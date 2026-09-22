package com.local.eldermetro.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.local.eldermetro.BuildConfig
import com.local.eldermetro.domain.WalkingRoute
import com.local.eldermetro.domain.Place
import kotlinx.coroutines.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object WalkingMapLoader {
    suspend fun load(route: WalkingRoute): Bitmap? = withContext(Dispatchers.IO) {
        // Keep every route vertex. If the URL is too long, do not draw shortcuts.
        val uri = Uri.parse("https://restapi.amap.com/v3/staticmap").buildUpon()
            .appendQueryParameter("key",BuildConfig.AMAP_WEB_KEY).appendQueryParameter("size","600*400")
            .appendQueryParameter("markers","mid,0x176858,起:${route.origin.api()}|mid,0xB3261E,终:${route.destination.point.api()}")
            .appendQueryParameter("paths","6,0x176858,1,,:" + route.points.joinToString(";") { it.api() }).build()
        if (uri.toString().length > 16000) return@withContext null
        val connection = URL(uri.toString()).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 12000; connection.readTimeout = 12000
            if (connection.responseCode != 200 || !connection.contentType.orEmpty().startsWith("image/")) return@withContext null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size > 4_000_000) return@withContext null
            BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        } finally { connection.disconnect() }
    }
}

@Composable fun WalkingMap(route: WalkingRoute) {
    var bitmap by remember(route) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(route) { mutableStateOf(true) }
    LaunchedEffect(route) {
        try { bitmap = WalkingMapLoader.load(route) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { bitmap = null }
        finally { loading = false }
    }
    bitmap?.let { Image(it.asImageBitmap(),"步行路线地图，起表示查询时的位置，终表示目的地",Modifier.fillMaxWidth().aspectRatio(1.5f)) }
        ?: Text(if (loading) "地图加载中…" else "地图暂不可用，请打开高德导航")
    Text("地图为本次查询的路线；走动后请刷新。实时位置和语音指路请用高德导航。")
}

@Composable fun PlaceMap(place: Place) {
    var bitmap by remember(place.identity) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(place.identity) { mutableStateOf(!place.demo) }
    LaunchedEffect(place.identity) {
        if (place.demo) return@LaunchedEffect
        try {
            bitmap = withContext(Dispatchers.IO) {
                if (BuildConfig.AMAP_WEB_KEY.isBlank()) return@withContext null
                val uri = Uri.parse("https://restapi.amap.com/v3/staticmap").buildUpon()
                    .appendQueryParameter("key", BuildConfig.AMAP_WEB_KEY)
                    .appendQueryParameter("location", place.point.api()).appendQueryParameter("zoom", "15")
                    .appendQueryParameter("size", "600*400")
                    .appendQueryParameter("markers", "mid,0x16634E,A:${place.point.api()}").build()
                val connection = URL(uri.toString()).openConnection() as HttpsURLConnection
                try {
                    connection.connectTimeout = 10000; connection.readTimeout = 10000
                    if (connection.responseCode != 200 || !connection.contentType.orEmpty().startsWith("image/")) return@withContext null
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            if (output.size() > 4_000_000) return@withContext null
                        }
                        output.toByteArray()
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } finally { connection.disconnect() }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { bitmap = null }
        finally { loading = false }
    }
    bitmap?.let { Image(it.asImageBitmap(), "高德地图，A 标记表示所选目的地", Modifier.fillMaxWidth().aspectRatio(1.5f)) }
        ?: Text(if (place.demo) "演示地点不显示真实地图" else if (loading) "正在加载位置地图…" else "地图暂不可用，请核对上方地址")
    if (!place.demo) Text("地图由高德提供，仅用于核对目的地位置。")
}
