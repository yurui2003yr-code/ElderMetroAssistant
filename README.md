# 地铁出行助手 · ElderMetroAssistant

当前版本：0.2.0。新增无地铁时的步行方案、进出站步行地图、大字逐步指引及高德实时步行导航入口；详见 `docs/WALKING_013.md`。应用内地图为可刷新的静态路线图，实时导航交给高德客户端。下车站继续大字显示；保留通话/其他应用兼容性防护，实际通话仍需老人手机验收。

家庭侧载 Android 应用，最低 Android 8.0 / API 26。0.1.2家庭交付包已配置用户提供的高德 Web 服务 Key，使用真实查询。源码不带 Key，默认构建为 **Demo 演示模式**；演示城市、地点、线路不用于实际出行。无账号、广告、云同步或桌面替换功能。

## 已实现的顺序子任务

1. Kotlin / Compose / Material 3 工程、页面导航、独立 JVM 领域模块和 Gradle Wrapper。
2. 语音及文字输入、最多三个候选地点确认、固定地点设置、最多两个不同最近地点、唯一路线筛选、换乘行程和模拟提醒。
3. 高德 Web API 地点搜索、Android 新鲜定位与高德坐标转换、当前城市、公交地铁解析、完整站序核对方向。缺 Key 自动使用 Demo；真实查询异常时清除旧路线并回到带提示的演示首页。
4. DataStore 状态保存、前台服务、通知和震动、备用 WorkManager、同次开机的进程恢复、提醒原子去重。
5. 高德官方 HTTPS 路线链接降级；用户自行选择打车、价格及支付。未使用私有打车 URI。
6. 自动构建、模拟器验收及公共地标真实接口测试；老人手机定位、实际线路和通话验收仍需完成，见 `docs/ACCEPTANCE.md`。

## 安装与演示

使用交付目录中的 `ElderMetroAssistant-debug.apk`。把 APK 发到 Android 手机，打开文件并允许该文件来源安装应用。该包为 Debug 签名，不适合公开商店发布。

也可以使用 Android SDK 的 ADB：

```powershell
adb install -r ElderMetroAssistant-debug.apk
adb shell am start -n com.local.eldermetro/.MainActivity
```

演示流程：市人民医院 → 核对名称及地址 → 是，去这里 → 开始行程 → 我已到站 → 我已经上车 → 允许通知 → 演示：触发下车提醒 → 我知道了 → 到下一条线站台 → 再次确认上车 → 再次提醒 → 出站步行 → 我已到达。

文字输入“人民公园”会返回两个示例候选，必须自行确认。搜索“社区中心”可演示最近地点更新。固定地点不会出现在最近区域。系统没有语音识别服务时显示提示，文字输入照常使用；应用不持有录音权限，也不保存录音。

## 构建

已验证组合：JDK 17、Android SDK 36、Build Tools 35.0.0、AGP 8.11.1、Gradle 8.13、Kotlin 2.1.20。依赖版本已固定。使用 Android Studio 打开工程根目录，配置本机 SDK；或设定 `JAVA_HOME` 和 `ANDROID_HOME` 后执行：

```powershell
.\gradlew.bat test lint assembleDebug
.\gradlew.bat connectedDebugAndroidTest # 已连接设备时
```

当前工作区可用 `powershell -ExecutionPolicy Bypass -File .\build-local.ps1`，自动使用本次准备的相邻 `.tools` 目录。工具不随源码压缩包分发。

APK 默认位于 `app/build/outputs/apk/debug/app-debug.apk`。单元测试报告位于 `domain/build/reports/tests/test/index.html`，Lint 报告位于 `app/build/reports/lint-results-debug.html`，设备测试报告位于 `app/build/reports/androidTests/connected/debug/index.html`。

## 配置高德 Key

本项目使用 **Web 服务 API Key**，不是 Android 地图 SDK Key。需要开通地点搜索、路径规划、坐标转换、逆地理编码；方向补全还依赖公交线路 ID 查询权限。API 配额不足或字段不完整会给出失败或省略方向。

在工程根目录自行创建 `secrets.properties`：

```properties
AMAP_WEB_KEY=填入自己的Web服务Key
```

然后执行：

```powershell
.\gradlew.bat assembleDebug -PROUTE_PROVIDER=amap
```

Key 为空仍进入 Demo。要恢复演示，使用 `-PROUTE_PROVIDER=demo` 重建。真实模式首次使用，在首页阅读数据说明，点击“同意并开启定位”，允许手机定位。城市从定位逆地理编码取得，不预设家庭所在城市。为保证站点精度，定位样本须在60秒以内且误差不超过200米；获取不到则不使用旧定位。查询失败进入演示后，修复网络或定位设置，可点击“重试真实地点搜索”。

`secrets.properties`、`local.properties`、签名材料、交付目录与构建目录已列入忽略规则。**Key 会随 BuildConfig 进入真实查询 APK，APK 可被反编译**；含 Key 的包仅在家庭内分发。源码压缩包不含本地 Key；Demo 构建也不会嵌入 Key。

