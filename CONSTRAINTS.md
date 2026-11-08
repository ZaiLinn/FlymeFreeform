# FlymeFreeform 实现说明

版本 1.3.6 / versionCode 2026110717　包名 `io.github.zailinn.flymefreeform`　产物 `out/FlymeFreeform-v1.3.6.apk`
源码 `ZaiLinn/FlymeFreeform`（维护者 ZaiLinn；fork 自 `Hoerire/FlymeFreeform` ← `Mangi-11/FlymeFreeform`，致谢见 README）
工作副本 `~/Documents/Code/FlymeFreeform`（macOS），临时文件 `tmp/`；构建、签名与发布见第六节。
包名于 2026-11-08 由 `io.github.hoerire.flymefreeform` 改为现名，设备上的旧包名模块需卸载后再装新包。

本文只讲「代码怎么实现的」与「改动时不能碰的边界」。功能说明见 README。

---

## 〇、开工闸门（新会话第一条，先做再说话）

交接文档教的是「照做，不要跳」，本条优先于它。**没走完闸门之前不动设备、不重装、不重启。**

**铁律 0（最高优先级，用户 2026-11-08 两次重申）：动手之前先回答。**
用户提问时先给结论；没有用户明确说「做 / 继续 / 装 / 发 / 构建」，就不构建、不装包、不重启、不推送。
“顺手先做”不算授权。一次只验一个变量，一个改动一个包。

1. 读本文件第七节（日志）、第九节（改动守则）、第十节（当前状态），一句话复述给用户
2. 核设备当前包：`dumpsys package io.github.zailinn.flymefreeform | grep codePath` → `sha256sum <codePath>/base.apk`，与第十节对照，不认版本号
3. 取日志现状：`grep -aE 'MODULE_CONFIG_READY|COORDINATOR_READY|SYSTEM_CATALOG_|PANEL_DATA_HOOKS' 最新 modules log`，先看现在到底是哪版在跑
4. 要重启 → 一律走 `sh restart-gate.sh --changed … --pkg … --verify … --logs … -- "<命令>"`，
   四段缺一项脚本就拒绝执行；`--pkg` 写设备上 APK 的 sha256 前缀，与设备实际安装的不符也拒绝；
   声明落 `tmp/restart-declarations.log`。例：
   `sh restart-gate.sh --changed "…" --pkg beec312c --verify "…" --logs "COORDINATOR_READY|SYSTEM_CATALOG_" -- "adb shell \"su -c 'setsid killall system_server'\""`
5. 收工：回写第十节。**每次改代码、装包、重启后都要更新本文件**（用户 2026-11-08 要求）：
   第十节的设备包与 sha256、待验证项、下一步，以及受影响的实现说明、参数、日志码

> 为什么写死成脚本：上下文压缩会丢掉「承诺过不做的事」，只留「现在做到哪了」。
> 纪律必须能从磁盘读出来并拦得住动作，不能靠新会话的自觉。

## 一、进程分工

模块按进程名分派到不同安装器，改代码前先确认目标代码跑在哪个进程。

| 进程 | 安装器 | 负责 |
|---|---|---|
| system_server | `SystemServerHookInstaller` | 启动判定与参数注入、窗口数量上限与应用准入、窗外关闭、快速上滑、扇形协调器 |
| com.android.systemui | `SystemUiHookInstaller` | 底角触摸监听、通知点击转场 |
| com.android.launcher | `LauncherHookInstaller` | 桌面侧底角触摸监听 |
| com.coloros.smartsidebar:ui | `SidebarHookInstaller` | 「更多」面板接管、面板工具小窗注入 |

跨进程通信只有两条路：侧边栏走 Binder（`SidebarProtocol`），模块应用与框架走 SharedPreferences 与广播。**没有静态字段共享**。

## 二、目录

```
hook/                       各进程安装器与运行环境
  launch/ActivityStarterZoomInjector.kt   读取快照与执行判定结果（343 行）
  launch/decision/                      纯 Kotlin 启动判定、时间窗与规则
  launch/StarterLaunchEnvironment.kt    惰性平台查询适配
  launch/StarterSideEffects.kt           广播、面板、关窗重开的统一投递出口
  launch/StarterLockProbe.kt             WM 锁探针与违规节流
  launch/StarterQueryCache.kt            PM 查询异步缓存
  launch/ZoomLaunchSpec.kt                小窗参数契约
  launch/InterAppLaunchTracker.kt         跳转关系记录，识别返回
  launch/FlexibleWindowLimitHook.kt       数量上限与应用准入
  launch/NotificationZoomSourceHook.kt    通知来源标记
platform/coloros/           ColorOS 私有接口封装，全部反射
  ColorOsFreeformCoordinator.kt           system_server 内扇形总协调
  ColorOsAllApps*.kt                      「更多」面板窗口与内容（侧边栏进程）
  PanelToolLaunchBridge.kt                面板工具小窗参数注入（侧边栏进程）
  FavoriteGroupInjector.kt                面板数据原地改写、分组标题角色登记
  PanelGroupLabels.kt                     分组标题文案（收藏/最近，随系统语言）
  ColorOsAppCatalog.kt                    扇形与「更多」的条目目录（system_server 后台线程）
gesture/                    纯逻辑：手势状态机、命中计算、多圆弧容量（RadialArcPlan）
window/                     浮层自绘与动画
ui/                         Compose + Miuix 设置界面
config/ apps/ framework/ log/
```

按功能定位：

| 要改什么 | 去哪里 |
|---|---|
| 扇形呼出、命中半径 | `gesture/CornerGestureEngine.kt`、`RadialGeometry.kt` |
| 多圆弧容量与间距 | `gesture/RadialArcPlan.kt` |
| 扇形放哪些应用、补位、图标加载 | `platform/coloros/ColorOsAppCatalog.kt`、`apps/AppSelectionPolicy.kt` |
| 扇形应用页（按圈分页） | `ui/PinnedAppsScreen.kt` |
| 扇形浮层 | `window/CornerRadialOverlayView.kt` |
| 角区触摸窗口 | `hook/SystemUiCornerInputMonitor.kt` |
| 「更多」面板 | `platform/coloros/ColorOsAllApps*.kt` |
| 面板工具小窗 | `PanelToolLaunchBridge.kt` + `SidebarHookInstaller` |
| 通知点击 | `hook/NotifBundleHook.kt` |
| 各类启动判定 | `hook/launch/ActivityStarterZoomInjector.kt` |
| 数量上限与准入 | `hook/launch/FlexibleWindowLimitHook.kt` |
| 小窗关闭（窗外单击 / 双击、双击横条） | `hook/OutsideTapCloseHookInstaller.kt` |
| 快速上滑 | `hook/HandleSwipeUpHookInstaller.kt` |
| 暂停条件 | `hook/ModuleEnvironmentState.kt` |
| 设置界面 | `ui/` 下对应 Screen |

