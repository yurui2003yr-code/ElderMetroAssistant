package com.local.eldermetro.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.eldermetro.data.ai.*
import com.local.eldermetro.data.local.DeepSeekKeyStore
import com.local.eldermetro.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ConversationState(
    val reply: String = "您好，今天想去哪里？您可以慢慢说。",
    val transcript: String = "",
    val busy: Boolean = false,
    val destinationQuery: String? = null,
    val replyVersion: Int = 0,
    val error: String? = null,
    val keyConfigured: Boolean = false,
    val settingsMessage: String? = null,
    val credentialSaving: Boolean = false,
    val retryText: String? = null
)

class ConversationViewModel(app: Application) : AndroidViewModel(app) {
    private val credentials = DeepSeekKeyStore(app)
    private val gateway: ConversationGateway = DeepSeekClient()
    private val mutable = MutableStateFlow(ConversationState())
    val state = mutable.asStateFlow()
    private val history = mutableListOf<ConversationMessage>()
    private var request: Job? = null
    private var generation = 0

    init { viewModelScope.launch { refreshCredential() } }
    private suspend fun refreshCredential() {
        val exists = withContext(Dispatchers.IO) { runCatching { !credentials.read().isNullOrBlank() } }
        mutable.update { it.copy(keyConfigured = exists.getOrDefault(false), settingsMessage = if (exists.isFailure) "无法读取已保存的 Key，请重新设置。" else it.settingsMessage) }
    }
    fun saveKey(value: String) {
        if (state.value.credentialSaving) return
        cancel()
        history.clear()
        mutable.update { it.copy(credentialSaving = true, settingsMessage = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { credentials.save(value) } }
            mutable.update { it.copy(credentialSaving = false, settingsMessage = if (result.isSuccess) "Key 已加密保存在本机。" else "保存失败，请检查 Key 格式后重试。") }
            refreshCredential()
        }
    }
    fun clearKey() {
        if (state.value.credentialSaving) return
        cancel(); history.clear()
        mutable.update { it.copy(credentialSaving = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { credentials.clear() } }
            mutable.update { it.copy(credentialSaving = false, settingsMessage = if (result.isSuccess) "Key 已清除。" else "清除失败，请重试。") }
            refreshCredential()
        }
    }
    fun error(message: String) { mutable.update { it.copy(error = message) } }
    fun heard(text: String) { mutable.update { it.copy(transcript = text, error = null) } }
    fun beginInput() { mutable.update { it.copy(transcript = "", destinationQuery = null, retryText = null, error = null) } }
    fun send(text: String) {
        val clean = text.trim()
        if (clean.isBlank() || state.value.busy || state.value.credentialSaving) return
        if (clean.length > ConversationProtocol.MAX_INPUT) { error("这段话有些长，请分开说，每次不超过500字。"); return }
        val revision = ++generation
        mutable.update { it.copy(transcript = clean, busy = true, error = null, destinationQuery = null, retryText = clean) }
        request = viewModelScope.launch {
            try {
                val key = withContext(Dispatchers.IO) { credentials.read() }
                if (key.isNullOrBlank()) throw ConversationFailure("请先让家属在设置中填写 DeepSeek API Key。")
                val user = ConversationMessage("user", clean)
                val result = gateway.reply(key, history.takeLast(10) + user)
                ensureActive()
                if (generation != revision) return@launch
                history.add(user)
                history.add(ConversationMessage("assistant", result.reply))
                while (history.size > 12) history.removeAt(0)
                mutable.update { it.copy(reply = result.reply, busy = false, destinationQuery = result.destinationQuery,
                    replyVersion = it.replyVersion + 1, retryText = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == revision) mutable.update { it.copy(busy = false, error = (e as? ConversationFailure)?.userMessage ?: "AI 暂时不可用，请重试或输入目的地。") }
            }
        }
    }
    fun retry() { state.value.retryText?.let(::send) }
    fun cancel() {
        generation++
        request?.cancel(); request = null
        mutable.update { it.copy(busy = false) }
    }
    fun reset() { cancel(); history.clear(); mutable.update { ConversationState(keyConfigured = it.keyConfigured, settingsMessage = it.settingsMessage) } }
}
