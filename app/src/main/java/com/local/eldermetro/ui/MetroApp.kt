package com.local.eldermetro.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.eldermetro.domain.*

val MetroBrand = Color(0xFF16634E)
val MetroBackground = Color(0xFFFAFBF7)
val MetroInk = Color(0xFF192F29)
val MetroMuted = Color(0xFF536C60)
val MetroCard = Color(0xFFDEEEE4)
val MetroWarning = Color(0xFFFFF0D9)

@Composable fun MetroApp(vm: AppViewModel = viewModel(), conversation: ConversationViewModel = viewModel()) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = MetroBrand, onPrimary = Color.White, background = MetroBackground,
        surface = MetroBackground, onSurface = MetroInk, onBackground = MetroInk,
        surfaceVariant = MetroCard, onSurfaceVariant = MetroMuted
    ), typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, lineHeight = 30.sp))) {
        val context = LocalContext.current
        val ui by vm.ui.collectAsStateWithLifecycle()
        val local by vm.local.collectAsStateWithLifecycle()
        val chat by conversation.state.collectAsStateWithLifecycle()
        var query by rememberSaveable { mutableStateOf("") }
        var keyDialog by remember { mutableStateOf(false) }
        var manualBoard by remember { mutableStateOf(false) }
        var endTrip by remember { mutableStateOf(false) }
        var undoBoard by remember { mutableStateOf(false) }
        val trip = local.trip
        val reminderKey = "${trip.route?.generatedAt}:${trip.segmentIndex}:${trip.startedElapsed}"
        var reminderDismissed by rememberSaveable(reminderKey) { mutableStateOf(false) }
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.resumeTracking() }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
        val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { values ->
            if (values.values.none { it }) vm.message("定位权限未开启，请允许位置权限后重试。目的地已保留。")
        }
        val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.board(true) else manualBoard = true
        }
        fun locate() {
            vm.acceptPrivacy()
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        fun board() { if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.board(true) }
        BackHandler(ui.page != "home") { conversation.cancel(); vm.back() }
        Surface(Modifier.fillMaxSize()) {
            key(ui.page) {
                when (ui.page) {
                    "voice" -> ConversationScreen(chat, conversation, vm::back, { vm.page("settings"); keyDialog = true },
                        { vm.search(it) }, { vm.page("input") })
                    "home" -> {
                        val active = trip.phase !in listOf(TripPhase.IDLE, TripPhase.FINISHED)
                        StepScreen(primary = if (active) "继续当前行程" else "说出目的地",
                            onPrimary = { vm.page(if (active) "trip" else "voice") },
                            secondary = "输入目的地", onSecondary = { vm.page("input") },
                            settings = { vm.page("settings") }, status = ui.error, busy = ui.busy) {
                            Eyebrow("今天想去哪里？")
                            Text("常用目的地", fontSize = 20.sp, color = MetroMuted)
                            val favorites = local.favorites.filter { it.place.demo == vm.container.demo }.sortedBy { it.sortOrder }
                            favorites.forEach { favorite ->
                                DestinationTile(favorite.displayName,
                                    if (favorite.displayName.contains("家")) Color(0xFFEFDEDF) else MetroCard,
                                    !ui.busy) { vm.select(favorite.place) }
                            }
                            if (favorites.isEmpty()) {
                                Text("还没有常用地点", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { vm.page("settings") }) { Text("请家属添加常用地点", fontSize = 20.sp) }
                            }
                            if (active) TextButton(onClick = { vm.page("voice") }) { Text("和安心助手说话", fontSize = 20.sp) }
                            if (vm.container.demo) DemoNotice()
                            if (!vm.container.demo && !local.privacyAccepted) {
                                Text("地点与路线查询需要把当前位置和目的地发送给高德。", fontSize = 18.sp)
                                TextButton(onClick = { locate() }) { Text("同意查询并开启定位", fontSize = 20.sp) }
                            }
                        }
                    }
                    "input" -> StepScreen(vm::back, "查找地点", { vm.search(query) }, !ui.busy && query.isNotBlank(),
                        "改用语音", { vm.page("voice") }, status = ui.error, busy = ui.busy) {
                        Eyebrow("选择目的地")
                        Heading("输入您想去的地方")
                        OutlinedTextField(query, { query = it.take(100) }, label = { Text("输入目的地") },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 24.sp), modifier = Modifier.fillMaxWidth(),
                            minLines = 2, enabled = !ui.busy)
                        Text("可以加上城市、院区或附近街道。", color = MetroMuted)
                        if (vm.container.demo) DemoNotice()
                    }
                    "destination" -> StepScreen(vm::back, if (ui.searchOrigin == "voice") "重新说" else "重新输入",
                        { vm.page(ui.searchOrigin) }, secondary = "输入目的地", onSecondary = { vm.page("input") },
                        status = ui.error, busy = ui.busy) {
                        Eyebrow("选择目的地")
                        Heading("从下方选择目的地")
                        ui.candidates.forEach { place ->
                            Card(onClick = { vm.choose(place) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MetroWarning)) {
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(place.name, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = MetroBrand)
                                    Text("${place.city} · ${place.district}", fontSize = 18.sp)
                                    Text(place.address.ifBlank { "地址暂缺，请进一步核对" }, fontSize = 18.sp)
                                }
                            }
                        }
                    }
                    "confirm" -> ui.selectedPlace?.let { place ->
                        StepScreen(vm::back, if (ui.addingFavorite) "确认添加" else "就是这里", { vm.confirm(place) }, !ui.busy,
                            "不是这里，重新选择", { vm.page(ui.searchOrigin) }, status = ui.error, busy = ui.busy) {
                            Eyebrow("确认目的地")
                            Heading("您要去的是这里吗？")
                            InfoPanel("${place.city} · ${place.district}", place.name, place.address.ifBlank { "地址暂缺，请核对名称和区域" })
                            PlaceMap(place)
                        }
                    } ?: StepScreen(vm::back) { Text("请先选择地点") }
                    "route" -> ui.route?.let { route ->
                        StepScreen(vm::back, "开始出发", vm::start, !ui.busy,
                            "打开高德，选择打车 ↗", { vm.taxi(route) }, status = ui.error, busy = ui.busy) {
                            Eyebrow("去 ${route.destination.name}")
                            Heading("预计 ${(route.durationSeconds + 59) / 60} 分钟")
                            InfoPanel("地铁 ${(route.metroDurationSeconds + 59) / 60} 分钟 · " +
                                (route.walkDurationSeconds?.let { "步行 ${(it + 59) / 60} 分钟" } ?: "步行时间暂缺"),
                                "换乘 ${route.transfers} 次", route.segments.joinToString(" → ") { it.line })
                            Text("包含步行、候车和乘车时间", fontWeight = FontWeight.Bold)
                            Text(route.segments.first().boarding.name + " → " + route.segments.joinToString(" → ") { it.alighting.name }, color = MetroMuted)
                            Walking(route.totalWalk)
                            Text("打车将在外部应用高德地图中完成。", fontSize = 16.sp, color = MetroMuted)
                            if (route.demo) DemoNotice()
                        }
                    } ?: StepScreen(vm::back, "重新选择目的地", { vm.page("home") }, status = ui.error) { Heading("请重新查询路线") }
                    "noMetro" -> StepScreen(vm::back, "打开高德，选择打车 ↗", { ui.selectedPlace?.let(vm::taxiPlace) },
                        secondary = "查看步行距离", onSecondary = { ui.selectedPlace?.let { vm.walkTo(it, "noMetro") } }, status = ui.error) {
                        Eyebrow("未查到可用地铁方案")
                        Heading("换一种方式出行")
                        InfoPanel("您要去的地方", ui.selectedPlace?.name.orEmpty(), "没有地铁方案，不代表适合步行。")
                        Text("高德属于外部应用。请在高德中核对上车点和费用，再自行确认叫车。")
                    }
                    "walk" -> ui.walkTarget?.let { target ->
                        StepScreen(vm::back, "打开高德步行导航 ↗", { vm.navigateWalking(target) }, !target.demo,
                            "返回上一页", vm::back, status = ui.error, busy = ui.busy) {
                            Eyebrow("步行路线")
                            Heading("前往 ${target.name}")
                            ui.walkReason?.let { Text(it) }
                            ui.walking?.let { walk ->
                                Text("约 ${(walk.seconds + 59) / 60} 分钟 · ${walk.meters} 米")
                                Walking(walk.meters)
                                if (!target.demo) WalkingMap(walk) else DemoNotice()
                            }
                            Text("高德是外部应用，打开后请跟随其语音导航。返回助手不会结束行程。")
                            TextButton(onClick = vm::refreshWalking, enabled = !ui.busy) { Text("刷新当前位置与路线") }
                        }
                    }
                    "trip" -> {
                        val segment = trip.segment
                        val primary = when (trip.phase) {
                            TripPhase.WALKING_TO_STATION -> "我已到站"
                            TripPhase.WAITING_FOR_BOARD_CONFIRMATION -> "我已上车"
                            TripPhase.RIDING, TripPhase.APPROACHING_ALIGHT_STATION -> "我已下车"
                            TripPhase.TRANSFER_WALKING -> "我到换乘站台了"
                            TripPhase.WALKING_TO_DESTINATION -> "我已到达"
                            else -> "回到首页"
                        }
                        val secondary = when (trip.phase) {
                            TripPhase.WALKING_TO_STATION, TripPhase.WALKING_TO_DESTINATION -> "打开高德步行导航 ↗"
                            TripPhase.RIDING, TripPhase.APPROACHING_ALIGHT_STATION -> "点错了，我还没上车"
                            else -> "向 AI 问路"
                        }
                        StepScreen(vm::back, primary, {
                            when (trip.phase) {
                                TripPhase.WAITING_FOR_BOARD_CONFIRMATION -> board()
                                TripPhase.FINISHED, TripPhase.IDLE -> vm.page("home")
                                else -> vm.advance()
                            }
                        }, secondary = secondary, onSecondary = {
                            when (trip.phase) {
                                TripPhase.WALKING_TO_STATION -> trip.route?.let { vm.walkToStation(it, "trip") }
                                TripPhase.WALKING_TO_DESTINATION -> trip.route?.destination?.let { vm.walkTo(it, "trip") }
                                TripPhase.RIDING, TripPhase.APPROACHING_ALIGHT_STATION -> undoBoard = true
                                else -> vm.page("voice")
                            }
                        }, status = ui.error, footerNote = if (trip.phase == TripPhase.FINISHED) "本次行程已完成" else "返回不结束行程") {
                            trip.restoreMessage?.let { Notice(it, MaterialTheme.colorScheme.error) }
                            if (segment != null) when (trip.phase) {
                                TripPhase.WALKING_TO_STATION -> {
                                    Eyebrow("路程 1 · 去地铁站")
                                    Heading("前往 ${segment.boarding.name}")
                                    InfoPanel("请核对入口", segment.entrance ?: "按站内标识进站", "到站后点击“我已到站”")
                                    Text("步行导航由外部应用高德提供。", color = MetroMuted)
                                }
                                TripPhase.WAITING_FOR_BOARD_CONFIRMATION -> {
                                    Eyebrow("候车 · 第 ${trip.segmentIndex + 1} 段")
                                    Heading("乘坐 ${segment.line}")
                                    InfoPanel("往这个方向", segment.direction ?: "请核对站台标识", "上车后再点击“我已上车”")
                                    Text("在 ${segment.alighting.name} 下车")
                                }
                                TripPhase.RIDING, TripPhase.APPROACHING_ALIGHT_STATION -> {
                                    Eyebrow("乘车 · ${segment.line}")
                                    Heading("在这里下车")
                                    InfoPanel("下车站", segment.alighting.name, trip.route?.segments?.getOrNull(trip.segmentIndex + 1)?.let { "下车后换乘 ${it.line}" } ?: "下车后出站去目的地")
                                    Text(if (trip.reminderEnabled) "● 到站提醒已开启" else "到站提醒已关闭，请自行留意报站", color = MetroBrand)
                                    Text("提醒为时间估计，请留意车厢报站。", color = MetroMuted)
                                    if (trip.reminderEnabled) TextButton(onClick = vm::disableReminder) { Text("关闭本段到站提醒") }
                                    if (trip.route?.demo == true && trip.phase == TripPhase.RIDING && trip.reminderEnabled)
                                        TextButton(onClick = vm::simulate) { Text("演示：触发下车提醒") }
                                }
                                TripPhase.TRANSFER_WALKING -> {
                                    val next = trip.route?.segments?.getOrNull(trip.segmentIndex + 1)
                                    Eyebrow("已在 ${segment.alighting.name} 下车")
                                    Heading("接下来换乘")
                                    InfoPanel("跟着站内标识走", next?.line.orEmpty(), next?.direction?.let { "往 $it 方向" } ?: "请核对站台方向")
                                    Text("换乘在站内完成，请勿跟随室外步行路线。")
                                }
                                TripPhase.WALKING_TO_DESTINATION -> {
                                    Eyebrow("出站后去目的地")
                                    Heading(trip.route?.destination?.name.orEmpty())
                                    InfoPanel(segment.alighting.name, segment.exit ?: "按站内出口标识出站", "出站后打开高德步行导航")
                                    Text("高德属于外部应用。到达后回到这里确认。")
                                }
                                TripPhase.FINISHED -> {
                                    Eyebrow("这次行程已完成")
                                    Heading("您已到达")
                                    InfoPanel("目的地", trip.route?.destination?.name.orEmpty(), "✓ 行程与提醒已结束")
                                }
                                else -> Heading("当前没有进行中的行程")
                            } else Heading("当前没有进行中的行程")
                            if (trip.route?.demo == true) DemoNotice()
                            if (trip.phase !in listOf(TripPhase.FINISHED, TripPhase.IDLE))
                                TextButton(onClick = { endTrip = true }) { Text("结束行程", color = MetroMuted) }
                        }
                    }
                    "settings" -> StepScreen(vm::back, "保存完成，返回首页", { vm.page("home") }, status = ui.error) {
                        Heading("家属设置")
                        TextButton(onClick = { keyDialog = true }) { Text(if (chat.keyConfigured) "替换 DeepSeek API Key" else "设置 DeepSeek API Key", fontSize = 22.sp) }
                        Text(if (chat.keyConfigured) "AI 对话：已配置 Key" else "AI 对话：尚未配置 Key", color = MetroMuted)
                        Text("对话回复会显示并朗读。Key 仅加密保存在本机。", fontSize = 18.sp)
                        HorizontalDivider()
                        Text("常用地点（最多 5 个）", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        SearchBox(query, { query = it }, !ui.busy) { vm.search(query, true) }
                        local.favorites.filter { it.place.demo == vm.container.demo }.forEach { FavoriteEditor(it, vm) }
                        HorizontalDivider()
                        Text(vm.container.modeReason)
                        if (vm.container.realSearchAvailable) TextButton(onClick = vm::retryLive) { Text("启用真实地点查询") }
                        if (!vm.container.demo) TextButton(onClick = { locate() }) { Text("同意高德查询并开启定位权限") }
                        TextButton(onClick = {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }) { Text("打开手机通知设置") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(local.speechEnabled, vm::speech)
                            Text("到站提醒语音播报", modifier = Modifier.weight(1f))
                        }
                        Text("此开关只控制到站提醒朗读，不影响语音对话。通话、其他音频或离开应用时停止朗读。", fontSize = 18.sp)
                        Text("不保存录音和对话历史。地点、最近记录与行程保存在本机；查询地点和路线会发送必要信息给高德。", fontSize = 18.sp)
                    }
                }
            }
        }
        if (keyDialog) ApiKeyDialog(chat, conversation::saveKey, conversation::clearKey, { keyDialog = false })
        if (trip.phase == TripPhase.APPROACHING_ALIGHT_STATION && trip.reminderEnabled && !reminderDismissed)
            AlertDialog(onDismissRequest = { reminderDismissed = true }, title = { Text("快到站了") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(trip.segment?.alighting?.name.orEmpty(), fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, color = MetroBrand)
                    Text("这是时间估计，请听清车厢报站后再下车。关闭提示后仍保持乘车状态。")
                } },
                confirmButton = { TextButton(onClick = { reminderDismissed = true; vm.advance() }) { Text("我已下车") } },
                dismissButton = { TextButton(onClick = { reminderDismissed = true }) { Text("知道了，关闭提示") } })
        if (manualBoard) AlertDialog(onDismissRequest = { manualBoard = false }, title = { Text("到站提醒未开启") },
            text = { Text("可继续查看行程，请自行留意车厢报站。") },
            confirmButton = { TextButton(onClick = { manualBoard = false; vm.board(false) }) { Text("继续乘车") } },
            dismissButton = { TextButton(onClick = { manualBoard = false }) { Text("返回") } })
        if (endTrip) AlertDialog(onDismissRequest = { endTrip = false }, title = { Text("结束这次行程吗？") },
            text = { Text("结束后将关闭到站提醒。只想返回上一页，请选择继续行程。") },
            confirmButton = { TextButton(onClick = { endTrip = false; vm.finish() }) { Text("确认结束") } },
            dismissButton = { TextButton(onClick = { endTrip = false }) { Text("继续行程") } })
        if (undoBoard) AlertDialog(onDismissRequest = { undoBoard = false }, title = { Text("您还没有上车吗？") },
            text = { Text("确认后回到候车页面，只取消本段提醒，保留目的地和路线。") },
            confirmButton = { TextButton(onClick = { undoBoard = false; vm.undoBoard() }) { Text("我还没上车") } },
            dismissButton = { TextButton(onClick = { undoBoard = false }) { Text("已经上车") } })
    }
}

