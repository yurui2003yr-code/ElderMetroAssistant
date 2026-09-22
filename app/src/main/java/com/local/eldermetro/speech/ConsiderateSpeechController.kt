package com.local.eldermetro.speech

import android.app.Activity
import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Optional speech yields immediately to navigation away, focus loss, calls and other audio. */
class ConsiderateSpeechController(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private var resumed = false
    private var ready = false
    private var speaking = false
    private var closed = false
    private var engine: TextToSpeech? = null
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change -> if (change != AudioManager.AUDIOFOCUS_GAIN) stop() },handler).build()
    private val guard = object : Runnable {
        override fun run() {
            if (!speaking || closed) return
            if (!resumed || communicationActive(audio)) stop()
            else handler.postDelayed(this,100)
        }
    }
    init {
        app.registerActivityLifecycleCallbacks(this)
        engine = TextToSpeech(app) { status ->
            if (!closed && status == TextToSpeech.SUCCESS) {
                ready = true; engine?.language = Locale.SIMPLIFIED_CHINESE
                engine?.setAudioAttributes(attributes)
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { handler.post { stop() } }
                    @Deprecated("Legacy callback") override fun onError(utteranceId: String?) { handler.post { stop() } }
                })
            }
        }
    }
    // The service is created after the Activity resumed; its current state is supplied explicitly.
    fun sayIfIdle(text: String, appResumed: Boolean) {
        resumed = appResumed
        if (closed || !ready || !resumed || communicationActive(audio) || audio.isMusicActive) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        if (communicationActive(audio) || !resumed) { stop(); return }
        speaking = true
        if (engine?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"metro-reminder") != TextToSpeech.SUCCESS) stop()
        else { handler.post(guard); handler.postDelayed({ stop() },10000) }
    }
    fun stop() { speaking = false; handler.removeCallbacksAndMessages(null); engine?.stop(); audio.abandonAudioFocusRequest(focus) }
    fun close() { closed = true; stop(); engine?.shutdown(); app.unregisterActivityLifecycleCallbacks(this) }
    override fun onActivityResumed(activity: Activity) { resumed = true }
    override fun onActivityPaused(activity: Activity) { resumed = false; stop() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
    companion object { fun communicationActive(audio: AudioManager) = audio.mode != AudioManager.MODE_NORMAL }
}