## 三、各功能怎么实现的

**starter 锁安全**　副作用经 StarterSideEffects 离开 starter 线程；面板即时收起改为主线程队首，延迟收起仍为 60 ms。StarterLockProbe 从 mService.mGlobalLock 读取弱引用，执行时以 Thread.holdsLock 检测；未知只报一次，违规同名每 10 秒最多一次，不抛异常。同步 zoom 启动与窗口状态查询为既有厂商白名单。PM 与厂商控制中心来源查询改为异步缓存（含包变化失效），冷缓存按未知放行；锁屏只读 ModuleEnvironmentState 的广播缓存，不在钩子里刷新厂商 Keyguard 实现。


**扇形菜单**　system_server 内的协调器监听底角指针，手势逻辑在 `CornerGestureEngine`（纯状态机，可单测），
命中计算在 `RadialGeometry`；浮层由 `CornerRadialOverlayView` 自绘，窗口类型 `TYPE_INPUT_METHOD_DIALOG`(141000)，
压在输入法与键盘之上，回落 `TYPE_APPLICATION_OVERLAY`(111000)。松手当帧提交启动、复制原生 zoom 参数、
侧边栏连接常驻——三处缺一都会明显变慢。

**多圆弧**　第 1 条是默认圆弧（快捷项 +「更多」），内侧圆弧依次向内收一个圈距，容量 5/4/3 且不超过外侧一条减 1
（`RadialArcPlan.innerCapacities`）。`RadialGeometry.layout` 的 itemCenters 顺序为「默认圆弧快捷项 → 内侧各圈（由外向内）→ 更多」。
各圈实际条数以目录给出的 `AppCatalogSnapshot.radialArcCounts` 为准，自定义圈放不满时空位不会挤进下一圈。
命中只按「离哪个图标圆心最近且在命中范围内」；有内侧圆弧时另设角落空白区 = 最内一圈半径 − 一个圈距
（2/3/4 条约 128/70/52 基准 dp），不能用「图标内沿」做边界，否则两圈时第二圈只剩约 50 宽、很难选中。
图标大小外大内小（`RadialIconScale`，只改绘制，不动圆心、圈距、命中）：应用图标按不透明本体占比放大（上限 1.25×），本体与「更多」底板同大；
「更多」底板随「图标形状」：圆形画圆盘；方形取扇形里第一个合格应用图标的外形模板（`IconShapes.bodyMaskFrom`，裁到本体，与侧边栏条目塑形同法），
用 `saveLayer` + `SrcIn` 把渐变填进模板，模板按源图标跨次呼出缓存（`MorePlateMasks`）；取不到模板时退回 0.25 圆角方形。
内侧圆弧每向内一圈乘步进——两条圆弧 0.92，三四条 0.95（用户定）；角落空白区与设置页命中预览同样按最内圈缩小后的直径。
屏幕中央所选应用卡片（开关 `radial_center_preview` 默认关、`radial_center_label` 默认开）：`CornerRadialOverlayView.drawCenterPreview` 在扇形画布上画，
不新开窗口，只画图标与应用名、不加底板（用户要求），应用名白字加阴影；图标经 `AppCatalogSnapshot.previewIconLoader` 按目标边长从 `DENSITY_XXXHIGH` 自适应图标重画（再走 `IconShapes`），不放大扇形那份位图，最近 4 个缓存在目录里；侧边栏工具/快捷方式没有高清来源，仍用 96 px 那份。布局见 `RadialCenterPreview`（图标边长取设置 `radial_center_size_dp` 48–160、默认 96 dp，不超过安全区短边 45%；应用名 16 sp 随图标按比例缩放、限 12–24 sp，最宽 60% 安全区宽度省略），
淡入淡出 120 ms、换项从 0.94 弹回；选中「更多」不显示。开关与大小经 `begin(centerPreview, centerLabel, centerSizeDp)` 每次呼出从 `lastSettings` 传入，不依赖目录快照。

**扇形目录**　`ColorOsAppCatalog` 在 system_server 的单线程执行器里工作，一次刷新分两步发布：
① 只为扇形条目（≤18 项）取名称、图标并塑形，立即发布；② 读全部名称、排好「更多」顺序后发布（未画好的图标用
`PanelIconPlaceholder`，面板格子进入视野时经 `panelIconLoader` 在独立线程按需取图），随后后台补齐全部图标再发布一次。
应用列表只在 reloadApps（解锁恢复、目录为空）或包广播（`invalidatePackage`）时重读；名称与图标按条目缓存，
只丢弃变动的包。增减固定项**不重读应用列表**。禁止回到「每次刷新把全部应用图标画成位图」的做法：
本机 537 个桌面入口，那样增减一项要等数秒、开机后头几次呼出是空扇形。
补位来源 `radial_fill_source`：最近使用（`getRecentTasks`）、最常使用（`UsageStatsManager` 近 7 天前台时长，
排除桌面与本模块，缓存 10 分钟）、不补位（未自定义的内圈留空，默认圆弧仍按最近使用）。
补位跳过当前前台应用：`ModuleEnvironmentState.observeForeground`（回调处在 ATMS 锁内，只投递）→ 协调器
`onForegroundChanged` → `ColorOsAppCatalog.updateForeground` + 刷新；固定项不受影响。
「更多」顶部顺序与补位依据一致（最常使用时按常用）；开机后第一次刷新在读全部名称前先发布这批应用，面板不空等。

