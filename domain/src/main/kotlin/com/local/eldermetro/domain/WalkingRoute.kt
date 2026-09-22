package com.local.eldermetro.domain

import kotlinx.serialization.json.JsonObject

data class WalkingRoute(val origin: GeoPoint, val destination: Place, val seconds: Int, val meters: Int, val steps: List<String>, val points: List<GeoPoint>)

fun parseWalkingRoute(root: JsonObject, destination: Place): WalkingRoute? = runCatching {
    val route = root.obj("route") ?: error("missing route")
    val origin = parsePoint(route.text("origin")) ?: error("missing origin")
    val end = parsePoint(route.text("destination")) ?: error("missing destination")
    require(end.distanceTo(destination.point) <= 200)
    val path = route.items("paths").firstOrNull() ?: error("missing path")
    val seconds = path.text("duration")?.toIntOrNull() ?: error("missing time")
    val meters = path.text("distance")?.toIntOrNull() ?: error("missing distance")
    require(seconds in 1..172800 && meters in 1..100000)
    val rawSteps = path.items("steps")
    val steps = rawSteps.map { it.text("instruction") ?: error("missing instruction") }
    val points = rawSteps.flatMap { it.text("polyline")?.split(';')?.map { p -> parsePoint(p) ?: error("invalid point") } ?: error("missing geometry") }
    require(steps.isNotEmpty() && points.size >= 2)
    WalkingRoute(origin,destination,seconds,meters,steps,points)
}.getOrNull()
