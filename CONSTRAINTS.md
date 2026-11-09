# FlymeFreeform 实现说明

源码与 GitHub Release 为 1.5.0 / versionCode 2026110916（`main` 已推送，标签 v1.5.0 = `f6601ad`）；设备 1.4.10 / 2026110915。v1.4.10 Release 与标签已删除。包名 `io.github.zailinn.flymefreeform`
源码 `ZaiLinn/FlymeFreeform`（维护者 ZaiLinn；fork 自 `Hoerire/FlymeFreeform` ← `Mangi-11/FlymeFreeform`，致谢见 README）
工作副本 `~/Documents/Code/FlymeFreeform`（macOS），临时文件 `tmp/`；构建、签名与发布见第六节。
包名于 2026-11-08 由 `io.github.hoerire.flymefreeform` 改为现名，设备上的旧包名模块需卸载后再装新包。

本文只讲「代码怎么实现的」「改动时不能碰的边界」与「现在到哪了」。功能说明见 README，逐次改动记录见 `docs/dev-history.md`。

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
5. 收工：**每次改代码、装包、重启后都要更新本文件**（用户 2026-11-08 要求）：
   - 第十节改成最新状态（源码版本、未提交改动、设备包与 sha256、待验证、下一步），只写现状，不堆历史；
   - 受影响的实现说明（第三、四节）、参数（第五节）、日志码（第七节）同步改；
   - 本次改动的完整经过（原因、做法、验证结果）追加到 `docs/dev-history.md` 顶部。

> 为什么写死成脚本：上下文压缩会丢掉「承诺过不做的事」，只留「现在做到哪了」。
> 纪律必须能从磁盘读出来并拦得住动作，不能靠新会话的自觉。

## 一、进程分工

模块按进程名分派到不同安装器，改代码前先确认目标代码跑在哪个进程。

| 进程 | 安装器 | 负责 |
|---|---|---|
| system_server | `SystemServerHookInstaller` | 启动判定与参数注入、窗口数量上限与应用准入、窗外关闭、快速上滑、扇形协调器与目录、侧边栏目录请求端 |
| com.android.systemui | `SystemUiHookInstaller` | 底角触摸监听、通知点击转场与「目标已在前台」判定 |
| com.android.launcher | `LauncherHookInstaller` | 桌面侧底角触摸监听 |
| com.coloros.smartsidebar:ui | `SidebarHookInstaller` | 「更多」面板接管、面板工具小窗注入、固定项目录与启动的服务端 |

跨进程通信：「更多」面板走 Binder（`SidebarProtocol`），固定项目录走 Messenger（`SidebarEntryProtocol`）；模块应用与框架走 SharedPreferences 与广播；
system_server 向 SystemUI / 桌面广播手势许可（验证 SYSTEM_UID，共享发送身份）。**没有静态字段共享**。

## 二、目录

