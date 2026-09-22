package com.local.eldermetro.speech

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Lives only while the conversation screen is composed. Never records in the background. */
class ConversationRecognizer(
    private val context: Context,
    private val onListening: (Boolean) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var accepting = false
    private var generation = 0
    fun start() {
        cancel()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { onError("手机语音识别不可用，请输入文字。"); return }
        if (ConsiderateSpeechController.communicationActive(context.getSystemService(AudioManager::class.java))) {
            onError("通话或响铃期间请使用文字输入。"); return
        }
        try {
            accepting = true
            val revision = generation
            fun current() = accepting && generation == revision
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { r ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { if (current()) onListening(true) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { /* Keep the control occupied until results/error arrive. */ }
                    override fun onError(error: Int) {
                        if (!current()) return
                        accepting = false; onListening(false)
                        onError(when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限未开启，请允许权限或输入文字。"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络不通，请输入文字或重试。"
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听清，请再说一次。"
                            else -> "语音识别暂不可用，请重新说或输入文字。"
                        })
                    }
                    override fun onResults(results: Bundle?) {
                        if (!current()) return
                        accepting = false; onListening(false)
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (text.isNullOrBlank()) onError("没有听清，请再说一次。") else onResult(text)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (current()) partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                onListening(true)
                r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                })
            }
        } catch (_: Exception) { cancel(); onError("无法启动语音识别，请输入文字。") }
    }
    fun finish() { recognizer?.stopListening() }
    fun cancel() { generation++; accepting = false; recognizer?.cancel(); recognizer?.destroy(); recognizer = null; onListening(false) }
}

class ConversationSpeaker(context: Context, private val onSpeaking: (Boolean) -> Unit, private val onError: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var ready = false
    private var closed = false
    private var currentId: String? = null
    private var sequence = 0
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) stop() }.build()
    private var engine: TextToSpeech? = null
    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (!closed && status == TextToSpeech.SUCCESS) {
                val available = engine?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                ready = available != null && available >= TextToSpeech.LANG_AVAILABLE
                engine?.setSpeechRate(0.85f)
                engine?.setAudioAttributes(attributes)
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { handler.post { if (currentId == utteranceId) stop() } }
                    @Deprecated("Legacy callback") override fun onError(utteranceId: String?) { handler.post {
                        if (currentId == utteranceId) { stop(); onError("朗读失败，您可以阅读中间的回答。") }
                    } }
                })
            }
        }
    }
    fun say(text: String) {
        stop()
        if (!ready || closed) { onError("中文朗读暂不可用，您可以阅读中间的回答。"); return }
        if (ConsiderateSpeechController.communicationActive(audio) || audio.isMusicActive) { onError("其他音频正在使用手机，本次只显示文字。"); return }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { onError("无法开始朗读，您可以阅读文字。"); return }
        val id = "conversation-${++sequence}"
        currentId = id
        onSpeaking(true)
        if (engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            stop(); onError("朗读失败，您可以阅读中间的回答。")
        }
    }
    fun stop() { currentId = null; engine?.stop(); audio.abandonAudioFocusRequest(focus); onSpeaking(false) }
    fun close() { closed = true; stop(); handler.removeCallbacksAndMessages(null); engine?.shutdown() }
}
