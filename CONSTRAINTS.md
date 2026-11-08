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
  launch/ActivityStarterZoomInjector.kt   启动判定与参数注入（核心）
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
| 窗外关闭 | `hook/OutsideTapCloseHookInstaller.kt` |
| 快速上滑 | `hook/HandleSwipeUpHookInstaller.kt` |
| 暂停条件 | `hook/ModuleEnvironmentState.kt` |
| 设置界面 | `ui/` 下对应 Screen |

## 三、各功能怎么实现的

**扇形菜单**　system_server 内的协调器监听底角指针，手势逻辑在 `CornerGestureEngine`（纯状态机，可单测），
命中计算在 `RadialGeometry`；浮层由 `CornerRadialOverlayView` 自绘，窗口类型 `TYPE_INPUT_METHOD_DIALOG`(141000)，
压在输入法与键盘之上，回落 `TYPE_APPLICATION_OVERLAY`(111000)。松手当帧提交启动、复制原生 zoom 参数、
侧边栏连接常驻——三处缺一都会明显变慢。

**多圆弧**　第 1 条是默认圆弧（快捷项 +「更多」），内侧圆弧依次向内收一个圈距，容量 5/4/3 且不超过外侧一条减 1
（`RadialArcPlan.innerCapacities`）。`RadialGeometry.layout` 的 itemCenters 顺序为「默认圆弧快捷项 → 内侧各圈（由外向内）→ 更多」。
各圈实际条数以目录给出的 `AppCatalogSnapshot.radialArcCounts` 为准，自定义圈放不满时空位不会挤进下一圈。
命中只按「离哪个图标圆心最近且在命中范围内」；有内侧圆弧时另设角落空白区 = 最内一圈半径 − 一个圈距
（2/3/4 条约 128/70/52 基准 dp），不能用「图标内沿」做边界，否则两圈时第二圈只剩约 50 宽、很难选中。

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

**面板工具小窗**　付款码、扫一扫这类工具由侧边栏自建 Intent，经 `AppPlatformUtils.n(Intent, Bundle, UserHandle)`
交给系统 uid 代发，界面进程无法重发（目标是第三方未导出 Activity）。做法是点击前登记目标包，
在代发瞬间把 zoom 参数**就地并入系统已建好的 Bundle**，启动链路不变。

**通知点击**　钩 SystemUI 的 `OplusStatusBarNotificationActivityStarterExImpl.setNotificationTypeStarterLog`
（类名带 `Impl`）：依次移除横幅 → 剥离 `android:activity.remoteTransition` → 写窗口参数 →
非横幅时调 `collapseShadeForActivityStart()`。横幅实例早于钩子创建，需同时钩 `HeadsUpManagerImpl.showNotification` 取 thisObject。

**窗外关闭 / 快速上滑 / 数量上限**　分别钩 `FlexibleTaskController.exitFlexibleTask`、
普通小窗底部横条上滑入口、`FlexibleWindowManagerService.getMaxWinNum`；应用准入钩三类共 9 个
`isSupportFlexibleWindow`（FlexibleWindowManagerService 2、FlexibleTaskController 3、FlexibleWindowUtils 3）。

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
- 扇形条目数 1–6，小窗数量 1–5（两个上限不同，不可混用）
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
面板改 `kill com.coloros.smartsidebar`；桌面改 `killall com.android.launcher`；纯 UI 改重开模块应用。

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

更新时间：2026-11-08 10:05（Asia/Shanghai）