```
hook/                       各进程安装器与运行环境
  NotifBundleHook.kt                      通知点击写小窗参数、前台全屏判定（SystemUI）
  launch/ActivityStarterZoomInjector.kt   读取快照与执行判定结果
  launch/decision/                        纯 Kotlin 启动判定、时间窗与规则（无 android.*）
  launch/StarterLaunchEnvironment.kt      惰性平台查询适配
  launch/StarterSideEffects.kt            广播、面板、关窗重开的统一投递出口
  launch/StarterLockProbe.kt              WM 锁探针与违规节流
  launch/StarterQueryCache.kt             PM 查询异步缓存（预热、按启动补种）
  launch/StarterRequestReplay.kt          关窗后复制 Request 重开
  launch/ZoomLaunchSpec.kt                小窗参数契约
  launch/InterAppLaunchTracker.kt         跳转关系记录，识别返回
  launch/FlexibleWindowLimitHook.kt       数量上限与应用准入
platform/coloros/           ColorOS 私有接口封装，全部反射
  ColorOsFreeformCoordinator.kt           system_server 内扇形总协调
  ColorOsAppCatalog.kt                    扇形与「更多」的条目目录（system_server 后台线程）
  ColorOsEntryCatalogBroker.kt            固定项侧边栏目录：请求、重试、本地副本（system_server）
  ColorOsSidebarEntryConnection.kt        与侧边栏的常驻 Messenger 连接（system_server）
  SidebarCatalogStore.kt                  侧边栏目录本地副本 /data/system/flymefreeform
  ColorOsSidebarEntryEndpoint.kt          目录与启动服务端（侧边栏进程）
  ColorOsSidebarEntries.kt                读侧边栏原生条目、分身核对、调用原生路由（侧边栏进程）
  SidebarEntryKind.kt                     原生条目 → 工具 / 快捷方式 / 特殊入口 / 分身
  ColorOsAllApps*.kt                      「更多」面板窗口与内容（侧边栏进程）
  PanelToolLaunchBridge.kt                面板工具小窗参数注入（侧边栏进程）
  FavoriteGroupInjector.kt                面板数据原地改写、分组标题角色登记
  PanelGroupLabels.kt                     分组标题文案（收藏/最近，随系统语言）
  SidebarDexQuery.kt / LauncherDexQuery.kt  DexKit 查混淆符号，结果按 APK 身份缓存
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
| 固定项里的侧边栏工具 / 快捷方式 / 分身 | `ColorOsEntryCatalogBroker` → `ColorOsSidebarEntryConnection` → `ColorOsSidebarEntryEndpoint` → `ColorOsSidebarEntries` |
| 扇形应用页（按圈分页） | `ui/PinnedAppsScreen.kt` |
| 扇形浮层 | `window/CornerRadialOverlayView.kt` |
| 角区触摸窗口 | `hook/SystemUiCornerInputMonitor.kt` |
| 「更多」面板 | `platform/coloros/ColorOsAllApps*.kt` |
| 面板工具小窗 | `PanelToolLaunchBridge.kt` + `SidebarHookInstaller` |
| 通知点击 | `hook/NotifBundleHook.kt` |
| 各类启动判定 | `hook/launch/decision/LaunchDecider.kt`（判定）、`ActivityStarterZoomInjector.kt`（读快照、执行） |
| 数量上限与准入 | `hook/launch/FlexibleWindowLimitHook.kt` |
| 小窗关闭（窗外单击 / 双击、双击横条） | `hook/OutsideTapCloseHookInstaller.kt` |
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
图标大小外大内小（`RadialIconScale`，只改绘制，不动圆心、圈距、命中）：应用图标按不透明本体占比放大（上限 1.25×），本体与「更多」底板同大；
「更多」底板随「图标形状」：圆形画圆盘；方形取扇形里第一个合格应用图标的外形模板（`IconShapes.bodyMaskFrom`，裁到本体，与侧边栏条目塑形同法），
用 `saveLayer` + `SrcIn` 把渐变填进模板，模板按源图标跨次呼出缓存（`MorePlateMasks`）；取不到模板时退回 0.25 圆角方形。
内侧圆弧每向内一圈乘步进——两条圆弧 0.92，三四条 0.95（用户定）；角落空白区与设置页命中预览同样按最内圈缩小后的直径。
屏幕中央所选应用卡片（开关 `radial_center_preview` 默认关、`radial_center_label` 默认开）：`CornerRadialOverlayView.drawCenterPreview` 在扇形画布上画，
不新开窗口，只画图标与应用名、不加底板（用户要求），应用名白字加阴影；图标经 `AppCatalogSnapshot.previewIconLoader` 按目标边长 1.5×（最长 1024 px，默认尺寸单张约 1.6 MB）从 `DENSITY_XXXHIGH` 图标经 `ColorOsRadialIconRenderer.shapedIcon(source, sizePx)` 按预览边长原生塑形后重画（再走 `IconShapes`，参数同扇形），不读 APK 原始资源，不放大扇形那份位图，缓存当前扇形条目（目录加载后后台预取缺的、裁掉不在扇形里的）：扇形里的固定项全部常驻 `pinnedPreviews`、不受上限淘汰（用户决定固定项都保持高清，18 项最大尺寸约 72 MiB）；补位项进按字节上限 32 MiB 的 `LruCache`，只预取放得下的几项，其余选中时现画；浮层收起即清空 `previewImages`；同一条目的在途任务合并，执行前再查缓存，选中项排在图标线程最前（`iconJobs` 双端队列）；侧边栏工具/快捷方式没有高清来源，仍用 96 px 那份。布局见 `RadialCenterPreview`（图标边长取设置 `radial_center_size_dp` 48–160、默认 96 dp，不超过安全区短边 45%；应用名 16 sp 随图标按比例缩放、限 12–24 sp，最宽 60% 安全区宽度省略），
淡入淡出 120 ms、换项从 0.94 弹回；选中「更多」不显示。开关与大小经 `begin(centerPreview, centerLabel, centerSizeDp)` 每次呼出从 `lastSettings` 传入，不依赖目录快照。

**扇形目录**　`ColorOsAppCatalog` 在 system_server 的单线程执行器里工作，一次刷新分两步发布：
① 只为扇形条目（≤18 项）取名称、图标并塑形，立即发布；② 读全部名称、排好「更多」顺序后发布。面板条目一律用
`PanelIconPlaceholder`，格子进入视野时经 `panelIconLoader` 在独立线程按需取图，结果进 8 MiB `LruCache`（`iconCache`）；
**不再后台补齐全部面板图标**：内置「更多」面板只在侧边栏面板打不开（`Outcome.Fallback`）时才用，面板条目与快照都不持有真图标。
选中「更多」时（`showMorePanel`）先经 `PanelIconLoader.prefetch` 预画第一屏及相邻缓冲行（`PanelVisualMetrics.visibleItemRange`，量不出布局取前 24 项），
排在看得见的请求之后；侧边栏面板正常打开时这批图标留在缓存，下次命中。
扇形应用条目的塑形图标在 `shapedCache`（访问顺序，最多 32 项）。目录按当前前台用户读取（`CurrentUser`，system_server 自身是 0 号用户），
`ACTION_USER_SWITCHED` → `invalidateUser` 丢弃全部名称、图标、预览与侧边栏条目。每次加载开头取一次用户号，索引、最近任务、使用统计、
桌面解析都按它读（`userContext`：`createContextAsUser`，AOSP 的 `getRecentTasks` / `queryUsageStats` 按 context 用户号查询），快照带 `userId`；
协调器只采用、只保存 `userId == currentUserId` 的快照，切换时清空当前快照与待用副本、读新用户的副本，侧边栏目录 `userSwitched` 取消在途读取、清空条目、换新用户副本。
应用列表只在 reloadApps（目录为空）或包广播（`invalidatePackage`）时重读，恢复手势（解锁、横屏 / 游戏模式结束）不再重读；名称与图标按条目缓存，
只丢弃变动的包；切换系统语言（`ACTION_LOCALE_CHANGED` → `invalidateLabels`）丢弃全部名称。字母顺序（`alphabeticalOrder`）按应用列表、`labelEpoch` 与语言缓存，
前台切换不重排；「更多」顺序与上次相同时不重建、不重发面板（`lastPanelTargets`）。增减固定项**不重读应用列表**。禁止回到「每次刷新把全部应用图标画成位图」的做法：
本机 537 个桌面入口，那样增减一项要等数秒、开机后头几次呼出是空扇形。
补位来源 `radial_fill_source`：最近使用（`getRecentTasks`）、最常使用（`UsageStatsManager` 近 7 天前台时长，
排除桌面与本模块，缓存 10 分钟）、不补位（未自定义的内圈留空，默认圆弧仍按最近使用）。
补位跳过当前前台应用：`ModuleEnvironmentState.observeForeground`（回调处在 ATMS 锁内，只投递）→ 协调器
`onForegroundChanged` → `ColorOsAppCatalog.updateForeground` + 刷新；固定项不受影响。扇形全由固定项占满（外圈固定项不少于数量上限，
内圈都已自定义或不补位，见 `radialDependsOnForeground`）时前台切换不刷新，只记 `panelOrderStale`，下次呼出时后台补刷一次供内置面板使用。
「更多」顶部顺序与补位依据一致（最常使用时按常用）；开机后第一次刷新在读全部名称前先发布这批应用，面板不空等。
开机不空扇形：每次发布扇形后（合并 10 s）把扇形条目（名称、塑形图标、角标、分圈数）写 `/data/system/flymefreeform/radial_snapshot_u<用户>.bin`
（`RadialSnapshotStore`，单项 PNG 按位图对象复用、摘要相同不写），协调器 `start()` 读出后、只在决定扇形的设置（`RadialSnapshotStore.Key`：固定项、内圈、
数量上限、圆弧条数、图标形状、补位来源）一致时采用为 `provisional` 快照；真实目录发布即替换，provisional 期间照常刷新。
呼出时扇形为空，目录一发布就经 `updateRadialAppearance` → `replaceEmptyCatalog` 整体补进当前浮层并按手指位置重新判定。
条目相同时 `adoptSameTargets` 整份换上新目录（高清与面板加载器、面板列表、分圈），保留选中与手势；高清图只在条目图标换了时丢弃。
浮层的 `catalog` 存在 State（`referentialEqualityPolicy`）里，换目录界面即重组。内置面板已显示时只在面板为空或顺序相同时换列表（`updatePanelCatalog`）。
内置面板的应用图标只随格子留在组合里：格子离开即从 `panelImages` 移除，滚回时经 `PanelIconLoader.cached` 当场取缓存，浮层不再持有浏览过的全部图标。

**固定项里的侧边栏条目**　工具、快捷方式、特殊应用入口与分身应用由侧边栏进程提供，system_server 只拿显示用的目录，启动仍交回侧边栏原生路由。
链路：`ColorOsEntryCatalogBroker` → `ColorOsSidebarEntryConnection`（provider `bindServiceForTransferDock` 唤醒 → bindService → LIST，连接建立后常驻）
→ 侧边栏 `ColorOsSidebarEntryEndpoint`（每 50 ms 查一次面板就绪）→ `ColorOsSidebarEntries.load` → 逐项 ITEM、最后 COMPLETE；单次 7 s 超时，最多 256 项，图标压到 96 px。
目录 = `EntryBeanHelper.getAllAppListForTool`（工具）+ `getShownShortcuts`（快捷方式、category 11 特殊入口）+ `getShownApps` 中的分身。
分身判定（`SidebarEntryKind.fromNative`）不只看侧边栏的 `isCloned()`：该方法只在 `OplusOSVERSION >= 38` 时问 `OplusMultiAppManager.isMultiAppUserId`，否则只认用户 999；
所以应用条目（type 2、非 category 11）的 `getMutiUserId` 有效且不同于当前用户也算分身，再用 `LauncherApps.getActivityList(包, 该用户)` 核对确有入口
（activity 不符时取该用户下该包第一个入口，与侧边栏 `findLauncherActivityInfo` 一致），查不到就不进目录。
启动：EXECUTE 时工作线程 `resolve` 重新定位 bean，主线程调一次 `PageRoutUtils.startSysTool / startShortcut / startNormalApp`；`startNormalApp` 按 bean 的 `getMutiUserId` 启动，不依赖 `isCloned`。
重启后不空等：每次读到完整目录写一份 `/data/system/flymefreeform/sidebar_catalog_u<用户>.bin`（`SidebarCatalogStore`，AtomicFile，内容相同不写），
`start()` 先显示副本、真实目录到达后整体替换；副本只用于显示。保存前先与上次写入或读出的条目逐项比对（名称、包名、`Bitmap.sameAs`），相同则不编码 PNG。
读取失败按 2 s、5 s 各重试一次，主动取消不重试。包变化（`packageChanged`）不清空目录：卸载的包立即移除其条目，其余照常显示，
有侧边栏固定项时后台重读（读取途中再有变化，完成后补读一次）。设置页请求 500 ms 内过密时回 `BUSY`，客户端 600 ms 后用同一 ID 重发；
设置页暂停不取消进行中的请求，恢复时合并。侧边栏端 `ColorOsSidebarEntries` 每次请求新建（bean 只在本次有效），反射元数据按 ClassLoader 单格复用。
热连接在使用期间与锁屏、横屏、游戏模式暂停时保留；模块关闭时 `entryCatalog.release()` 解除绑定。侧边栏进程读的用户设置 XML 按本进程用户号取 `/data/user/<用户>/…`。

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

**面板工具小窗**　付款码、扫一扫这类工具由侧边栏自建 Intent，经 `AppPlatformUtils` 的 startActivityAsUser 包装
（混淆名随版本变：16.11.3 为 `m(Intent, Bundle, UserHandle)`，16.14 / 17.9 为 `n(...)`，17.11.2 为 `n(Intent, Bundle, UserHandle, ze.l)`
另带 synthetic 桥 `o`；按「静态、非 synthetic、前三参为 Intent/Bundle/UserHandle、唯一」取方法，不按名字）
交给系统 uid 代发，界面进程无法重发（目标是第三方未导出 Activity）。做法是点击前登记目标包，
在代发瞬间把 zoom 参数**就地并入系统已建好的 Bundle**，启动链路不变。

**通知点击**　钩 SystemUI 的 `OplusStatusBarNotificationActivityStarterExImpl.setNotificationTypeStarterLog`
（类名带 `Impl`）：依次移除横幅 → 剥离 `android:activity.remoteTransition` → 写窗口参数 →
非横幅时调 `collapseShadeForActivityStart()`。横幅实例早于钩子创建，需同时钩 `HeadsUpManagerImpl.showNotification` 取 thisObject。

**通知目标已在前台**（`NotifBundleHook.targetInForeground`）　点击通知（面板与横幅同一入口）时，
若通知所属应用正全屏显示在前台，就按系统默认全屏跳转，不开小窗。所属包取 `StatusBarNotification` 的发布包与 contentIntent 的创建包；
前台取 SystemUI 内 `getRunningTasks(6)` 第一个可见、窗口模式 1（全屏）的任务的顶层包，小窗（100）、分屏等任务跳过，桌面算普通全屏任务。
成立时 `rewriteBundle` 不写小窗参数、不移横幅、不剥转场，只写 AOSP 键 `android.activity.windowingMode=1`（`ZoomLaunchSpec.markFullscreen`）；
system_server 见 SystemUI 发起且启动参数写明全屏（`LaunchSnapshot.optionsFullscreen`）即不改写，见第四节第 1 段。查询失败按不成立处理，保持小窗。
通知发布包在 `LaunchRules.SKIP_TARGETS`（系统界面、设置、权限控制器）内时同样不改写，与其他入口一致（原先只跳过 systemui / android）。
SystemUI 侧不接管 `onNotificationClicked` 一类点击入口（见 `SystemUiHookInstaller` 注释与第九节第 7 条）；原未安装的 `NotificationZoomSourceHook` 已删除。

**双击横条关闭**　「小窗关闭」第 4 项（`OutsideTapCloseMode.CaptionDoubleTap`，存储值 3），与窗外单击 / 双击互斥。
钩 `FlexibleTaskCaptionView.onDoubleTap`：原方法只处理迷你小窗（退出迷你态或与前台交换），普通小窗返回 false；
只在原方法返回 false、且横条所属任务是普通小窗（柔性状态 1）时调 `exitFlexibleTask(task, true, 0, 0)` 关闭该任务。
该模式下 `closesOnOutsideTap` 为假，横条触控区不扩到窗外，避免双击窗外也落到横条上被当成关闭（参考 2237742261/FlymeFreeform 的同类实现）。

**设置页应用列表**　`LauncherAppRepository` 只保存组件与名称，图标是 `AppIconPlaceholder`；行进入视野时经 `LocalAppIcons`（`AppIconLoader`，
后请求先画、离开即取消，结果已按外形处理，`LruCache` 12 MiB）按需加载。包变化时 `AppIconLoader.version` 递增（广播时一次、重读入口后再一次），
可见行以它为键重新取图，作废前开始的任务既不进缓存也不回调。包变化只重读该包；切换语言丢弃全部名称；状态 Loading / Ready / Failed，
失败时选择页显示重试，读完为空显示「没有可添加的应用」。方形模板 `squareMask` 由仓库在全量读取后取前 30 个应用。侧边栏条目的塑形结果放进 `ShapedIconCache`（4 MiB，按源图身份）。
设置页页面栈：每次进出页面经 `NavStackStore` 写进本应用 SharedPreferences（`ui_state` / `back_stack`，路由名逗号分隔），冷启动没有保存状态（后台冻结 / 清理工具强行停止或移除任务）时据此恢复，有保存状态时以 rememberSaveable 为准；首页按返回退出时栈只剩首页。
排除列表选择页逐行懒加载（`CardSegment` 用 `squircleSurface` 分角给半径，与 miuix Card 同一种平滑圆角）。搜索（`AppSearch`）去首尾空格，名称、包名包含即命中；查询为纯字母且名称含汉字时另按 ICU 全拼 / 首字母匹配，后台过滤。

**设置广播**　设置页每次改设置都发 `ACTION_PINNED_TARGETS_CHANGED`，带完整快照（`SettingsTransfer.EXTRA_SETTINGS`，
由 `ModuleSettingsSnapshot.writeTo/readFrom` 编解码，新增设置无需另登记），发往 android、smartsidebar、systemui、launcher 四个进程，
接收端统一走 `ProcessConfiguration.applySettingsBroadcast`；旧固定项字段仍一并发送，给尚未重启、仍跑旧代码的 Hook 进程用。
SystemUI 在 Application.onCreate、桌面在 Application.onCreate 登记接收端，首次触摸保留异步兜底。目的：模块重装后远程配置回调失效，靠广播让所有设置不重启即生效。

**窗外关闭 / 快速上滑 / 数量上限**　分别钩 `FlexibleTaskController.exitFlexibleTask`、
普通小窗底部横条上滑入口、`FlexibleWindowManagerService.getMaxWinNum`；应用准入钩三类共 9 个
`isSupportFlexibleWindow`（FlexibleWindowManagerService 2、FlexibleTaskController 3、FlexibleWindowUtils 3）。
数量上限取 0 =「跟随系统」时 `getMaxWinNum` 直接 `proceed`，不覆盖。实测装了 LuckyTool（多浮窗上限 200）时生效的是 LuckyTool 的值，即使本模块设为 5 也压不住，原因未核实（可能走的不是同一条判定路径，或钩子顺序在外层）。

**暂停条件**　`ModuleEnvironmentState` 维护锁屏、横竖屏、游戏模式、前台包；前台包来自
`ActivityTaskManagerService.setLastResumedActivityUncheckLocked`。横屏与游戏模式只暂停扇形呼出，
小窗能力用不含暂停的 `isModuleActive()` 判定。

**框架服务恢复**　FrameworkConnectionRepository 在后台串行队列中按服务 Binder 身份去重，优先最新绑定；监听 linkToDeath，并在 reconcile 与写入前剔除死亡服务、清理活动连接与缓存、激活剩余候选。Service 102 未公开 Binder，反射 mService 字段由 keep 规则保护；不可反射时只读 apiVersion，只有 DeadObjectException 视为死亡，其他错误保留原诊断；多个不同有效 Binder 仍禁止写入。

**starter 锁安全**　副作用经 StarterSideEffects 离开 starter 线程；面板即时收起改为主线程队首，延迟收起仍为 60 ms。StarterLockProbe 从 mService.mGlobalLock 读取弱引用，执行时以 Thread.holdsLock 检测；未知只报一次，违规同名每 10 秒最多一次，不抛异常。同步 zoom 启动与窗口状态查询为既有厂商白名单。PM 查询改为专用工作线程缓存（含包变化失效），冷缓存按未知放行；控制中心来源在当前 execute / resolveActivity 阶段以探针确认未持 WM 锁后读取，仅在本次启动内传递，持锁且没有本次观察时保守放行；锁屏只读 ModuleEnvironmentState 的广播缓存，不在钩子里刷新厂商 Keyguard 实现。
PM 缓存（`StarterQueryCache`）开机后的冷窗口靠两处缩短：预热先单查 SystemUI 与桌面，再读全量列表；每次读启动快照时 `seedFromLaunch` 用 Request 已解析的
`ActivityInfo.applicationInfo` 补入目标「是否第三方 + uid」，发起方为 SystemUI / 桌面且 callingUid == realCallingUid 时记下其 uid（PM 结果到位后以 PM 为准）。uid 一律按 appId（去用户号）保存，
`LaunchDecider.sameApp` 把启动里的完整 uid 取 appId 后再比（非主用户下桌面是 u10_aXXX）；uid 原值不改。

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

## 四、启动判定（`reasonFor`）

判定已迁入 `hook/launch/decision/LaunchDecider`，无 android.* 依赖；输入为 LaunchSnapshot / LaunchEnvironment，状态为 LaunchMemory。判定只读状态，返回的日志及返回指纹更新由执行层按顺序应用；接管成功后才记链接、目标和跳数。旧函数与新函数一一对应见 `docs/starter-decision-migration.md`，InterAppLaunchTracker 保留为共享同一份内存的薄包装。

全部启动改写受单一开关 `zoomLaunch` 控制：`reasonFor` 首行读它，关闭即返回 null，任何入口都不改写。
八段判定，先命中先返回：

1. 来源标记（控制中心磁贴；通知来源 extra 目前无写入方）；SystemUI 发起且启动参数写明全屏（通知目标已在前台）时直接放行全屏，必须先于第 3 段；抢占路径的 SystemUI 分支同样不接管
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

**执行层**（`ActivityStarterZoomInjector`）　原启动参数只用于判定：`Request.activityOptions` 是 SafeActivityOptions、没有 `toBundle()`，
`optionsBundle` 先试 toBundle，不行按 AOSP `mergeActivityOptions` 顺序合并 `mOriginalOptions`、`mCallerOptions`。原参数已带小窗参数时走 `ZOOM_NATIVE_CHANNEL_<reason>`，不再覆盖。
注入与延迟重开只写纯小窗参数（`ZoomLaunchSpec.bundle(null)`），**不并入原参数**：原参数可能带远程动画、launchTaskId 等受限字段，以 callingUid 重新包装会在 SafeActivityOptions 权限检查抛 SecurityException。
写入用 `setActivityOptions(Bundle, pid, uid)`，不可用时以 `SafeActivityOptions.fromBundle(Bundle, pid, uid)` 写 `Request.activityOptions`；两种都不可用不接管（`starter.mOptions` 在钩子时刻为 null、之后会被覆盖，写它无效）。

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
- 缓存上限：中央预览补位项 32 MiB、固定项不设上限，面板图标 8 MiB，扇形塑形图标 32 项（system_server）；设置页应用图标 12 MiB、塑形图标 4 MiB（App 进程）
- 侧边栏目录请求节流 500 ms（回 `BUSY`），客户端重发间隔 600 ms
- 双击关窗阈值 `ViewConfiguration.getDoubleTapTimeout()` = 300 ms
- 面板数据原地改写同一个 ArrayList、不换对象；原厂源头重灌（见第三节）返回后才换回「收藏 + 最近」并 notify

## 六、构建、签名与发布

**签名密钥**　项目根目录 `release.keystore`，维护者 ZaiLinn 自己的密钥（2026-11-08 生成，别名 `flymefreeform`，
证书 `CN=ZaiLinn, O=FlymeFreeform, C=CN`，权限 600，被 `.gitignore` 的 `*.keystore` 排除）。
- 必须保留并另行备份；丢失后已装用户无法覆盖升级
- 口令不写进任何文件、不进对话：脚本依次读环境变量 `FLYMEFREEFORM_KS_PASS`、钥匙串条目 `flymefreeform-release`
  （`security add-generic-password -a "$USER" -s flymefreeform-release -w` 添加一次）、交互输入，经 `env:` 传给 apksigner
- 与 Hoerire 官方版、调试签名版互不能覆盖安装；从它们切换过来需先卸载（设置会丢）

**版本号**（用户 2026-11-08 定）：每次代码更新 `flymeFreeformVersionCode` +1；每构建一次 APK，`flymeFreeformVersionName` 末位 +1（1.3.7 → 1.3.8）；末位只用 0–9，到 9 后下一次构建进位到中间一位、末位归 0（1.5.9 → 1.6.0；用户 2026-11-09 定，1.4.10 之后直接构建 1.5.0），
构建前改好，产物名随之变化，同一版本名不出两个包。两项都在 `gradle.properties`。
versionCode 格式为 `yyyyMMdd` + 两位当日序号，跨日时日期取当天、序号从 01 起（2026110852 → 2026110901，仍单调递增）。

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
- `local-release.sh`、`restart-gate.sh` 列入 `.gitignore`，不随仓库分发
- 关于页只保留一个「项目地址」→ `ZaiLinn/FlymeFreeform`；上游地址与致谢在 README

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
| starter 锁与副作用（WARN、常开） | `STARTER_LOCK_PROBE_UNAVAILABLE`（读不到 WM 锁）、`STARTER_LOCK_VIOLATION_<name>`（在 WM 锁内执行副作用，10 秒节流）、`STARTER_EFFECT_FAILED_<name>`（投递任务运行时异常） |
| starter 缓存预热 | `STARTER_QUERY_WARMUP_READY_PRIORITY_<ms>MS_APPS_<ms>MS_<n>_OWNERS_<ms>MS`（INFO，自首次触发预热起算） |
| 原参数已是小窗 | `ZOOM_NATIVE_CHANNEL_<reason>`（不覆盖） |
| 面板工具 | `PANEL_TOOL_HANDLED_<alias>` 后紧跟 `PANEL_TOOL_ZOOM_<包名>`；安装时 `PANEL_TOOL_ZOOM_INSTALLED_<方法名>_<参数个数>`，找不到为 `PANEL_TOOL_ZOOM_UNAVAILABLE`；排除列表内 `PANEL_TOOL_ZOOM_SKIPPED_EXCLUDED_<包>` |
| 面板点击 | `PANEL_CLICK` 的 index / alias / aligned / size / query |
| 通知 | `NOTIF_BUNDLE_ZOOM` 的 dropped；钩子缺失看 `NOTIF_BUNDLE_HOOKED_*` |
| 侧边栏目录耗时 | system_server：`SIDEBAR_ENTRY_LIST_READY_cold<0\|1>_prepare<ms>_bind<ms>_first<ms>_total<ms>_n<n>`（INFO，各段相对发起时刻，-1 为未到达；超时为 `SIDEBAR_ENTRY_TIMEOUT_LIST_` 加同样各段）；侧边栏进程：`SIDEBAR_ENTRIES_LIST_READY_wait<ms>_load<ms>_send<ms>_n<n>`（等面板就绪 / 读目录含画图标 / 逐项发送）；重试 `ENTRY_CATALOG_RETRY_<次>_IN_<ms>MS`；本地副本 `SIDEBAR_CATALOG_CACHE_READY_<n>_<ms>MS`，异常 `SIDEBAR_CATALOG_CACHE_*_FAILED` / `_INVALID`；扇形副本 `RADIAL_SNAPSHOT_READY_<n>_<ms>MS`、采用 `SYSTEM_RADIAL_SNAPSHOT_ADOPTED_<n>`（详细日志），异常 `RADIAL_SNAPSHOT_*_FAILED` / `_INVALID`；语言 / 切换用户广播注册失败 `LOCALE_OBSERVER_UNAVAILABLE` / `USER_SWITCH_OBSERVER_UNAVAILABLE` |
| 通知目标已在前台 | SystemUI：`NOTIF_FOREGROUND <前台包> match=<true\|false>`、`NOTIF_BUNDLE_FOREGROUND_FULLSCREEN <包>`；system_server：`ZOOM_SKIP_SYSTEMUI_FULLSCREEN_<包>`；查询异常 `NOTIF_FOREGROUND_QUERY_FAILED`（WARN）；系统包跳过 `NOTIF_BUNDLE_SKIPPED_SYSTEM <包>` |
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

0. starter 钩子运行时可能持有 WindowManagerGlobalLock；不得同步发广播或调用可能拿 AMS 锁的服务。副作用统一经 StarterSideEffects 投递，新增同步调用必须在 KDoc 说明锁安全依据，构建前由 `scripts/check-starter-safety.sh` 检查（Gradle 编译前置条件，CI 同时执行脚本回归与单测）。
1. 结论要能指到一行日志或一段字节码，反编译优先于猜测（`dexsql` 查 services.jar / oplus-services.jar / SystemUI.apk；侧边栏 APK 用 build-tools 的 `dexdump -d`）
2. 重启前先把话说完：改了什么、装的哪个包、sha256、验证什么、看哪几条日志；命令用 `setsid` 脱离进程组
3. 能只重启一个进程就不重启设备
4. 注释与提交信息第三人称陈述，只写做什么、为什么、约束是什么
5. 不在手机上跑 Gradle；不跨进程用静态字段通信；不按固定次数 `postDelayed` 撞系统数据
6. 面板分组标题不能按文本或先后改名（标题行文案由 viewType 布局决定，两段都显示「工具 / Tools」，且随系统语言变化）：注入器按对象身份登记标题角色（`FavoriteGroupInjector.titleRole`），`title_bind` 钩子在 `onBindViewHolder` 返回后按角色改写可见标题，文案随语言取「收藏/最近」或「Favorites/Recent」；不能读 `UserPanelView.getRecentData()`（占位内容）
7. 不在 SystemUI 侧改写通知点击入口（回调不稳定，命中失败会丢弃原始事件）；通知相关改动只在 `NotifBundleHook.rewriteBundle` 写参数
8. 扇形目录不得在刷新路径上加载全部应用图标；新增补位来源或排序时，只能在「挑选条目」一步做，图标仍按条目缓存
9. 侧边栏 / 桌面的混淆名每个版本都可能变：按签名、字符串或调用关系找（DexKit 或签名匹配），不按单字母名写死；新版本先对照 `docs/sidebar-dex-resolution.md` 逐项核对
10. `decision/` 包保持纯 Kotlin、无 android.* 依赖，判定改动必须带单测（`LaunchDeciderTest`）
11. 版本号：每次代码更新 versionCode +1，构建前 versionName 末位 +1，末位到 9 后进位（1.5.9 → 1.6.0，见第六节）
12. 工程纪律：构建输出落 `out/`、临时文件落 `tmp/`、`release.keystore` 必须保留、交付前 `tar -czf` 打包放存储根目录

## 十、当前状态

更新时间：2026-11-09（只改源码；未查询或操作设备）

| 项 | 值 |
|---|---|
| 源码 | 1.5.0 / versionCode 2026110916；`main` = `origin/main`，代码 `f7e62d8`（0902–0915）与 `da170ee`（0916 设置页记住上次页面），版本提交 `f6601ad`（标签 v1.5.0）；之后只有文档提交，无未提交代码 |
| 本版内容 | 1.5.0 = 2026110902–0915（`f7e62d8`：通知前台全屏、启动原参数、starter 冷窗口、侧边栏与扇形本地副本、分身识别、多用户、减少重复工作与占用、设置页按需加载与拼音搜索、1.4.9 闪退修复）+ 2026110916（设置页记住上次页面）。逐条详情见 `docs/dev-history.md` |
| 测试 | 2026110916：单测 275 项（0 失败、3 跳过），`minifyReleaseWithR8`、lintVitalRelease 通过，R8 产物 dex 无读取不存在的静态字段；未构建 APK、未在设备验证。1.4.10 发布前 `local-release.sh` 通过（272 项）。回归范围见 `docs/regression-2026-11-09.md` |
| 构建 | 1.5.0 / 2026110916，`out/FlymeFreeform-v1.5.0.apk`（2228527 字节），sha256 `9a8361920fc5941cce7762bf0dd2214c97514d493210a02647b20dba6c43a89e`，release.keystore 签名；`local-release.sh` 通过（单测 275 项 0 失败 3 跳过），包内 dex 无读取不存在的静态字段；已发布为 GitHub Release v1.5.0，未安装 |
| 上一包 | 1.4.9 / 2026110914，`out/FlymeFreeform-v1.4.9.apk`（2228527 字节），sha256 `21f38bc48df2ccec2715ec0559fbecd78772f63fabe8945736102f99140ad8f3`，release.keystore 签名；用户已自行装机，设置页打开即闪退（`NoSuchFieldError` `$stable`，见 2026110915），模块功能正常 |
| 设备 | 1.4.10 / 2026110915（22:53 覆盖安装，设置页冷启动正常）。用户装 1.4.9 后已自行重启，系统部分运行的是 1.4.9 的钩子，与 1.4.10 相同（1.4.10 只改设置页），不需再重启 |
| 设备另有 | 旧包 `io.github.hoerire.flymefreeform`（2026-11-08 记录仍在，未确认是否已卸载） |
| GitHub | `ZaiLinn/FlymeFreeform` main 已推送；Release / 标签 v1.3.6、v1.3.7、v1.3.10、v1.4.8、v1.5.0（2026-11-09 发布，Latest，资产 `FlymeFreeform-v1.5.0.apk`，GitHub 记录的 sha256 与本地一致）；v1.4.10 Release 与标签按用户要求已删除；LSPosed 模块仓库未建 |
| 侧边栏样本 | 本地有 16.11.3、17.11.2 两个 APK（项目根目录，未入库）；`docs/sidebar-dex-resolution.md` 的回归样本为 16.14.0、16.14.6、17.9.2、17.9.8，尚未加入这两个 |

**待验证**（1.4.10 装机后；先确认设置页能打开、各页不闪退，按进程；2026110914 新增回归与真机边界见 `docs/regression-2026-11-09.md`）

- system_server（必须重启 system_server，会连带 SystemUI）：
  - 通知：前台全屏应用点自己的通知 → 全屏打开，转场与横幅收起与原生一致；桌面或其他应用在前台、或该应用正在小窗时仍小窗；
    `NOTIF_FOREGROUND` 的前台包正确（为 null 或总是桌面说明 SystemUI 取不到任务列表）；设置类通知全屏；
    `ZOOM_SKIP_SYSTEMUI_FULLSCREEN_` 不应出现在非前台场景（出现说明 ColorOS 自己写了 windowingMode=1）
  - 启动：`ZOOM_NATIVE_CHANNEL_` 是否出现；通知、桌面卡片、快捷项、链接小窗与之前一致
  - 缓存：`STARTER_QUERY_WARMUP_READY_` 三段耗时；开机后立即点链接 / 通知是否仍误判全屏
  - 侧边栏目录：首次解锁取到目录后写出副本（`ls -l /data/system/flymefreeform`）；再重启后解锁前已有 `SIDEBAR_CATALOG_CACHE_READY_`，解锁后工具与分身立即可见；
    副本显示期间点工具能否打开；两行耗时日志各段数值；SELinux 拒写时见 `SIDEBAR_CATALOG_CACHE_WRITE_FAILED`
  - 2026110907：装 / 更新一个无关应用，扇形里固定的工具不消失；卸载应用后其分身条目消失；切换系统语言后扇形与「更多」名称、排序更新；
    前台切换后 `SYSTEM_CATALOG_PANEL_ORDER_` 不再每次出现；中央预览同一项不重复绘制（`SYSTEM_PREVIEW_PREFETCH_` 计数），160 dp 时只预取前 8 项
  - 2026110909–0910：中央预览开启、固定项填满时，每项高清图只画一次、反复呼出不重画；内置「更多」面板（侧边栏打不开时）图标随滚动出现；
    `dumpsys meminfo system_server` 与改动前对比；固定项占满时切换前台不再出现 `SYSTEM_CATALOG_RADIAL_`；关闭模块后 `dumpsys activity services com.coloros.smartsidebar` 不再有本模块绑定；
    小窗外点击关闭照常（新开小窗后区域就绪），功能关闭时新开小窗不再刷新触摸区域；系统分身用户下扇形列出该用户的应用并在该用户下打开
  - 2026110911：首次装机解锁后约 10 s 出现 `radial_snapshot_u0.bin`；再重启 system_server，解锁前已有 `RADIAL_SNAPSHOT_READY_`，解锁后立即呼出扇形不为空，点固定项能打开；
    侧边栏面板打不开时内置「更多」面板第一屏图标随面板出现即有（2026110912）；
    2026110913：副本显示期间呼出、真实目录到达后（手指不松）中央预览变高清、点「更多」内置面板有列表；内置面板滚到底再回顶部，`dumpsys meminfo system_server` 不随应用数涨；
    分身用户下补位用该用户的最近 / 常用，切换用户后扇形不出现旧用户的应用，新用户下桌面快捷项、通知仍进小窗；
    改固定项后重启，副本不被采用（无 `SYSTEM_RADIAL_SNAPSHOT_ADOPTED_`）；删掉副本、解锁后立刻呼出，扇形在手指未松开时补上条目
- 设置页（App 进程，覆盖安装即生效）：应用列表先出名称、图标随滚动出现；快速离开再进入「扇形应用」页不再出现 7 s 读取中；
  搜索「weixin」「wx」「 微信 」都能找到微信；停留在列表页时更新一个可见的应用，图标换新；排除列表选择页滑动流畅；切换语言后名称更新
- 侧边栏进程（`pkill -f com.coloros.smartsidebar`）：
  - 17.11.2 上 `PANEL_TOOL_ZOOM_INSTALLED_n_4`、16.11.3 上 `_m_3`，付款码 / 扫一扫进小窗；排除列表内的应用记 `PANEL_TOOL_ZOOM_SKIPPED_EXCLUDED_`
  - 分身固定项能列出并打开分身而不是本体；出问题的侧边栏版本上分身是否出现（根因未在真机确认）
- 1.4.8 本身：4 个进程 `MODULE_CONFIG_READY`；改「更多」颜色后下次呼出即变色；开关中央预览后立即预取
- 更早登记、未确认是否已验的回归清单见 `docs/audit-fixes-2026-11-08.md` 与 `docs/dev-history.md` 末尾旧状态表「未在设备验证」

**下一步**

1. 按上面「待验证」清单在设备上验收（钩子已生效，用户自行重启过）；2026110916 装机后：进二级 / 三级页回桌面，`am force-stop` 或等后台冻结清理后再打开，应回到原页面；在首页按返回退出后再开是首页
2. 把 16.11.3、17.11.2 加进 `docs/sidebar-dex-resolution.md` 回归样本
3. 卸载旧包 `io.github.hoerire.flymefreeform`
4. 待评估：滑选时显示应用名

## 十一、经验与坑

长期有效的结论，原始记录与证据路径见 `docs/dev-history.md`「坑（原文）」。

**system_server 与 starter**
- 1.4.0 死锁（看门狗重启）：starter 钩子在持 `WindowManagerGlobalLock` 时同步 `sendBroadcast` 等 AMS 锁，另一线程 `attachApplication` 持 AMS 锁等 WM 锁，AB-BA。starter 钩子里任何广播 / 拿 AMS 锁的调用都不能同步执行（第九节第 0 条）。traces 在 `tmp/traces_SystemServer_WDT_1.4.0_deadlock.txt`
- 应用间跳转：从 Activity 调 `startActivity` 也带 `resultTo`（requestCode -1），只有 requestCode >= 0 才算等结果；往后台发起的启动注入小窗参数会被 `adjustOptionsForFlexibleTask` 以 reason 8/9 拒绝，只能改走 zoom 入口抢占
- X 一类以 NEW_TASK|CLEAR_TASK 重启自身的应用：已在小窗时再点链接，`startZoomWindow` 只把旧任务提到前台、链接不送达；做法是关旧小窗后复制 Request 重开
- `killall system_server` 不会新开 `modules_*.log`，重启后的记录追加在原文件；等待就绪按时间过滤，不能按「最新文件里有 COORDINATOR_READY」判断
- 装了 LuckyTool 时多浮窗上限以 LuckyTool 为准，本模块的「小窗数量」压不住它

**设置与广播**
- 广播接收端核对 `sentFromUid`，发送端不共享身份时它是 INVALID_UID，推送被丢弃而 `dumpsys` 仍显示 DELIVERED；必须 `BroadcastOptions.setShareIdentityEnabled(true)`
- 模块重装后 Hook 进程里的远程配置回调不再触发，设置只能靠全量广播热生效；覆盖安装后按改动最大的进程重启
- 重启后设置页报「检测到多个框架服务」：lspd 可能把同一服务递两次，现按底层 Binder 去重并清理死亡服务

**扇形与目录**
- 扇形视图里只跟显示有关的设置（如「更多」颜色）不能读 `catalog.settings`：目录快照只在 `AppCatalogSnapshot.matches()` 列出的字段变化时重载。显示类设置由协调器在 `begin()` 时从 `lastSettings` 传入；影响挑选或预取的设置加进 `matches()`
- 角落空白区不能以「最内一圈图标内沿」为界，两圈时第二圈只剩约 50 基准 dp 宽
- ColorOS 的 `buildAdaptiveIconDrawable` 不理会外形路径，应用图标形状只能在模块里自己裁；系统图标带透明留白，裁切前先裁到本体
- 侧边栏工具的图标资源可能不属于侧边栏自身；目录读取里任何单项失败都必须就地兜底，不能让整个目录作废
- 添加应用页的目录读取与本地顺序只能随 `settingsEditable` / `issue` 变化，随含 isUpdating 的 `canChangeSettings` 变化会反复重发请求、勾选闪动、连点互相覆盖；broker 原先对 500 ms 内的重复请求不回复，客户端白等 7 s 超时，现回 `BUSY` 让客户端重发，且页面暂停不取消请求。满队列（回调已有 2 个）仍立即回失败

**代码与流程**
- R8 会删掉 Compose 的 `$stable` 字段而留下调用处的运行时读取（1.4.9：`inner class AppIconLoader` 推断不出稳定性，设置页打开即 `NoSuchFieldError`；debug 不混淆，测试与编译都发现不了）。给 Composable 传的类型推断不出稳定性时标 `@Stable` / `@Immutable`；keep 规则已保留本模块的 `$stable`。改 Compose 参数类型后，查 R8 产物 dex：`dexdump -d` 里每个 `sget …$stable` 的字段都要存在
- `ConcurrentHashMap` 上用 Kotlin `in` 调的是 `contains` = `containsValue`，必须写 `containsKey`
- Compose 里「换位后先回到旧位置再动画」的补偿必须同帧生效（`SideEffect` 写状态、绘制阶段读），放进 `LaunchedEffect` 会闪一帧
- 一次打包混入多个改动出问题无法定位，一包一改
- 直接跑 Gradle 要 `ANDROID_HOME=$HOME/Library/Android/sdk sh ./gradlew …`：仓库没有 `local.properties`，`gradlew` 没有执行位；输出再经 grep 过滤时失败会被吞掉，看退出码
- system_server 里 `Process.myUserHandle()` / `UserHandle.myUserId()` 永远是 0 号用户；涉及前台用户的目录、启动、组件校验都要取 `CurrentUser`
- 缓存上限要同时管住持有者：只把缓存换成 LRU，若面板条目、快照、浮层仍引用位图，内存照样不释放，被淘汰的还会被重画
- 目录类缓存清空后再重读，读取失败时界面会一直缺项：失效时保留旧内容、只删确定失效的条目，读到新结果再整体替换
- 写在没安装的钩子里的逻辑不会生效（2026110901 的前台判定写进了从未安装的 `NotificationZoomSourceHook`）：改之前先确认入口真的装上、真的在跑
- 结论前先核字节码：2026110906 曾以为侧边栏按 `isCloned` 选启动用户并加了覆盖钩子，复审 `startNormalApp` 发现按 `getMutiUserId` 启动，钩子已删