## 常用地点设置

进入“设置”，搜索地点，逐项核对城市、区域、地址，确认添加。最多五个，可改显示名称或移除。实际家庭地址只在用户手机上设置，无需写入源码。Demo 和真实地点分开显示；切换构建模式不会把演示地址拿来真实规划。

最近地点只在成功生成有效路线后记录，以地点 ID 去重；无 ID 时以标准化名称及坐标去重。常用地点排除后最多保留两项。数据保存在应用私有 DataStore，关闭云备份和设备迁移，卸载会清除本机数据。

## 通知和后台设置

上车确认时申请 Android 13+ 通知权限。请在“设置 → 打开手机通知设置”允许通知，并保留“准备下车提醒”渠道的高重要性、震动和锁屏显示。0.1.1 使用新的默认无声渠道，不绕过勿扰；检测到通话时静默显示。拒绝时可继续手动看行程，但不会宣称提醒启用。权限后续被关闭时，服务也会停用提醒。通知中的“结束行程”可停止服务及备用提醒。

有定位权限时使用 location 前台服务；无定位权限时 Android 14+ 使用 specialUse 类型执行用户主动开启的计时任务。每次换乘都重新确认上车。后台部分唤醒锁有6小时上限，投递提醒或结束行程会释放。提醒后不会持续循环等待确认；可选前台播报最多10秒后停止服务。备用 WorkManager 不是精确闹钟，可能被省电策略延迟。

请家属在手机系统中允许本应用后台运行。系统“强行停止”、重启、厂商省电限制可能阻止提醒。重启后原有开机计时失效，应用会提示核对所在站点，不能在列车途中把当前时刻当作原上车时间重新计时。

## 路线及提醒规则

路线必须包含完整地铁段。先按最快方案的1.5倍和额外30分钟双重上限过滤；在最少步行的200米范围内比较时间，时间接近定义为120秒，再优先少换乘。以全局最小值为锚点，排序不随输入顺序改变。

首版保守剔除包含普通公交、火车等无法完整展示的混合交通方案，而不会隐藏非地铁路段。步行距离保留在领域模型中用于选路和步行过多提示，但不在老人页面展示分段米数。解析的分段总和必须与官方总步行距离基本一致。无出口或无可靠方向时明确省略，不猜测。

0.1.2 地铁耗时为各地铁段预计时长之和；走路耗时为接口提供的完整步行段时长之和，任一段缺失或与总耗时矛盾时显示“走路时间暂缺”，不把候车时间当作走路时间。全程可能包含候车等额外时间。Demo医院路线为全程48分钟、地铁24分钟、走路14分钟，均为演示数据。

0.1.2家庭交付使用真实查询；此前“找不到地点”的主要原因是安装了仅含固定示例的 Demo 包。Demo 首页和空结果现在均说明这一限制。手机应用通过高德 HTTPS API直接查询，不需要 MCP。

每段计时以“我已经上车”为起点。无逐站时间时，平均每站按90–240秒约束，扣除最后一站和30秒安全量；一站行程立即提醒。真实线路附近定位仅可提前触发。路线超过5分钟不能启动，起点明显改变须重新规划，断网时不继续展示为可用的旧路线。

**时间估计不能保证真正还剩一站，也不能保证早于实际到站。** 快车、停站、拥堵、上车确认时机和系统调度都会影响结果。界面始终要求核对车厢报站；在老人实际线路陪同验证前，不能将此首版作为独立可靠的下车依据。

## 架构与实现边界

`domain` 为纯 Kotlin 模型、Repository 接口、UseCase、Demo Provider 和官方字段解析器；`app` 为 Compose UI、AppViewModel、DataStore、高德适配器、服务、通知、语音和叫车适配器。首版以一个 AppViewModel 协调页面，业务算法位于领域模块。UI 不接触高德响应对象。

通知去重先在 DataStore 原子认领、再投递系统通知，避免服务与备用任务重复震动。若恰好在认领后、系统通知投递前进程崩溃，该次系统通知可能遗漏；恢复后的应用内提醒仍可显示。这不是具备实时位置保证的专业导航产品。

## 官方接口依据

- [高德公交路径规划](https://lbs.amap.com/api/webservice/guide/api/direction)
- [高德地点搜索](https://lbs.amap.com/api/webservice/guide/api-advanced/search)
- [高德坐标转换](https://developer.amap.com/api/webservice/guide/api/convert)
- [高德公交线路 ID 查询](https://lbs.amap.com/api/webservice/guide/api-advanced/bus-inquiry)
- [高德官方路线 URI](https://lbs.amap.com/api/uri-api/guide/travel/route)
- [Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)

来源核对日期：2026-09-20。2026-09-21使用公共地标进行真实接口测试，并修复空铁路占位字段与跨接口站点编号差异。单元测试样本为虚构数据；测试不使用家庭地址。
