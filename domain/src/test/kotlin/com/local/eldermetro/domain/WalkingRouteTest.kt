package com.local.eldermetro.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class WalkingRouteTest {
    private val destination=Place("x","测试入口","","测试市","",GeoPoint(31.201,121.401))
    private val raw="""{"route":{"origin":"121.400,31.200","destination":"121.401,31.201","paths":[{"duration":"300","distance":"250","steps":[{"instruction":"沿人行道直走","polyline":"121.400,31.200;121.401,31.201"}]}]}}"""
    private fun parse(text:String=raw)=parseWalkingRoute(Json.parseToJsonElement(text).jsonObject,destination)
    @Test fun parsesRealWalkingGeometryAndInstructions(){ val route=parse()!!; assertEquals(300,route.seconds); assertEquals(250,route.meters); assertEquals(2,route.points.size); assertEquals("沿人行道直走",route.steps.single()) }
    @Test fun rejectsMissingTimeGeometryAndWrongDestination(){ assertNull(parse(raw.replace("\"300\"","[]"))); assertNull(parse(raw.replace("121.400,31.200;121.401,31.201","NaN,31"))); assertNull(parse(raw.replace("\"destination\":\"121.401,31.201\"","\"destination\":\"120,30\""))); assertNull(parse("{}")) }
}