**「更多」面板**　不替换原厂面板，而是接管它的内容视图：`ColorOsAllAppsContent` 构造侧边栏自己的面板类，
`FavoriteGroupInjector` 在 `bindAdapter` 时**原地改写同一个 ArrayList**（不换对象、不 notify）；
点击回调按 `alias` 回查而不是按索引分发，避免搜索后索引错位打开错误应用。
数据源头（侧边栏 16.11.3 字节码核对）：adapter 的 `mData` 就是 `SearchHelper.currentDataList` 同一个对象，
原厂所有重灌都是 `clear(); addAll(工具折叠/展开/不折叠列表)`，出现在 `buildAllList`、`fuzzyMatch`（空串即退出搜索）、
`buildCollapseList`、`buildExpandList`、`onDynamicIconChanged`。`SidebarHookInstaller.installPanelDataHooks`
在这些方法返回后、只对模块面板的 SearchHelper 换回「收藏 + 最近」并 notify；带标题才接管，搜索结果原样保留。
不再用定时器、数据观察者或 `applyIfStale` 追赶。分组标题行是 `LabelViewHolder`，文案来自 viewType 对应布局的固定字符串，
原厂 `onBindViewHolder` 不处理它；`title_bind` 钩子（`AllAppRecyclerAdapter.onBindViewHolder` 返回后）
按登记角色写入文案（`PanelGroupLabels`）。
开关 `panel_native_list`（「「更多」显示原有列表」）打开时，`bindAdapter` 与 refill 接管都跳过，面板保持原厂「工具 + 全部应用」与原厂标题。
此模式下另有字母索引：`refreshAlphabet` 在绑定与每次 refill 后，读 currentDataList 里「应用」标题（viewType `ALL_APP_TABLE_APP`）之后的一段，
按名称首字母分桶求每个字母的首个位置（`PanelAlphabet`，拼音取 ICU `Han-Latin; Latin-ASCII`，`PanelPinyin` 缓存）。
原厂「应用」段已按首字母排序（用户在设备上确认），不重排、不 notify。卡片上叠 `PanelIndexBar`（A–Z、#，铺满卡片、只收右侧 32 dp 竖条触摸），列表右内边距加 32 dp 让位；
绑定时原厂列表常已加载完、adapter 尚未挂到 RecyclerView，之后没有重灌回调，所以 `placeIndexBar` 每 50 ms 重试找已布局的列表（最多 20 次；按 adapter 匹配，退回第一个 `androidx.recyclerview.widget.RecyclerView`），卡片建好后也补算一次；首版只在绑定当下找一次，实测索引条不出现。滑动换字母时 `SEGMENT_TICK` 并经 `LayoutManager.scrollToPositionWithOffset(pos, 0)` 跳转（方法名 16.11.3 未混淆）。搜索中隐藏。
回桌面自动收起：侧边栏面板窗口 `ColorOsAllAppsWindow` 在 `show()` 注册、`dispose()` 注销 `ACTION_CLOSE_SYSTEM_DIALOGS` 接收器；
内置兜底面板由协调器常驻接收器在 `morePanelActive` 时调 `CornerRadialOverlayView.dismissPanel()`。
只认 reason `homekey`（回桌面键）与 `recentapps`（本机手势上滑回桌面发的是它），其余 reason 不收起。

**面板工具小窗**　付款码、扫一扫这类工具由侧边栏自建 Intent，经 `AppPlatformUtils.n(Intent, Bundle, UserHandle)`
交给系统 uid 代发，界面进程无法重发（目标是第三方未导出 Activity）。做法是点击前登记目标包，
在代发瞬间把 zoom 参数**就地并入系统已建好的 Bundle**，启动链路不变。

**通知点击**　钩 SystemUI 的 `OplusStatusBarNotificationActivityStarterExImpl.setNotificationTypeStarterLog`
（类名带 `Impl`）：依次移除横幅 → 剥离 `android:activity.remoteTransition` → 写窗口参数 →
非横幅时调 `collapseShadeForActivityStart()`。横幅实例早于钩子创建，需同时钩 `HeadsUpManagerImpl.showNotification` 取 thisObject。

**双击横条关闭**　「小窗关闭」第 4 项（`OutsideTapCloseMode.CaptionDoubleTap`，存储值 3），与窗外单击 / 双击互斥。
钩 `FlexibleTaskCaptionView.onDoubleTap`：原方法只处理迷你小窗（退出迷你态或与前台交换），普通小窗返回 false；
只在原方法返回 false、且横条所属任务是普通小窗（柔性状态 1）时调 `exitFlexibleTask(task, true, 0, 0)` 关闭该任务。
该模式下 `closesOnOutsideTap` 为假，横条触控区不扩到窗外，避免双击窗外也落到横条上被当成关闭（参考 2237742261/FlymeFreeform 的同类实现）。

**设置广播**　设置页每次改设置都发 `ACTION_PINNED_TARGETS_CHANGED`，带完整快照（`SettingsTransfer.EXTRA_SETTINGS`，
由 `ModuleSettingsSnapshot.writeTo/readFrom` 编解码，新增设置无需另登记），发往 android、smartsidebar、systemui、launcher 四个进程，
接收端统一走 `ProcessConfiguration.applySettingsBroadcast`；旧固定项字段仍一并发送，给尚未重启、仍跑旧代码的 Hook 进程用。
SystemUI 在 Application.onCreate、桌面在首次触摸时登记接收端。目的：模块重装后远程配置回调失效，靠广播让所有设置不重启即生效。

**窗外关闭 / 快速上滑 / 数量上限**　分别钩 `FlexibleTaskController.exitFlexibleTask`、
普通小窗底部横条上滑入口、`FlexibleWindowManagerService.getMaxWinNum`；应用准入钩三类共 9 个
`isSupportFlexibleWindow`（FlexibleWindowManagerService 2、FlexibleTaskController 3、FlexibleWindowUtils 3）。
数量上限取 0 =「跟随系统」时 `getMaxWinNum` 直接 `proceed`，不覆盖。实测装了 LuckyTool（多浮窗上限 200）时生效的是 LuckyTool 的值，即使本模块设为 5 也压不住，原因未核实（可能走的不是同一条判定路径，或钩子顺序在外层）。

**超限关窗（不贴边）—— 已移除，不要再加回来**

2026-11-08 决定删除 `OverflowCloseHook`，`SystemServerHookInstaller` 里的安装调用一并去掉。
现在超限完全交给系统：窗口缩成边缘浮标，就是原厂行为。

移除原因（三轮实测都失败，代价大于收益）：

- 系统的溢出路径不止一个入口。`updateTaskMaxNumWhenFocusChanged` → `determineFlexibleTaskToExit`
  → `setLastFlexibleTaskToExitLocked` 里既有 `FloatHandleController.isInFloatingList` 这类早退，
  也有直接建浮标的路径，钩子经常根本不触发；
- 钩住唯一必经点后，能拿到的只有 `prevTask`。从桌面新开窗口时它不在小窗列表里，
  代码会退回「按列表取第一个」，而第一个往往正是刚打开的窗口，于是形成
  **关掉 → 系统重启该应用 → 再关** 的循环，表现为整机卡死（实测 00:47:03/07/11 连续三轮）；
