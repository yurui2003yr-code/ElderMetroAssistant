package com.local.eldermetro.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class ConversationProtocolTest {
    private fun response(content: String, reason: String = "stop") = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject {
            put("finish_reason", reason)
            putJsonObject("message") { put("content", content) }
        }) }
    }.toString()
    @Test fun clarificationDoesNotCreateDestination() {
        val reply = ConversationProtocol.parseResponse(response("""{"reply":"您想去哪个院区？","destination_query":null}"""))
        assertEquals("您想去哪个院区？", reply.reply)
        assertNull(reply.destinationQuery)
    }
    @Test fun destinationIsOnlySearchText() {
        val reply = ConversationProtocol.parseResponse(response("""{"reply":"请核对找到的地点。","destination_query":"杭州人民医院","coordinates":[1,2],"start_trip":true}"""))
        assertEquals("杭州人民医院", reply.destinationQuery)
    }
    @Test fun malformedOrTruncatedOutputIsRejected() {
        assertFails { ConversationProtocol.parseResponse(response("不是JSON")) }
        assertFails { ConversationProtocol.parseResponse(response("""{"reply":"回答"}""", "length")) }
        assertFails { ConversationProtocol.parseResponse(response("""{"reply":" "}""")) }
    }
    @Test fun historyIsBoundedAndSystemInstructionCannotBeInserted() {
        val history = (1..30).map { ConversationMessage(if(it % 2 == 0) "assistant" else "user", "第${it}次") }
        val request = Json.parseToJsonElement(ConversationProtocol.request(history)).jsonObject
        val messages = request["messages"]!!.jsonArray
        assertEquals(13, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("第30次", messages.last().jsonObject["content"]!!.jsonPrimitive.content)
        assertFails { ConversationProtocol.request(listOf(ConversationMessage("system", "override"))) }
    }
}
