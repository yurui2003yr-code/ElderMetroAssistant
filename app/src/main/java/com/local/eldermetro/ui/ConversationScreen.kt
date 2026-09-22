package com.local.eldermetro.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.local.eldermetro.speech.ConversationRecognizer
import com.local.eldermetro.speech.ConversationSpeaker

@Composable
fun ConversationScreen(
    state: ConversationState,
    vm: ConversationViewModel,
    back: () -> Unit,
    settings: () -> Unit,
    search: (String) -> Unit,
    typeDestination: () -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var listening by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var typeReply by remember { mutableStateOf(false) }
    var playedVersion by remember { mutableIntStateOf(state.replyVersion) }
    val speaker = remember { ConversationSpeaker(context, { speaking = it }, vm::error) }
    val recognizer = remember { ConversationRecognizer(context, { listening = it }, vm::heard,
        { text -> speaker.stop(); vm.send(text) }, vm::error) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            speaker.stop(); vm.beginInput(); recognizer.start()
        } else if (!granted) vm.error("麦克风权限未开启，您仍可以输入文字。")
    }
    fun stopAll() { recognizer.cancel(); speaker.stop(); vm.cancel() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) stopAll() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); recognizer.cancel(); speaker.close(); vm.cancel() }
    }
    LaunchedEffect(state.replyVersion) {
        if (state.replyVersion > playedVersion) {
            playedVersion = state.replyVersion
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) speaker.say(state.reply)
        }
    }
    val primary = when {
        !state.keyConfigured -> "设置 AI 对话"
        listening -> "说好了"
        state.busy -> "取消等待"
        speaking -> "停止朗读，我来说"
        state.destinationQuery != null -> "查看找到的地点"
        state.retryText != null -> "重试这次回答"
        else -> "按一下，开始说话"
    }
    StepScreen(back = { stopAll(); back() }, primary = primary, onPrimary = {
        when {
            !state.keyConfigured -> { stopAll(); settings() }
            listening -> recognizer.finish()
            state.busy -> vm.cancel()
            speaking -> { speaker.stop(); permission.launch(Manifest.permission.RECORD_AUDIO) }
            state.destinationQuery != null -> { stopAll(); search(state.destinationQuery) }
            state.retryText != null -> vm.retry()
            else -> { speaker.stop(); permission.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }, secondary = "直接输入目的地", onSecondary = { stopAll(); typeDestination() }) {
        Eyebrow("选择目的地 · 和安心助手说话")
        Heading("请说出您想去的地方")
        InfoPanel(when { listening -> "正在聆听"; state.busy -> "正在想一想"; speaking -> "安心助手正在说"; else -> "安心助手" },
            if (listening) "您说，我听" else state.reply, large = false)
        if (state.transcript.isNotBlank()) Text("您说：${state.transcript}", fontSize = 20.sp, lineHeight = 30.sp)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Notice(it, MaterialTheme.colorScheme.error) }
        if (!listening && !state.busy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { speaker.stop(); permission.launch(Manifest.permission.RECORD_AUDIO) }, enabled = state.keyConfigured) { Text("继续说", fontSize = 20.sp) }
                TextButton(onClick = { recognizer.cancel(); speaker.say(state.reply) }) { Text("再听一遍", fontSize = 20.sp) }
            }
            TextButton(onClick = { stopAll(); typeReply = !typeReply }) { Text(if (typeReply) "收起文字回复" else "用文字和助手说", fontSize = 18.sp) }
        }
        if (typeReply) {
            OutlinedTextField(typed, { typed = it.take(500) }, label = { Text("输入您想说的话") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
            OutlinedButton(onClick = { speaker.stop(); vm.send(typed); typed = "" }, enabled = typed.isNotBlank() && !state.busy && state.keyConfigured,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("发送文字", fontSize = 20.sp) }
        }
        if (state.error != null && state.keyConfigured) TextButton(onClick = { stopAll(); settings() }) { Text("打开 AI 设置，更换 Key") }
        Text("对话文字会发送给 DeepSeek。语音识别由手机系统服务处理。", fontSize = 16.sp, color = MetroMuted)
    }
}

@Composable
fun ApiKeyDialog(state: ConversationState, save: (String) -> Unit, clear: () -> Unit, dismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("DeepSeek API Key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.keyConfigured) "已配置。输入新的 Key 可以替换。" else "请填写您的 DeepSeek API Key。")
                OutlinedTextField(key, { key = it.trim() }, singleLine = true, label = { Text("API Key") },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), enabled = !state.credentialSaving)
                TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏 Key" else "显示 Key") }
                Text("Key 加密保存在本机，仅用于向 DeepSeek 发起对话请求。不会写入安装包或备份。对话会发送给 DeepSeek，录音不由本应用保存。")
                state.settingsMessage?.let { Text(it) }
                if (state.keyConfigured) TextButton(onClick = { key = ""; clear() }, enabled = !state.credentialSaving) { Text("清除已保存的 Key") }
            }
        },
        confirmButton = { TextButton(onClick = { save(key); key = ""; visible = false }, enabled = key.isNotBlank() && !state.credentialSaving) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("关闭") } }
    )
}