@Composable fun StepScreen(
    back: (() -> Unit)? = null,
    primary: String? = null,
    onPrimary: () -> Unit = {},
    enabled: Boolean = true,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    settings: (() -> Unit)? = null,
    status: String? = null,
    busy: Boolean = false,
    footerNote: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize().background(MetroBackground), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (back != null) TextButton(onClick = back, modifier = Modifier.heightIn(min = 56.dp)) { Text("‹ 返回", fontSize = 22.sp, color = MetroInk, fontWeight = FontWeight.Bold) }
                else Text("安心出行", modifier = Modifier.padding(start = 12.dp), fontSize = 20.sp, color = MetroMuted, fontWeight = FontWeight.Bold)
                if (settings != null) TextButton(onClick = settings) { Text("设置", fontSize = 20.sp) }
                else Text("安心出行", fontSize = 18.sp, color = MetroMuted, modifier = Modifier.padding(end = 12.dp))
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Notice(it, MaterialTheme.colorScheme.error) }
                content()
            }
            if (primary != null || secondary != null) Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                primary?.let { Action(it, enabled, onPrimary) }
                secondary?.let { TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(it, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                } }
                footerNote?.let { Text(it, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 16.sp, color = MetroMuted) }
            }
        }
    }
}
@Composable fun Heading(text: String) { Text(text, fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, color = MetroInk) }
@Composable fun Eyebrow(text: String) { Text(text, fontSize = 18.sp, lineHeight = 26.sp, color = MetroMuted, fontWeight = FontWeight.Medium) }
@Composable fun InfoPanel(label: String, title: String, detail: String? = null, large: Boolean = true) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MetroCard)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, fontSize = 20.sp, lineHeight = 28.sp, color = MetroBrand)
            Text(title, modifier = Modifier.fillMaxWidth(), fontSize = if (large) 40.sp else 28.sp,
                lineHeight = if (large) 54.sp else 40.sp, fontWeight = FontWeight.Bold, color = MetroBrand, textAlign = TextAlign.Start)
            detail?.let { Text(it, fontSize = 20.sp, lineHeight = 30.sp) }
        }
    }
}
@Composable fun DestinationTile(label: String, color: Color, enabled: Boolean, click: () -> Unit) {
    Card(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
        shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = color)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(horizontal = 24.dp, vertical = 20.dp), contentAlignment = Alignment.CenterStart) {
            Text(label, fontSize = 36.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, color = MetroBrand)
        }
    }
}
@Composable fun Action(label: String, enabled: Boolean = true, click: () -> Unit) {
    Button(onClick = click, enabled = enabled, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        Text(label, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}
@Composable fun Notice(text: String, color: Color) { Text(text, color = color, fontSize = 18.sp, lineHeight = 28.sp) }
@Composable fun DemoNotice() { Text("演示数据 · 不可用于实际出行", fontSize = 16.sp, color = Color(0xFF78431C)) }
@Composable fun Walking(meters: Int) { walkingWarning(meters)?.let { Notice(it, Color(0xFF78431C)) } }
@Composable fun SearchBox(query: String, change: (String) -> Unit, enabled: Boolean, search: () -> Unit) {
    OutlinedTextField(query, change, label = { Text("输入目的地") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = enabled)
    OutlinedButton(onClick = search, enabled = enabled && query.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("查找地点", fontSize = 20.sp) }
}
@Composable fun FavoriteEditor(f: FavoriteDestination, vm: AppViewModel) {
    var name by remember(f.place.identity, f.displayName) { mutableStateOf(f.displayName) }
    OutlinedTextField(name, { name = it }, label = { Text(f.place.name) }, modifier = Modifier.fillMaxWidth())
    Row { TextButton(onClick = { vm.renameFavorite(f.place.identity, name) }) { Text("保存名称") }; TextButton(onClick = { vm.removeFavorite(f.place.identity) }) { Text("移除") } }
}
