package com.local.eldermetro.app

import android.content.Context
import com.local.eldermetro.domain.*
import com.local.eldermetro.data.local.LocalDestinationStore
import com.local.eldermetro.data.amap.*
import com.local.eldermetro.BuildConfig

class AppContainer(context: Context) {
    val store = LocalDestinationStore(context)
    private val client = AMapClient(BuildConfig.AMAP_WEB_KEY)
    val liveLocation = AMapLocationProvider(context, client)
    suspend fun walking(origin: GeoPoint, destination: Place): WalkingRoute? = parseWalkingRoute(client.get("direction/walking",mapOf("origin" to origin.api(),"destination" to destination.point.api())),destination)
    val realSearchAvailable = BuildConfig.ROUTE_PROVIDER == "amap" && BuildConfig.AMAP_WEB_KEY.isNotBlank()
    var demo = BuildConfig.ROUTE_PROVIDER != "amap" || BuildConfig.AMAP_WEB_KEY.isBlank()
        private set
    var modeReason = if (demo) "演示数据 · 不可用于实际出行" else "高德实时查询"
        private set
    var places: PlaceRepository = if (demo) DemoPlaceProvider() else AMapPlaceProvider(client)
        private set
    var routes: RouteRepository = if (demo) DemoRouteProvider() else AMapRouteProvider(client)
        private set
    suspend fun location(): LocationFix = if (demo) DemoData.origin.copy(measuredAt = System.currentTimeMillis()) else liveLocation.current()
    fun useDemo(reason: String) { demo = true; modeReason = reason; places = DemoPlaceProvider(); routes = DemoRouteProvider() }
    fun useLive() { check(realSearchAvailable); demo = false; modeReason = "高德实时查询"; places = AMapPlaceProvider(client); routes = AMapRouteProvider(client) }
}
