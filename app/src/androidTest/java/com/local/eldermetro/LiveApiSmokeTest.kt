package com.local.eldermetro

import androidx.test.platform.app.InstrumentationRegistry
import com.local.eldermetro.data.amap.*
import com.local.eldermetro.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Explicit opt-in only. Uses public Beijing landmarks, never the user's location or address. */
class LiveApiSmokeTest {
    @Test fun realPublicPlacesAndMetroRoute() = runBlocking<Unit> {
        Assume.assumeTrue("Live API test requires explicit opt-in and configured key",InstrumentationRegistry.getArguments().getString("liveApi") == "true" && BuildConfig.ROUTE_PROVIDER == "amap" && BuildConfig.AMAP_WEB_KEY.isNotBlank())
        try {
            withTimeout(120000) {
                val client = AMapClient(BuildConfig.AMAP_WEB_KEY)
                val placeProvider = AMapPlaceProvider(client)
                val context = LocationFix(GeoPoint(39.9,116.35),"北京市","西城区",System.currentTimeMillis())
                val start = placeProvider.search("北京西站",context,null).firstOrNull { it.name == "北京西站" } ?: error("origin missing")
                val destination = placeProvider.search("北京大学人民医院",context,null).firstOrNull() ?: error("destination missing")
                assertEquals("北京市",destination.city)
                val raw = client.get("direction/transit/integrated",mapOf("origin" to start.point.api(),"destination" to destination.point.api(),"city" to "北京市","extensions" to "all","strategy" to "0"))
                val candidates = AMapParser.routes(raw,destination,System.currentTimeMillis())
                assertTrue("No complete metro route returned",candidates.isNotEmpty())
                val chosen = SelectBestMetroRouteUseCase()(candidates)
                assertNotNull(chosen)
                assertNotNull("Public route must provide walking time for this smoke check",chosen!!.walkDurationSeconds)
                assertTrue(chosen.metroDurationSeconds > 0)
                val line = client.get("bus/lineid",mapOf("id" to chosen.segments.first().id,"extensions" to "all")).items("buslines").firstOrNull()
                assertNotNull(line)
                val direction = resolveDirection(chosen.segments.first(),line!!.items("busstops").mapNotNull(::parseStation),line.text("end_stop"))
                assertNotNull("Expected ordered station evidence on this public route",direction)
                val converted = client.get("assistant/coordinate/convert",mapOf("locations" to "116.481499,39.990475","coordsys" to "gps"))
                assertNotNull(parsePoint(converted.text("locations")))
                val regeo = client.get("geocode/regeo",mapOf("location" to start.point.api(),"extensions" to "base"))
                assertNotNull(regeo.obj("regeocode")?.obj("addressComponent"))
                val entrance = chosen.segments.first().entrancePoint ?: chosen.segments.first().boarding.point!!
                val walkTarget = destination.copy(name="公共车站入口",point=entrance)
                val walking = parseWalkingRoute(client.get("direction/walking",mapOf("origin" to start.point.api(),"destination" to entrance.api())),walkTarget)
                assertNotNull("Walking API must return valid route geometry",walking)
                val bitmap = com.local.eldermetro.ui.WalkingMapLoader.load(walking!!)
                assertNotNull("Static route map must decode to an image",bitmap)
                val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"public-walking-map.png")
                file.outputStream().use { bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            }
        } catch (_: Exception) { fail("Live API verification failed; request URL and credentials are intentionally withheld") }
    }
}