- 按 `taskId` 判「最早」也不成立：任务被复用后 id 保持旧值，重开 QQ 时它比新任务还小，
  刚打开的会被误关（表现为点开就消失、切不回去）；
- 关窗后应用立即变 cached，与系统 freezer 的节奏打架，反复冻结/解冻放大卡顿。

要重新做，先满足三条前提：拿到当前焦点任务（钩 `updateTaskMaxNumWhenFocusChanged` 的第二个参数）、
一次只关一个并带冷却、以及一套能稳定复现的验收脚本；否则不要动系统默认的浮标行为。

**暂停条件**　`ModuleEnvironmentState` 维护锁屏、横竖屏、游戏模式、前台包；前台包来自
`ActivityTaskManagerService.setLastResumedActivityUncheckLocked`。横屏与游戏模式只暂停扇形呼出，
小窗能力用不含暂停的 `isModuleActive()` 判定。

## 四、启动判定（`reasonFor`）

判定已迁入 `hook/launch/decision/LaunchDecider`，无 android.* 依赖；输入为 LaunchSnapshot / LaunchEnvironment，状态为 LaunchMemory。判定只读状态，返回的日志及返回指纹更新由执行层按顺序应用；接管成功后才记链接、目标和跳数。旧函数与新函数一一对应见 `docs/starter-decision-migration.md`，InterAppLaunchTracker 保留为共享同一份内存的薄包装。


全部启动改写受单一开关 `zoomLaunch` 控制：`reasonFor` 首行读它，关闭即返回 null，任何入口都不改写。
八段判定，先命中先返回：

1. 来源标记（SystemUI 写入的通知标记、控制中心磁贴）
2. 排除列表与「返回发起方」识别 —— 放行
3. 通知点击（发起方 SystemUI + PendingIntent）
4. 文件选择 —— 始终全屏
5. 应用间跳转（带 resultTo 的跨包、双方均为第三方的跨包）
6. 桌面发起（快捷项 / 快捷菜单 / 卡片），实现见 `launcherOriginReason`
7. system_server 以 MAIN 代发
8. SystemUI 自身发起的其余启动，按磁贴 / 通知栏区分

顺序不能调：通知点击与文件选择都带 resultTo，放到第 5 段之后会被抢先命中；控制中心判定依赖发起方信息，必须更晚。

桌面四类入口只靠发起方字段区分，不看 Intent 内容：

| 入口 | callingPackage | realCallingUid | resultTo | action |
|---|---|---|---|---|
| 图标点击（保持全屏） | 桌面 | 桌面 | 有 | MAIN |
| 长按快捷项 | 目标应用 | 桌面 | 无 | 任意 |
| 快捷菜单（应用详情） | 桌面 | 桌面 | 无 | 非 MAIN |
| 卡片 / 负一屏 | 桌面 | 桌面 | 有 | 非 MAIN |

- 只判 `callingPackage == 目标包` 会把应用内跳转一起小窗化，必须同时比对桌面 uid
- 不能用 action 区分图标与快捷项：QQ 的快捷项就是 `ACTION_MAIN` 加参数（flags `0x14000000`）
- 应用内页内跳转同样携带 resultTo，所以第 5 段要求「跨包」
- 返回发起方的启动与主动跳转特征相同，靠 `InterAppLaunchTracker`（2 秒窗口）识别

`LAUNCHER_CARD_SKIP` = SKIP_TARGETS + 安装器 + 分享面板 + 桌面自身 + 安全中心：桌面长按菜单的
「编辑」「上锁」因此不会被开成小窗。

## 五、关键参数

- 扇形命中半径 40–140 dp，默认 100 dp；选中放大 1.2×
- 触发范围 24–160 dp，默认 84 dp
- 扇形条目数 1–6，小窗数量 0–5（0 = 跟随系统；两个上限不同，不可混用）
- 多圆弧 1–4 条：默认圆弧放「数量上限」内条目 +「更多」，内侧圆弧容量 5/4/3（`RadialArcPlan`）；默认圆弧固定项仍存 `corner_targets_v2`（上限 6），第 2～4 圈各存 `corner_targets_arc{2,3,4}_v1`（上限 5/4/3，空即按补位方式填充），减少圆弧条数不清除；启动前核对用 `allPinnedTargets`；广播 `EXTRA_INNER_ARC_TARGETS` 携带内圈固定项
- 圈距 `min(1.3 × 图标, (外半径 − 2.27 × 图标)/(条数 − 1))`，不小于图标直径；角落空白区 = 最内一圈半径 − 圈距
- 补位方式 `radial_fill_source`：0 最近使用（默认）/ 1 最常使用 / 2 不补位
- 震动：同圈换格 `CLOCK_TICK`（80 ms 节流）；跨圈换格 `GESTURE_THRESHOLD_ACTIVATE`（不节流），圈号见 `RadialArcPlan.arcOfIndex`
- 跨圈移动固定项走 `FrameworkConnectionRepository.moveTargetToArc`（`ArcPins.move`），两圈一次提交、一次广播；含未启用的圈
- 命中范围预览按 `RadialArcPlan.expectedArcCounts`（默认圆弧按数量上限，内圈按固定项 / 容量 / 不补位为 0）
- 双击关窗阈值 `ViewConfiguration.getDoubleTapTimeout()` = 300 ms
- 面板数据原地改写同一个 ArrayList、不换对象；原厂源头重灌（见第三节）返回后才换回「收藏 + 最近」并 notify

## 六、构建、签名与发布

**签名密钥**　项目根目录 `release.keystore`，维护者 ZaiLinn 自己的密钥（2026-11-08 生成，别名 `flymefreeform`，
证书 `CN=ZaiLinn, O=FlymeFreeform, C=CN`，权限 600，被 `.gitignore` 的 `*.keystore` 排除）。
- 必须保留并另行备份；丢失后已装用户无法覆盖升级
- 口令不写进任何文件、不进对话：脚本依次读环境变量 `FLYMEFREEFORM_KS_PASS`、钥匙串条目 `flymefreeform-release`
  （`security add-generic-password -a "$USER" -s flymefreeform-release -w` 添加一次）、交互输入，经 `env:` 传给 apksigner
- 与 Hoerire 官方版、调试签名版互不能覆盖安装；从它们切换过来需先卸载（设置会丢）

**版本号**（用户 2026-11-08 定）：每次代码更新 `flymeFreeformVersionCode` +1；每构建一次 APK，`flymeFreeformVersionName` 末位 +1（1.3.7 → 1.3.8），
构建前改好，产物名随之变化，同一版本名不出两个包。两项都在 `gradle.properties`。

