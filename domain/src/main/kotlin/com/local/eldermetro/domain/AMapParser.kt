package com.local.eldermetro.domain

import kotlinx.serialization.json.*

fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
fun JsonObject.obj(key: String) = get(key) as? JsonObject
fun JsonObject.items(key: String): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
fun parsePoint(value: String?): GeoPoint? { val pair = value?.split(',') ?: return null; if (pair.size != 2) return null; return GeoPoint(pair[1].toDoubleOrNull() ?: return null, pair[0].toDoubleOrNull() ?: return null).takeIf { it.valid } }
fun parseStation(obj: JsonObject): Station? { val id = obj.text("id") ?: return null; val name = obj.text("name") ?: return null; return Station(id,name,parsePoint(obj.text("location"))) }

object AMapParser {
    // The live API emits railway placeholders containing empty arrays on metro segments.
    private fun JsonElement?.hasTransportData(): Boolean = when (this) {
        null, JsonNull -> false
        is JsonObject -> values.any { it.hasTransportData() }
        is JsonArray -> any { it.hasTransportData() }
        is JsonPrimitive -> !contentOrNull.isNullOrBlank()
    }
    fun places(root: JsonObject): List<Place> = root.items("pois").mapNotNull { p ->
        val point = parsePoint(p.text("location")) ?: return@mapNotNull null
        Place(p.text("id"),p.text("name") ?: return@mapNotNull null,p.text("address").orEmpty(),p.text("cityname") ?: return@mapNotNull null,p.text("adname").orEmpty(),point,publicFacility = p.text("typecode")?.take(2) in listOf("09","11","13","15"))
    }
    fun routes(root: JsonObject, destination: Place, now: Long): List<MetroRoute> {
        val route = root.obj("route") ?: return emptyList()
        val origin = parsePoint(route.text("origin")) ?: return emptyList()
        val end = parsePoint(route.text("destination")) ?: return emptyList()
        if (end.distanceTo(destination.point) > 200) return emptyList()
        return route.items("transits").mapIndexedNotNull { index, transit -> runCatching {
            val segments = mutableListOf<MetroSegment>()
            var pendingWalk = 0
            var walkSeconds = 0
            var completeWalkTime = true
            require((transit["segments"] as? JsonArray)?.size == transit.items("segments").size)
            for (part in transit.items("segments")) {
                require(!part["railway"].hasTransportData() && !part["taxi"].hasTransportData())
                part.obj("walking")?.takeIf { it.isNotEmpty() }?.let { w ->
                    pendingWalk += (w.text("distance")?.toDoubleOrNull()?.toInt() ?: error("walk missing")).also { require(it >= 0) }
                    val seconds = w.text("duration")?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..21600.0 }?.toInt()
                    if (seconds == null) completeWalkTime = false else walkSeconds += seconds
                }
                val lines = part.obj("bus")?.items("buslines").orEmpty()
                require(part.obj("bus").isNullOrEmpty() || (part.obj("bus")?.get("buslines") as? JsonArray)?.let { it.isEmpty() || lines.isNotEmpty() } == true)
                if (lines.isEmpty()) continue
                // Alternative buslines are not sequential rides. Select one supported, complete metro alternative.
                val metro = lines.firstOrNull { it.text("type") in listOf("地铁线路", "地铁") } ?: error("unsupported transport")
                val boarding = parseStation(metro.obj("departure_stop") ?: error("departure missing")) ?: error("departure invalid")
                val alighting = parseStation(metro.obj("arrival_stop") ?: error("arrival missing")) ?: error("arrival invalid")
                val rawVia = metro.items("via_stops")
                val via = rawVia.map { parseStation(it) ?: error("via invalid") }
                require(metro.text("via_num")?.toIntOrNull() == via.size)
                val s = MetroSegment(metro.text("id") ?: error("line id missing"), metro.text("name")?.substringBefore('(')?.substringBefore('（') ?: error("name missing"),boarding,alighting,via,via.size + 1,metro.text("duration")?.toDoubleOrNull()?.toInt() ?: error("duration missing"),entrance = part.obj("entrance")?.text("name"),exit = part.obj("exit")?.text("name"),walkBeforeMeters = pendingWalk)
                require(s.valid); segments += s.copy(entrancePoint = parsePoint(part.obj("entrance")?.text("location"))); pendingWalk = 0
            }
            val parsed = MetroRoute("amap-$index",origin,destination,transit.text("duration")?.toDoubleOrNull()?.toInt() ?: error("duration missing"),segments,pendingWalk,now)
            val reportedWalk = transit.text("walking_distance")?.toDoubleOrNull()?.toInt() ?: error("walk missing")
            require(kotlin.math.abs(parsed.totalWalk - reportedWalk) <= 20 && parsed.valid)
            parsed.copy(walkDurationSeconds = walkSeconds.takeIf { completeWalkTime && it <= parsed.durationSeconds - parsed.metroDurationSeconds })
        }.getOrNull() }
    }
}
