package com.local.eldermetro.data.ai

import com.local.eldermetro.domain.*
import kotlinx.coroutines.*
import java.net.URL
import java.net.SocketTimeoutException
import javax.net.ssl.HttpsURLConnection

class ConversationFailure(val userMessage: String) : Exception(userMessage)

class DeepSeekClient : ConversationGateway {
    override suspend fun reply(key: String, history: List<ConversationMessage>): ConversationReply = withContext(Dispatchers.IO) {
        val connection = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 35_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(ConversationProtocol.request(history).toByteArray(Charsets.UTF_8)) }
            ensureActive()
            when (connection.responseCode) {
                200 -> Unit
                401, 403 -> throw ConversationFailure("API Key 无效或无权限，请在设置中替换。")
                402 -> throw ConversationFailure("DeepSeek 余额不足，请联系家属处理。")
                429 -> throw ConversationFailure("AI 现在有些忙，请稍后再试。")
                else -> throw ConversationFailure("AI 暂时无法回答，请稍后再试或输入目的地。")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(2048)
                while (true) {
                    ensureActive()
                    val size = reader.read(buffer)
                    if (size < 0) break
                    output.append(buffer, 0, size)
                    if (output.length > 128_000) throw ConversationFailure("回答过长，请重新说一次。")
                }
                output.toString()
            }
            ensureActive()
            try { ConversationProtocol.parseResponse(body) }
            catch (_: Exception) { throw ConversationFailure("没有理解这次回答，请再说一次。") }
        } catch (e: CancellationException) { throw e }
        catch (e: ConversationFailure) { throw e }
        catch (_: SocketTimeoutException) { throw ConversationFailure("等待回答超时，请重试。") }
        catch (_: Exception) { throw ConversationFailure("网络连接失败，请检查网络后重试。") }
        finally { connection.disconnect() }
    }
}