| 项 | 值 |
|---|---|
| 版本 | 1.3.6 / 2026110717 |
| 工作副本 | `~/Documents/Code/FlymeFreeform`，main = `origin/main` = `c156254`（基于 `87c0aaa` 的 7 个提交：`8d9f06d` 包名、`f700547` 测试期望、`277f1ed` 面板标题与搜索返回、`16a5753` 配置模型、`eb6aeb4` 扇形浮层与目录、`1d53dfc` 模块界面、`c156254` 文档）；`upstream` = Hoerire |
| 已发布 | Release [v1.3.6](https://github.com/ZaiLinn/FlymeFreeform/releases/tag/v1.3.6)（tag 指向 `c156254`），资产 `FlymeFreeform-v1.3.6.apk` 与 `out/` 产物同一 sha256（GitHub 资产摘要已核对） |
| 最新构建 | `out/FlymeFreeform-v1.3.6.apk`，sha256 `bdff3a8496c5ac89735fe927b8dd16887fe2a95723240f99f03765f7c5d3ba44`，2117935 B，用户密钥签名（证书 SHA-256 `14f19d42…4b52`），包名 `io.github.zailinn.flymefreeform`；含包 A–E 全部改动（用户要求直接出最新包，未按一包一改拆分）；**未安装** |
| 设备已装 | `beec312c…86d7`（调试签名 `CN=Android Debug`，v1/v2/v3 均通过、`-P 16` 对齐通过），system_server 已重启，运行中 |
| 设备包包含 | 多圆弧（1–4 条）与按圈自定义内圈、「更多」分组标题与搜索返回修复、扇形目录两步发布与缓存、两圈时第二圈命中范围加宽 |
| 已改未打包（按包拆分计划） | 包 A：「更多」面板图标按需加载（`PanelIconLoader`）+ 目录日志改走 `ModuleLog.debug`（`ICON_MISSING` 不再常开）+ 开机首次先发布常用/最近一批　包 B：扇形应用页左右滑动切圈、增删展开/收起动画、跨圈移动固定项（只需重开模块应用）　包 C：补位方式（最近使用/最常使用/不补位）+「更多」顶部随补位依据 + 补位跳过前台应用　包 D：跨圈换格震动　包 E：命中范围预览按各圈实际条数（模块应用）。另有包名改为 `io.github.zailinn.flymefreeform`（随第一次换签名一起装） |
| 单元测试 | 161 项全部通过（3 项 skip） |
| 工具 | `local-release.sh`、`restart-gate.sh` 已补（均列入 `.gitignore`，与本文件一样不随仓库分发） |
| 包名 | 源码已改为 `io.github.zailinn.flymefreeform`（目录、namespace、applicationId、`java_init.list`、广播 action、权限名）；设备上仍是旧包名 `io.github.hoerire.flymefreeform` 的 `beec312c`。新包名是另一个应用：装之前先在 LSPosed 停用并卸载旧模块，再给新模块重新勾选作用域（system_server、SystemUI、桌面、智能侧边栏）并重启 |
| 关于页 | 只保留一个「项目地址」→ `ZaiLinn/FlymeFreeform`；上游地址与致谢移到 README |
| 签名 | 用户已生成自己的 `release.keystore`（`CN=ZaiLinn`）；`local-release.sh` 已改为从环境变量 / 钥匙串 / 交互读口令。设备上仍是调试签名包，换用户密钥签的包需先卸载一次 |

### 实测（`dc0bcfef` 版，即目录两步发布首版，重启后）

- system_server 重启后约 46 秒扇形就绪：`SYSTEM_CATALOG_RADIAL_552MS_6`；第一次呼出 `APPS_6`
- 之后每次刷新扇形 2–98 ms；首次补齐面板 530 个图标 14.4 秒（期间「更多」为空），之后 11–50 ms

### 下一步（按序）

0. 改包名与换签名合在一次切换里做：LSPosed 停用并卸载旧模块 → 装用户密钥签的新包名包 → 勾选作用域 → 重启；模块设置需重新配置
1. 包 A–E 分开构建验证（改动在同一工作区，需先分开提交或按文件分批构建）：
   A 看 `SYSTEM_CATALOG_PANEL_ORDER` 耗时与重启后立刻点「更多」；B 看滑动切圈、增删动画、跨圈移动；
   C 看三种补位方式与前台应用不进补位；D 看跨圈震动；E 拖命中范围滑条看预览
2. 待评估：滑选时显示应用名

### 本轮的坑

- 扇形目录曾在每次刷新时把全部应用（本机 537 个）图标画成位图：增减固定项要等数秒、开机后首次呼出空扇形
- 角落空白区若以「最内一圈图标内沿」为界，两圈时第二圈只剩约 50 基准 dp 宽
- `ConcurrentHashMap` 上用 Kotlin `in` 会调用 `contains` = `containsValue`，必须写 `containsKey`
- 一次打包混入多个改动（首个 release 包含多圆弧、面板标题、搜索返回三项），出问题无法定位；之后一包一改