**构建**　一律用 `local-release.sh`（只在用户明确说「构建」之后）：

```
sh local-release.sh               # 单测 + assembleRelease + zipalign -P 16 + v1/v2/v3 签名 + 两项校验，产物落 out/
sh local-release.sh --sign-only   # 只对 app/build/outputs/apk/release/ 下已有的未签名产物重新对齐、签名、校验
sh local-release.sh --archive     # 另把源码打包到 tmp/ 并 adb push 到 /storage/emulated/0/（交付前用）
```

- 签名方案 v1+v2+v3 全开（`--min-sdk-version 23`），校验 `apksigner verify -v --min-sdk-version 23` 三项全 true、
  `zipalign -c -P 16 4` 通过；脚本任一项不过即退出
- 没有 `release.keystore` 时脚本退回 `~/.android/debug.keystore`，产物名带 `-debugsigned`，只供本机临时验证
- R8 会把入口类改名（如 `l01`），`META-INF/xposed/java_init.list` 同步改写，属正常
- `./gradlew` 无执行位，用 `sh ./gradlew`；`ANDROID_HOME=~/Library/Android/sdk`
- CI：`.github/workflows/build.yml` 在 push main 或手动触发时出**未签名** APK（artifact `FlymeFreeform-apk`）。
  CI 不持有密钥；要发 CI 产物，下载后放到 `app/build/outputs/apk/release/` 再 `sh local-release.sh --sign-only`

**安装**　`adb install -r out/<产物>.apk`（只在用户明确说「装」之后）；生效范围见第七节末尾。

**发布**　只在用户明确要求时推送或发布，目标是 `ZaiLinn/FlymeFreeform`：
- 源码仓库 main；Release 用 tag `v<版本名>`，资产为 `local-release.sh` 签好的 `out/` 产物，摘要以 sha256 记入第十节
- LSPosed 模块仓库（`Xposed-Modules-Repo/io.github.zailinn.flymefreeform`）尚未建立；建立后其 Release 资产须与源码仓库同一摘要，
  介绍页 README 由主 README 转换：去 shields 徽章块、`<h1 align="center">` 改 `# `、相对图片路径改 raw 绝对地址
- remote：`origin` = `ZaiLinn/FlymeFreeform`，`upstream` = `Hoerire/FlymeFreeform`（2026-11-08 设置）。绝不向 `upstream` 推送
- 2026-11-08 按用户要求以本地历史覆盖了 `origin/main`（原内容是 Mangi-11 0.1.9 `2475af1`，可从 Mangi-11 仓库找回）。
  本地 main 跟踪 `origin/main`，之后正常快进推送，不再强推
- `upstream/main` 已被 Hoerire 强推为 `f6d03ac`，本地历史仍基于旧的 `87c0aaa`，两者不共享后续提交
- 推送遇 `send-pack: unexpected disconnect` 时加 `-c http.postBuffer=524288000 -c http.version=HTTP/1.1` 重试
- 提交按改动拆分；同一文件多次修改又没有中间快照时，只能按文件分组提交

## 七、日志与排障

starter 新日志（WARN、常开）：STARTER_LOCK_PROBE_UNAVAILABLE（无法读取 WM 锁）、STARTER_LOCK_VIOLATION_<name>（在 WM 锁内执行副作用，10 秒节流）、STARTER_EFFECT_FAILED_<name>（投递任务运行时异常）。


日志只在 `/data/adb/lspd/log/modules_*.log`。诊断消息一律 lambda 传入 `ModuleLog.debug(tag) { }`，
详细日志关闭时不构造字符串、不读状态；常开 INFO 只留给 `*_INSTALLED` / `*_READY`。

```
L=$(ls -t /data/adb/lspd/log/modules_*.log | head -1)
grep -aE 'PATTERN' "$L" | sed 's/.*FlymeFreeform //' | tail -30
```

本机 grep 是 BRE，`\|` 会静默失效，必须用 `-E`。

| 现象 | 看什么 |
|---|---|
| 启动未接管 | `ZOOM_DECISION_*_okfalse` 的 `cp` / `rr` / `rt` / `tg` |
| 注入成功 | `ZOOM_INJECTED_<launcher_card \| launcher_shortcut \| launcher_settings \| inter_app \| system_launch \| notification \| control_center>` |
| 磁贴长按 | `ZOOM_PREEMPTED_<包名>` |
| 面板工具 | `PANEL_TOOL_HANDLED_<alias>` 后紧跟 `PANEL_TOOL_ZOOM_<包名>` |
| 面板点击 | `PANEL_CLICK` 的 index / alias / aligned / size / query |
| 通知 | `NOTIF_BUNDLE_ZOOM` 的 dropped；钩子缺失看 `NOTIF_BUNDLE_HOOKED_*` |
| 横幅未移除 | `NOTIF_HEADS_UP_MANAGER_NULL`（未取到实例）/ `NOT_HEADS_UP`（时机过晚） |
| 扇形被拦 | `SYSTEM_GESTURE_REJECTED_<原因>` |
| 扇形目录耗时 | `SYSTEM_CATALOG_RADIAL_<ms>MS_<项数>`（扇形就绪）、`SYSTEM_CATALOG_PANEL_ORDER_<ms>MS_<n>`（面板可开）、`SYSTEM_CATALOG_PANEL_<ms>MS_<n>`（图标补齐）；`SYSTEM_CATALOG_ICON_MISSING_<n>` 表示扇形图标读取失败、退回按总数均分 |
| 呼出时目录状态 | `SYSTEM_OVERLAY_HIT_RANGE_<dp>DP_…_APPS_<n>_PANEL_<n>`；`APPS_0` 后跟 `SYSTEM_CATALOG_EMPTY_REFRESH` 即目录未就绪 |
| 面板数据接管 | `PANEL_DATA_HOOKS_INSTALLED_<n>`（应为 6：5 个重灌方法 + 标题绑定）；缺哪个看 `PANEL_REFILL_HOOK_UNAVAILABLE_<方法名>` |
| 作用域外的子进程 | `MODULE_SKIPPED_UNEXPECTED_PROCESS_<进程名>`，DEBUG 级属正常 |

面板工具注入后 system_server 侧对应那条会显示 `okfalse`——参数已在侧边栏写好，注入器读到
`alreadyZoom` 不再二次改写，属正常，不是漏判。

