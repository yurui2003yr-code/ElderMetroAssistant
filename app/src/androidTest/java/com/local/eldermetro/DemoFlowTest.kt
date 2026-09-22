package com.local.eldermetro

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.local.eldermetro.app.ElderMetroApplication
import com.local.eldermetro.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

class DemoFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as ElderMetroApplication).container.store
    @Before fun reset() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant com.local.eldermetro android.permission.POST_NOTIFICATIONS").close()
        runBlocking { store.change { LocalData(favorites = DemoData.favorites, initialized = true) } }
        compose.waitForIdle()
    }
    @After fun clearTrip() {
        runBlocking { store.updateTrip { TripState() } }
        compose.activity.stopService(android.content.Intent(compose.activity, com.local.eldermetro.service.TripTrackingService::class.java))
    }
    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        try { node.performScrollTo() } catch (_: AssertionError) { /* fixed footer is outside scrolling content */ }
        node.performClick(); compose.waitForIdle()
    }
    private fun waitText(text: String) { compose.waitUntil(10000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun route() { click("市人民医院"); waitText("您要去的是这里吗？"); click("就是这里"); waitText("开始出发") }

    @Test fun homeAndRouteFollowFigmaHierarchy() {
        compose.onNodeWithText("女儿家").assertExists()
        route()
        compose.onNodeWithText("预计 48 分钟").assertExists()
        compose.onNodeWithText("换乘 1 次").assertExists()
        compose.onNodeWithText("手动翻页", substring = true).assertDoesNotExist()
    }
    @Test fun backAndWalkingDoNotEndTrip() {
        route(); click("开始出发"); waitText("我已到站")
        click("打开高德步行导航 ↗"); waitText("步行路线")
        compose.onNodeWithText("下一步").assertDoesNotExist()
        click("‹ 返回"); waitText("我已到站")
        click("‹ 返回"); waitText("继续当前行程")
        click("继续当前行程"); waitText("我已到站")
    }
    @Test fun candidatesRequireExplicitConfirmation() {
        click("输入目的地")
        compose.onNode(hasSetTextAction()).performTextInput("人民公园")
        click("查找地点"); waitText("从下方选择目的地")
        compose.onNodeWithText("开始出发").assertDoesNotExist()
        compose.onAllNodesWithText("人民公园")[0].performClick()
        waitText("您要去的是这里吗？")
        compose.onNodeWithText("就是这里").assertExists()
    }
    @Test fun dismissReminderStillRidingAndTransferRequiresBoarding() {
        route(); click("开始出发"); click("我已到站"); click("我已上车")
        waitText("演示：触发下车提醒"); click("演示：触发下车提醒"); waitText("快到站了")
        click("知道了，关闭提示")
        runBlocking { store.updateTrip { assertEquals(TripPhase.APPROACHING_ALIGHT_STATION, it.phase); it } }
        click("我已下车"); waitText("我到换乘站台了"); click("我到换乘站台了"); waitText("我已上车")
        runBlocking { store.updateTrip { assertEquals(TripPhase.WAITING_FOR_BOARD_CONFIRMATION, it.phase); assertFalse(it.reminderEnabled); it } }
    }
    @Test fun disablingReminderDoesNotConfirmAlighting() {
        route(); click("开始出发"); click("我已到站"); click("我已上车"); waitText("关闭本段到站提醒")
        click("关闭本段到站提醒")
        runBlocking { store.updateTrip { assertEquals(TripPhase.RIDING, it.phase); assertFalse(it.reminderEnabled); it } }
        compose.onNodeWithText("我已下车").assertExists()
    }
    @Test fun noMetroOffersChoiceInsteadOfStartingLongWalk() {
        click("输入目的地"); compose.onNode(hasSetTextAction()).performTextInput("社区中心")
        click("查找地点"); waitText("从下方选择目的地"); click("社区中心"); click("就是这里")
        waitText("未查到可用地铁方案")
        compose.onNodeWithText("查看步行距离").assertExists()
        compose.onNodeWithText("下一步").assertDoesNotExist()
    }
    @Test fun voicePageRetainsTextFallbackAndSettingsCanReplaceKey() {
        click("说出目的地"); waitText("请说出您想去的地方")
        compose.onNodeWithText("直接输入目的地").assertExists()
        click("‹ 返回"); click("设置")
        val label = if (compose.onAllNodesWithText("替换 DeepSeek API Key").fetchSemanticsNodes().isNotEmpty()) "替换 DeepSeek API Key" else "设置 DeepSeek API Key"
        click(label); waitText("DeepSeek API Key")
        compose.onNodeWithText("显示 Key").assertExists()
        click("关闭")
    }
}
