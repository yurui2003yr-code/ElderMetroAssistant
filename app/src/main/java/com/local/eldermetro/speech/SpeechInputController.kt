package com.local.eldermetro.speech
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
class SpeechInputController(private val context: Context) {
    fun intent(): Intent? = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出目的地")
    }.takeIf { it.resolveActivity(context.packageManager) != null }
}
