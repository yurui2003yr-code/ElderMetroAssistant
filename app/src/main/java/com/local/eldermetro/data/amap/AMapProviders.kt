package com.local.eldermetro.data.amap

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.local.eldermetro.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AMapClient(private val key: String) {
    suspend fun get(path: String, params: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val query = (params + ("key" to key)).entries.joinToString("&") { URLEncoder.encode(it.key,"UTF-8") + "=" + URLEncoder.encode(it.value,"UTF-8") }
        val connection = URL("https://restapi.amap.com/v3/$path?$query").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 12000; connection.readTimeout = 12000
            check(connection.responseCode == 200) { "高德服务暂不可用" }
            val raw = connection.inputStream.bufferedReader().use { it.readTextLimited() }
            val root = Json.parseToJsonElement(raw).jsonObject
            check(root.text("status") == "1") { "高德服务未返回有效结果" }
            root
        } finally { connection.disconnect() }
    }
    private fun java.io.Reader.readTextLimited(): String { val buffer = CharArray(8192); val out = StringBuilder(); while (true) { val count = read(buffer); if (count < 0) break; check(out.length + count <= 4_000_000); out.append(buffer,0,count) }; return out.toString() }
}
class AMapPlaceProvider(private val client: AMapClient) : PlaceRepository {
    override suspend fun search(query: String, location: LocationFix, poiId: String?): List<Place> {
        val raw = if (!poiId.isNullOrBlank()) client.get("place/detail",mapOf("id" to poiId)) else client.get("place/text",mapOf("keywords" to query,"city" to location.city,"citylimit" to "true","offset" to "20","page" to "1","extensions" to "base"))
        return AMapParser.places(raw)
    }
}
class AMapRouteProvider(private val client: AMapClient) : RouteRepository {
    override suspend fun routes(origin: LocationFix, destination: Place): List<MetroRoute> {
        require(origin.city == destination.city)
        val raw = client.get("direction/transit/integrated",mapOf("origin" to origin.point.api(),"destination" to destination.point.api(),"city" to origin.city,"extensions" to "all","strategy" to "0"))
        val routes = AMapParser.routes(raw,destination,System.currentTimeMillis())
        val lines = mutableMapOf<String, JsonObject?>()
        return routes.map { r -> r.copy(segments = r.segments.map { s ->
            if (!lines.containsKey(s.id)) {
                lines[s.id] = try { client.get("bus/lineid",mapOf("id" to s.id,"extensions" to "all")).items("buslines").firstOrNull { it.text("id") == s.id } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
            val line = lines[s.id]
            val direction = line?.takeIf { it.text("loop") != "1" }?.let { resolveDirection(s,it.items("busstops").mapNotNull(::parseStation),it.text("end_stop")) }
            if (direction == null) Log.i("MetroDiagnostics","direction_unavailable")
            s.copy(direction = direction)
        }) }
    }
}
class AMapLocationProvider(context: Context, private val client: AMapClient) : LocationRepository {
    private val manager = context.getSystemService(LocationManager::class.java)
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun freshGps(): Location = withContext(Dispatchers.Main.immediate) {
        withTimeout(15000) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos in 0..60_000_000_000L && location.hasAccuracy() && location.accuracy <= 200 && continuation.isActive) {
                            manager.removeUpdates(this); continuation.resume(location)
                        }
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                try {
                    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
                    check(providers.isNotEmpty()) { "请开启手机定位" }
                    providers.forEach { manager.requestLocationUpdates(it,1000,0f,listener,Looper.getMainLooper()) }
                } catch (e: Exception) { manager.removeUpdates(listener); if (continuation.isActive) continuation.resumeWithException(e) }
            }
        }
    }
    suspend fun point(): GeoPoint {
        val location = try { freshGps() } catch (_: TimeoutCancellationException) { throw java.io.IOException("定位超时") }
        val converted = client.get("assistant/coordinate/convert",mapOf("locations" to GeoPoint(location.latitude,location.longitude).api(),"coordsys" to "gps"))
        return parsePoint(converted.text("locations")) ?: error("无法转换当前位置")
    }
    override suspend fun current(): LocationFix {
        val point = point()
        val address = client.get("geocode/regeo",mapOf("location" to point.api(),"extensions" to "base")).obj("regeocode")?.obj("addressComponent") ?: error("无法确认城市")
        val city = address.text("city") ?: address.text("province") ?: error("无法确认城市")
        return LocationFix(point,city,address.text("district").orEmpty(),System.currentTimeMillis())
    }
}
