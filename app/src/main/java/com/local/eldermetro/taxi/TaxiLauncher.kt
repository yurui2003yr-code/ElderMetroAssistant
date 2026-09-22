package com.local.eldermetro.taxi

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.local.eldermetro.domain.GeoPoint
import com.local.eldermetro.domain.Place

fun walkingNavigationUri(place: Place): Uri = Uri.parse("amapuri://openFeature").buildUpon()
    .appendQueryParameter("featureName","OnFootNavi").appendQueryParameter("sourceApplication","ElderMetroAssistant")
    .appendQueryParameter("lat",place.point.latitude.toString()).appendQueryParameter("lon",place.point.longitude.toString())
    .appendQueryParameter("dev","0").build()

fun nativeRouteUri(place: Place): Uri = Uri.parse("amapuri://route/plan/").buildUpon()
    .appendQueryParameter("sourceApplication","ElderMetroAssistant")
    .appendQueryParameter("dlat",place.point.latitude.toString()).appendQueryParameter("dlon",place.point.longitude.toString())
    .appendQueryParameter("dname",place.name).appendQueryParameter("dev","0").appendQueryParameter("t","0").appendQueryParameter("m","0").build()

fun webRouteUri(place: Place, mode: String): Uri = Uri.parse("https://uri.amap.com/navigation").buildUpon()
    .appendQueryParameter("to",place.point.api()+","+place.name).appendQueryParameter("mode",mode)
    .appendQueryParameter("src","ElderMetroAssistant").appendQueryParameter("callnative","0").build()

private fun openNativeOrWeb(context: Context, place: Place, walking: Boolean): String {
    require(place.point.valid && !place.demo)
    val native = Intent(Intent.ACTION_VIEW,if(walking) walkingNavigationUri(place) else nativeRouteUri(place))
        .setPackage("com.autonavi.minimap").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(native)
        return if(walking) "已打开高德步行导航，返回本应用可继续地铁行程" else "已打开高德路线页，请选择打车并核对上车点和费用"
    } catch (_: android.content.ActivityNotFoundException) { }
      catch (_: SecurityException) { }
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW,webRouteUri(place,if(walking) "walk" else "car")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "未能打开高德客户端，已打开网页；实时导航和打车请安装高德"
    } catch (_: android.content.ActivityNotFoundException) { "没有高德或浏览器，请安装高德后重试" }
      catch (_: SecurityException) { "系统未允许打开地图，请手动打开高德" }
}
fun openWalkingNavigation(context: Context, place: Place) = openNativeOrWeb(context,place,true)
fun openAMapRoute(context: Context, place: Place) = openNativeOrWeb(context,place,false)

data class TaxiLaunchResult(val url: String, val message: String, val isTaxiPage: Boolean = false)
interface TaxiLauncher { suspend fun createTaxiLink(origin: GeoPoint, destination: GeoPoint): TaxiLaunchResult }
/** Official documented HTTPS route URI. This does not submit a taxi order. */
class FallbackTaxiLauncher : TaxiLauncher {
    override suspend fun createTaxiLink(origin: GeoPoint, destination: GeoPoint): TaxiLaunchResult {
        require(origin.valid && destination.valid)
        val url = Uri.parse("https://uri.amap.com/navigation").buildUpon()
            .appendQueryParameter("from",origin.api()).appendQueryParameter("to",destination.api())
            .appendQueryParameter("mode","car").appendQueryParameter("src","ElderMetroAssistant").appendQueryParameter("callnative","1").build().toString()
        return TaxiLaunchResult(url,"请在高德中选择打车，并确认上车点、价格和支付")
    }
}
/** Replace only after a supported taxi partner API has been obtained and verified. */
class AMapTaxiLauncher(private val fallback: TaxiLauncher = FallbackTaxiLauncher()) : TaxiLauncher by fallback
class LaunchTaxiUseCase(private val launcher: TaxiLauncher = AMapTaxiLauncher()) {
    suspend operator fun invoke(context: Context, origin: GeoPoint, destination: GeoPoint): String {
        val link = launcher.createTaxiLink(origin,destination)
        val intent = Intent(Intent.ACTION_VIEW,Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            val installed = context.packageManager.getLaunchIntentForPackage("com.autonavi.minimap") != null
            context.startActivity(intent)
            if (installed) link.message else "未检测到高德，已打开网页；请安装高德后选择打车"
        } catch (_: android.content.ActivityNotFoundException) { "没有可打开链接的应用，请安装高德或浏览器" }
          catch (_: SecurityException) { "无法打开高德，请手动打开并选择打车" }
    }
}