生效范围：system_server 改 `killall system_server`（约 30–40 秒恢复）；SystemUI 改 `kill com.android.systemui`；
面板改 `pkill -f com.coloros.smartsidebar`（进程名带 `:ui`/`:edgepanel`，`killall` 匹配不到；`:ui` 按需启动）；桌面改 `killall com.android.launcher`；纯 UI 改重开模块应用。

## 八、不做的事

以下方向投入过排查，结论是不做，重新评估前先看这里：

| 方向 | 结论 |
|---|---|
| 游戏内悬浮窗的窗外关闭 | 游戏助手拉起的窗口不归 FlexibleTaskController，柔性状态读 0，首次点击必然落到系统 |
| 小窗居中 | 位置由 `OplusFlexibleTaskLayoutPolicy.calcLaunchBounds` 决定，注入参数里没有位置键 |
| 翻译 / 识屏工具 | 跨进程无法可靠触发，已移除 |
| 系统分发服务代发的 PendingIntent 启动（无法注入，只能抢占） | 应用（如酷安）把链接 Intent 交给 `android.ext.services` 代发时，
  调用方是系统 uid、真发起者是原应用。这类启动由后台发起，往 options 注入小窗参数会被
  `FlexibleTaskController.adjustOptionsForFlexibleTask` 以 `reason:9` 拒绝，窗口创建不出来（表现为点击无反应）。
  因此不走 inject，改在 `preemptWithZoomWindow` 里用系统 zoom 入口重发；重发失败时原启动照常进行，退化为全屏 |

## 九、改动守则

0. starter 钩子运行时可能持有 WindowManagerGlobalLock；不得同步发广播或调用可能拿 AMS 锁的服务。副作用统一经 StarterSideEffects 投递，新增同步调用必须在 KDoc 说明锁安全依据，构建前由 scripts/check-starter-safety.sh 检查。


1. 结论要能指到一行日志或一段字节码，反编译优先于猜测（`dexsql` 查 services.jar / oplus-services.jar / SystemUI.apk）
2. 重启前先把话说完：改了什么、装的哪个包、sha256、验证什么、看哪几条日志；命令用 `setsid` 脱离进程组
3. 能只重启一个进程就不重启设备
4. 注释与提交信息第三人称陈述，只写做什么、为什么、约束是什么
5. 不在手机上跑 Gradle；不跨进程用静态字段通信；不按固定次数 `postDelayed` 撞系统数据
6. 面板分组标题不能按文本或先后改名（标题行文案由 viewType 布局决定，两段都显示「工具 / Tools」，且随系统语言变化）：注入器按对象身份登记标题角色（`FavoriteGroupInjector.titleRole`），`title_bind` 钩子在 `onBindViewHolder` 返回后按角色改写可见标题，文案随语言取「收藏/最近」或「Favorites/Recent」；不能读 `UserPanelView.getRecentData()`（占位内容）
9. 扇形目录不得在刷新路径上加载全部应用图标；新增补位来源或排序时，只能在「挑选条目」一步做，图标仍按条目缓存
7. 不在 SystemUI 侧改写通知点击入口（回调不稳定，命中失败会丢弃原始事件）
8. 工程纪律：构建输出落 `out/`、临时文件落 `tmp/`、`release.keystore` 必须保留、交付前 `tar -czf` 打包放存储根目录

## 十、当前状态

更新时间：2026-11-08 18:40（Asia/Singapore，按设备时钟）

