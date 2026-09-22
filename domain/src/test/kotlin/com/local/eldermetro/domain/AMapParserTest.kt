package com.local.eldermetro.domain

import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic official-schema fixture, not a saved user response. */
class AMapParserTest {
    private val destination = Place("p","测试医院","虚构地址","测试市","测试区",GeoPoint(31.2,121.4))
    private val fixture = """{"route":{"origin":"121.5,31.3","destination":"121.4,31.2","transits":[{"duration":"1500","walking_distance":"630","segments":[{"walking":{"distance":"200"},"bus":{"buslines":[{"id":"line1","name":"地铁1号线(起点--终点)","type":"地铁线路","duration":"600","departure_stop":{"id":"s1","name":"甲站"},"arrival_stop":{"id":"s3","name":"丙站"},"via_num":"1","via_stops":[{"id":"s2","name":"乙站"}]}]},"entrance":{"name":"1号口"},"exit":{"name":"2号口"}},{"walking":{"distance":"430"}}]}]}}"""
    private fun parse(raw: String = fixture) = AMapParser.routes(Json.parseToJsonElement(raw).jsonObject,destination,0)
    @Test fun parsesWalkStationsAndExits() { val r = parse().single(); assertEquals(630,r.totalWalk); assertEquals(2,r.totalStops); assertEquals("2号口",r.segments.single().exit); assertNull(r.segments.single().direction) }
    @Test fun rejectsBusOnly() { assertTrue(parse(fixture.replace("地铁线路","普通公交")).isEmpty()) }
    @Test fun acceptsEmptyRailwayPlaceholderButRejectsActualRailwayAndTaxi() {
        fun withTransport(value: String) = fixture.replace("\"entrance\":", "$value,\"entrance\":")
        assertEquals(1,parse(withTransport("\"railway\":{\"via_stops\":[],\"alters\":[],\"spaces\":[]}")).size)
        assertTrue(parse(withTransport("\"railway\":{\"id\":\"train1\",\"via_stops\":[]}")).isEmpty())
        assertTrue(parse(withTransport("\"taxi\":{\"distance\":\"300\"}")).isEmpty())
    }
    @Test fun rejectsMissingWalkingDistance() { assertTrue(parse(fixture.replace("\"distance\":\"200\"","\"distance\":[]")).isEmpty()) }
    @Test fun rejectsUnreconciledWalking() { assertTrue(parse(fixture.replace("\"walking_distance\":\"630\"","\"walking_distance\":\"900\"")).isEmpty()) }
    @Test fun rejectsMissingStops() { assertTrue(parse(fixture.replace("\"via_num\":\"1\"","\"via_num\":\"4\"")).isEmpty()) }
    @Test fun rejectsWrongDestination() { assertTrue(AMapParser.routes(Json.parseToJsonElement(fixture).jsonObject,destination.copy(point=GeoPoint(30.0,120.0)),0).isEmpty()) }
    @Test fun emptyArrayFieldsDoNotCrash() { assertTrue(AMapParser.places(Json.parseToJsonElement("""{"pois":[{"id":[],"name":[],"location":[]}]}""").jsonObject).isEmpty()); assertTrue(parse("{}").isEmpty()) }
    @Test fun coordinatesAreValidated() { assertNull(parsePoint("NaN,31")); assertNull(parsePoint("200,31")); assertNull(parsePoint("121")); assertEquals(GeoPoint(31.0,121.0),parsePoint("121,31")) }
    @Test fun alternateDirectionNotGuessedFromName() { assertNull(parse().single().segments.single().direction) }
    @Test fun walkingTimeUsesOnlyCompleteApiDurations() {
        val timed = fixture.replace("\"distance\":\"200\"", "\"distance\":\"200\",\"duration\":\"180\"").replace("\"distance\":\"430\"", "\"distance\":\"430\",\"duration\":\"420\"")
        assertEquals(600,parse(timed).single().walkDurationSeconds)
        assertEquals(600,parse(timed).single().metroDurationSeconds)
        assertNull(parse().single().walkDurationSeconds)
        assertNull(parse(timed.replace("\"duration\":\"420\"", "\"duration\":[]")).single().walkDurationSeconds)
        assertNull(parse(timed.replace("\"duration\":\"420\"", "\"duration\":\"20000\"")).single().walkDurationSeconds)
    }
}
