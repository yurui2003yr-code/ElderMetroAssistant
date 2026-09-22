package com.local.eldermetro.domain

object DemoData {
    val origin = LocationFix(GeoPoint(31.2304, 121.4737), "演示城市", "示例区", 0)
    val places = listOf(
        Place("demo-hospital", "市人民医院", "示例区健康路 100 号（虚构）", "演示城市", "示例区", GeoPoint(31.20, 121.44), true, true),
        Place("demo-station", "火车站", "示例区车站路 1 号（虚构）", "演示城市", "示例区", GeoPoint(31.25, 121.46), true, true),
        Place("demo-family", "女儿家", "示例区幸福小区（虚构）", "演示城市", "示例区", GeoPoint(31.22, 121.48), true),
        Place("demo-park", "人民公园", "示例区公园路 8 号（虚构）", "演示城市", "示例区", GeoPoint(31.23, 121.45), true, true),
        Place("demo-park-2", "人民公园", "示例新区公园路 20 号（虚构）", "演示城市", "示例新区", GeoPoint(31.27, 121.49), true, true),
        Place("demo-community", "社区中心", "示例区社区路 2 号（虚构）", "演示城市", "示例区", GeoPoint(31.21, 121.44), true, true)
    )
    val favorites = places.take(3).mapIndexed { i, p -> FavoriteDestination(p, p.name, i) }
    fun routes(destination: Place, now: Long): List<MetroRoute> {
        val a = MetroSegment("demo-2", "2号线", Station("a", "幸福路站"), Station("f", "人民广场站"), listOf("春光路站", "文化路站", "中心路站", "广场西站").mapIndexed { i, s -> Station("a$i", s) }, 5, 900, "浦东国际机场", "2号口", null, 250)
        val b = MetroSegment("demo-1", "1号线", a.alighting, Station("j", "医院站"), listOf(Station("g", "大学路站"), Station("h", "健康路站")), 3, 540, "莘庄", null, "2号口", 120)
        val base = MetroRoute("recommended", origin.point, destination, 2880, listOf(a, b), 430, now, true, walkDurationSeconds = 840)
        return listOf(base, base.copy(id = "faster", durationSeconds = 2400, exitWalkMeters = 900), base.copy(id = "no-metro", segments = emptyList()), base.copy(id = "too-slow", durationSeconds = 7000, segments = listOf(a.copy(walkBeforeMeters = 10)), exitWalkMeters = 10))
    }
}
class DemoPlaceProvider : PlaceRepository {
    override suspend fun search(query: String, location: LocationFix, poiId: String?): List<Place> = DemoData.places.filter { (poiId != null && it.id == poiId) || it.name.contains(query) }
}
class DemoRouteProvider : RouteRepository {
    override suspend fun routes(origin: LocationFix, destination: Place) = if(destination.id == "demo-community") emptyList() else DemoData.routes(destination, System.currentTimeMillis())
}