| 项 | 值 |
|---|---|
| 版本 | 工作副本 1.4.2 / 2026110833（下次构建 versionName 再加）；设备为 1.4.2 / 2026110830（`out/FlymeFreeform-v1.4.2.apk`，sha256 `012ca69d…3641`，2026-11-08 18:35 装并重启 system_server）。v1.3.10 曾按用户要求覆盖重发（破例未加 versionName），重发资产为 2026110821 |
| 本地构建 | `out/FlymeFreeform-v1.4.1.apk`，sha256 `04d7b8e7662f83d8542a036cef3d3c73689fd85596cfe51caa62d554c63cccbf`，用户密钥签名，已装设备、未发布。1.4.0 `1e847fe3…f358` 有死锁勿用；1.3.12 `2ce93a54…6cfd`；1.3.11 `b56e0400…42bd`；`out/FlymeFreeform-v1.3.10.apk` 为重发的 Release 资产 `afc03c6b…65f0`，首发包 `83d94a5e…2bf3` 备份在 `tmp/FlymeFreeform-v1.3.10-83d94a5e.apk` |
| 工作副本 | `~/Documents/Code/FlymeFreeform`，main = `origin/main`。v1.3.7（`5ce7888`）之后：用户网页提交 `bdb6a30`（README 致谢精简），`a454470` 新设置项、`5a89d85` 「更多」原有列表 + 字母索引 + 回桌面收起、`9d1c35d` 扇形外大内小 + 屏幕中央所选应用 + 内置面板回桌面收起、`c1d0893` 添加应用快速点击闪动，之后是 1.3.10 版本号与文档、用户网页提交 `e3681e4`（README 去上游链接）、方形「更多」修复、README 换 png 截图，重发 1.3.10 的版本号与文档（v1.3.10 tag 所指，`b3562e0`）。之后 2026-11-08 按功能分组提交 1.3.11–1.4.3 的改动：`8d015c7` 侧边栏目录缓存与悬空绑定、`530fd2b` 设置广播共享身份与完整快照、`bd5f490` 「最近」排除扇形固定项、`1dd1297` 「更多」打开时图标左移、`bdc667b` 双击横条关闭、`aef03c5` 应用互跳小窗与死锁修复（含关窗重开）、`ac8cb85` 文档与版本号；中间提交未单独编译，HEAD 编译与单测通过；均未推送。前三个功能提交均在临时 worktree 单独编译通过；`upstream` = Hoerire |
| 已发布 | Release [v1.3.10](https://github.com/ZaiLinn/FlymeFreeform/releases/tag/v1.3.10)，资产 `FlymeFreeform-v1.3.10.apk`（sha256 见「本地构建」）；上一版 v1.3.7 资产 `a8344244…61d3` |
| 设备已装 | `04d7b8e7`（1.4.1 / 2026110827），17:54 装后经 restart-gate 重启 system_server。此前 1.4.0 在 17:20:50 死锁、17:23 看门狗软重启 |
| 设备另有 | 旧包 `io.github.hoerire.flymefreeform` 仍在，应在 LSPosed 停用并卸载 |
| 设备反馈 | 1.3.8：中央卡片有黑底板、图标发糊、字母索引条不出现，1.3.9 已改；1.3.9：添加应用快速点击闪动，1.3.10 已改。第 2 圈「其余变灰」核实为该圈已存满 5 项（两个工具 + DeepSeek、Duolingo、Firefox），按设计 |
| 未打包改动 | B：纯 Kotlin 判定与启动记忆已迁出，injector 为 343 行，日志码及条件顺序保留。 A 锁安全重构：统一副作用出口、WM 锁检测、静态检查、异步 PM 缓存；未构建 APK。 相对设备上的 1.4.2：① X 已在小窗时再点链接，1.4.2 放行普通启动后 X 以 CLEAR_TASK 重启自身、系统不认小窗参数而退成全屏（日志 `setScenario --> 0`、无 `collectFlexibleTaskInfo`）；改为吞掉本次启动，主线程先经 `ZoomTaskCloser`（OutsideTapCloseHookInstaller 在 `addFlexibleTask` 时记下 FlexibleTaskController）对该包最上层普通小窗 `exitFlexibleTask`，400 ms 后以 `startZoomWindow` 重开，走首次打开的路径（日志 `ZOOM_REOPEN_SCHEDULED_` / `ZOOM_REOPENED_closed…_started…`）；Telegram 重发的同一链接按 linkKey 吞掉。② 「更多」原有列表模式打开时图标往左挪：字母索引条在拼音算完、列表已布局后才给列表加右内边距，网格列宽重算；改为建卡片、绑定 adapter、逐帧找列表时就先预留（只认绑定了本面板 adapter 的列表），索引条不显示也不撤。本机编译与 183 项单测通过，未构建。
| 未在设备验证 | B 完整回归：① Telegram→Chrome 小窗；② Telegram→X 直达帖子；③ X 小窗中再点链接，关旧窗重开并送达新帖子；④ 连点十次以上不死锁、无 STARTER_LOCK_VIOLATION_；⑤ 登录/授权回传；⑥ 页内跳转；⑦ 通知、磁贴长按、桌面卡片/快捷项/设置；⑧ 桌面全局搜索仍全屏。 A：通知面板收起改为队首投递后小窗是否被遮挡；启动早期冷缓存、安装/卸载后目录缓存恢复；锁屏/解锁切换；Telegram 连点 X/Chrome 不死锁且无 STARTER_LOCK_VIOLATION_。 下一包：Telegram 点 Chrome 链接小窗（`_link1` + `ZOOM_PREEMPTED_com.android.chrome`）、点 X 链接小窗且直达帖子（中转页重启是否接受注入，`ZOOM_INJECTED_self_relaunch`；若被 FlexibleTaskController 拒则全屏但链接仍在）、X 已在小窗时再点链接：旧小窗关闭后重开为小窗并显示新帖子（`ZOOM_REOPENED_closedtrue_startedtrue`），不再退成全屏；「更多」原有列表模式打开时图标不再左挪；装新包并重启一次后，再装新包不重启，改图标形状 / 圆弧数 / 角落开关 / 小窗关闭方式能否即时生效（详细日志 `SETTINGS_BROADCAST_APPLIED_FULL`）；「双击横条」关普通小窗（`CAPTION_DOUBLE_TAP_CLOSE`），迷你小窗双击仍为原生行为，该模式下点窗外不关、连点链接不再卡死 system_server；登录授权类 startActivityForResult 仍全屏且能回传；应用内翻页不被误接管；1.3.12：增删固定项不重启即生效；之后再装新包（不重启 system_server）增删固定项仍生效；搜索添加时连点应用，列表顶部不再弹出「正在读取侧边栏目录」、工具与快捷入口不变灰、不再出现「不可用」；方形模式下「更多」底板外形、大小是否与相邻应用图标一致（深浅色各看一次）；1.3.10 相对 1.3.9 的添加应用闪动修正与三处占用优化；屏幕中央所选应用卡片（无底板、高清图标、应用名省略、横屏位置、换项弹动）；扇形图标外大内小（方形模式圆角是否被裁、选中放大是否越出屏幕）；字母索引条（日志 `PANEL_ALPHABET_SHOWN` / `PANEL_ALPHABET_NO_LIST`；右列图标能否点、跳转、震动、深色文字颜色）；回桌面收起「更多」（`ALL_APPS_CLOSE_SYSTEM_DIALOGS_<reason>` / `SYSTEM_MORE_PANEL_CLOSE_<reason>`）；跟随系统时 LuckyTool 上限生效 |
| 待办计划 | `docs/plan-starter-hook-refactor.md`：starter 钩子锁安全（统一副作用出口、运行时锁检测、构建前静态检查）与启动判定纯函数化 + 真实日志回归测试，A 已完成，release Kotlin 编译与 185 项单测（3 项跳过）通过，A、B 均已完成，B 的 17 类场景、时间窗边界、惰性查询与失败路径测试通过 |
| 待查 | 装新包后增减应用扇形不更新：已定位为固定项广播未共享身份（`sentFromUid` 为 -1 被接收端丢弃），平时只靠 LSPosed 远程配置回调，而模块重装后 Hook 进程里该回调不再触发。1.3.12 已让广播共享身份；图标形状、圆弧数等其他设置原先只走远程配置，现已改为全量广播（待设备验证）；1.4.0 重启后侧边栏目录不可用：推断为上游已修的悬空绑定（system_server 起来后侧边栏进程晚到，连接回调晚于超时即放弃建链且不再重绑），现有版本无日志可证，移植后看 `SIDEBAR_ENTRY_TIMEOUT_LIST` 与是否恢复 |
| 单元测试 | 209 项：206 项通过、3 项跳过，release Kotlin 编译通过；未构建 APK |
| 工具 | `local-release.sh`、`restart-gate.sh`（均列入 `.gitignore`，不随仓库分发） |
| 关于页 | 只保留一个「项目地址」→ `ZaiLinn/FlymeFreeform`；上游地址与致谢在 README |
| 签名 | 用户密钥 `release.keystore`（`CN=ZaiLinn`）；口令从环境变量 / 钥匙串 / 交互读取 |

### 实测（`dc0bcfef` 版，即目录两步发布首版，重启后）

- system_server 重启后约 46 秒扇形就绪：`SYSTEM_CATALOG_RADIAL_552MS_6`；第一次呼出 `APPS_6`
- 之后每次刷新扇形 2–98 ms；首次补齐面板 530 个图标 14.4 秒（期间「更多」为空），之后 11–50 ms

### 下一步（按序）

0. 装 v1.3.7 并重启 system_server，逐项验证上表「未在设备验证」各项，同时看增减应用后扇形是否更新
1. 卸载旧包 `io.github.hoerire.flymefreeform`
2. 新包上逐项实测多圆弧、补位方式、跨圈移动、震动、预览、「更多」面板首开耗时（`SYSTEM_CATALOG_PANEL_ORDER`；图标形状让面板图标多一步处理）
3. 待评估：滑选时显示应用名

### 本轮的坑

- **重启后设置页报「检测到多个框架服务」**：1.4.2 重启 system_server 后马上打开设置页出现，强停应用再开即恢复「已连接」。
  推断 lspd（未随 system_server 重启）在模块应用启动时把服务递了两次，`liveServices` 里有两个对象，于是禁用写入防配置分叉；未取到绑定日志（应用日志超配额被丢）。
- **1.4.0 致 system_server 死锁（看门狗重启）**：`tmp/traces_SystemServer_WDT_1.4.0_deadlock.txt`（设备 `/data/anr/traces_SystemServer_WDT08_11_17_22_27.465_pid22251`）。
  binder 线程 tid=336 在 `ActivityStarter.execute` 内持有 `WindowManagerGlobalLock`，经我们的 executeRequest 钩子
  （`x1` = `ActivityStarterZoomInjector`，R8 map id `f287fa6a…` 与 1.4.0 构建一致）进入 `requestBannerCollapse` 的 `ContextImpl.sendBroadcast`，
  等 AMS 锁；tid=83 在 `attachApplication` 里持 AMS 锁、等 WM 全局锁（被跳转的应用正在起进程）——AB-BA 死锁。
  抢占路径里的任何 sendBroadcast / 拿 AMS 锁的调用都不能在 starter 钩子里同步执行（已改：`requestBannerCollapse` 投递到主线程）；1.4.0 让每次链接都走抢占，把原本罕见的时序变成必现。
  同批次还见到：X 中转页以 MAIN 重启自身时改走 `startZoomWindow` 会丢掉链接、只停在首页；X 已在小窗时再点链接，`startZoomWindow` 只把已有任务提到前台，链接不送达

- 应用间跳转（Telegram 点 X / Chrome 链接）实测全屏：从 Activity 调 `startActivity` 也带 `resultTo`（`rt=1`，requestCode -1），
  抢占路径见 resultTo 一律放行，只剩往 options 注入小窗参数，被柔性任务控制器拒绝（`adjustOptionsForFlexibleTask reason:8/9`），
  task `mode=fullscreen`；且 X（`MainActivity` 以 NEW_TASK|CLEAR_TASK 重启自身）、Chrome（`IntentDispatcher` → `CustomTabActivity` 新任务）都有中转页。
  1.4.0 改为：只有 requestCode >= 0 才算等结果；不等结果的第三方 `ACTION_VIEW` 跨包链接改走 `startZoomWindow`（`isPlainInterAppLink`），
  1.5 s 内发起方重发同一链接直接吞掉（Telegram 会用不同 flags 连发两次）；刚经 zoom 入口打开的应用 3 s 内以 NEW_TASK 重启自身也走 zoom 入口，最多 2 次（`isSelfRelaunch`）

- 固定项广播 `ACTION_PINNED_TARGETS_CHANGED` 原先用普通 `sendBroadcast` 发送，接收端（system_server、侧边栏）核对 `sentFromUid`，
  未共享身份时它是 INVALID_UID，推送一律被丢弃，`dumpsys activity broadcasts` 里却显示 DELIVERED。改为 `BroadcastOptions.setShareIdentityEnabled(true)`（1.3.12）。
  装新包只强停模块应用而不重启 system_server 时，远程配置回调失效、广播又被丢，增删应用不生效
- `killall system_server` 不会新开 `modules_*.log`（日志按 LSPosed 守护进程启动分文件），重启后的记录追加在原文件里；等待就绪时按时间过滤，不能按「最新文件里有 COORDINATOR_READY」判断

- 添加应用页的侧边栏目录读取以 `canChangeSettings` 为键：每次增删置起 isUpdating 就取消并重发请求，顶部反复弹出「正在读取侧边栏目录」、工具与快捷入口变灰；
  system_server 端 `ColorOsEntryCatalogBroker` 对 500 ms 内的重复请求、满队列（4）的请求都直接丢弃且不回复，客户端等满 7 s 超时即判「不可用」。
  改为以 `settingsEditable` 为键，并让目录读到一次后常驻（静默刷新、失败保留缓存）

- 添加应用页的本地顺序 `localOrders` 曾随 `canChangeSettings`（含 isUpdating）清空：每次写入时勾选先跳回旧配置再恢复（图标文字闪动），快速连点时后一次基于旧列表提交、冲掉前一次。改为只随 `settingsEditable` / `issue` 清空（已发布于 1.3.10）
- 占用检查后的三处优化（已发布于 1.3.10）：中央卡片高清图标的留白比例改在图标线程算（原在 system_server 主线程逐像素扫，滑选时可能掉帧）；中央卡片应用名的截断与测量按「选中项 + 大小 + 画布宽 + 字体缩放」缓存，不再每帧重算；字母索引的拼音首字母在 `PanelPinyin.executor` 后台算，全部命中缓存时才在主线程直接取，结果按 `alphabetGeneration` 只采用最新一次。高清图标缓存 6 → 4 个

- 扇形目录曾在每次刷新时把全部应用（本机 537 个）图标画成位图：增减固定项要等数秒、开机后首次呼出空扇形
- 角落空白区若以「最内一圈图标内沿」为界，两圈时第二圈只剩约 50 基准 dp 宽
- `ConcurrentHashMap` 上用 Kotlin `in` 会调用 `contains` = `containsValue`，必须写 `containsKey`
- 一次打包混入多个改动（首个 release 包含多圆弧、面板标题、搜索返回三项），出问题无法定位；之后一包一改
- 只要覆盖安装了模块包，就按改动最大的进程重启；装包后不重启 system_server 时，曾出现增减应用扇形不更新（原因未定）
- ColorOS 的 `buildAdaptiveIconDrawable` 不理会外形路径，应用图标形状只能在模块里自己裁；系统图标带透明留白，裁切前先裁到本体
- Compose 里「换位后先回到旧位置再动画」的补偿必须同帧生效（`SideEffect` 写状态、绘制阶段读），放进 `LaunchedEffect` 会闪一帧
- 装了 LuckyTool 时多浮窗上限以 LuckyTool 为准，本模块的「小窗数量」实测压不住它
- 侧边栏工具的图标资源可能不属于侧边栏自身；目录读取里任何单项失败都必须就地兜底，不能让整个目录作废
