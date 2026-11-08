# 计划：启动钩子锁安全 + 启动判定可单测

本文件是交给执行者（Codex）的完整施工计划，分两部分：

- **A：锁安全**。保证 `ActivityStarter` 钩子里不会同步执行可能拿 AMS 锁或其他跨模块锁的操作，并在运行时能发现违规。
- **B：判定纯函数化**。把启动判定从反射代码里拆出来，变成可在 JVM 单测里直接跑的纯 Kotlin 逻辑，并用真实日志序列写回归测试。

两部分都**不改变任何对用户可见的行为**，日志码保持不变（第 B6 节列出清单）。

---

## 0. 开工前必读与硬约束

1. 先读 `CONSTRAINTS.md` 全文，尤其是「〇、开工闸门」「九、改动守则」「十、当前状态」和「本轮的坑」里关于 1.4.0 死锁的条目。
2. 工作副本可能有大量未提交改动。开工前先确认 `git status`；若用户没有另行说明，**先请用户提交或允许你按功能分组提交**，再在新分支上施工（建议分支名 `refactor/starter-hook`）。
3. 只允许运行：
   - `export ANDROID_HOME=$HOME/Library/Android/sdk && sh gradlew -q :app:compileReleaseKotlin :app:testDebugUnitTest`
   - 不要运行 `local-release.sh`、不要 `adb install`、不要重启任何设备进程；这些需要用户亲口同意（见 CONSTRAINTS.md 规则 0）。
   - `./gradlew` 没有执行位，用 `sh gradlew`；单测任务是 `testDebugUnitTest`，不是 release。
4. 代码注释、KDoc、提交信息用中文，正常行文。
5. 每完成一次代码改动：`gradle.properties` 里 `flymeFreeformVersionCode` +1（不改 `flymeFreeformVersionName`，那是构建 APK 时才加）；同时更新 `CONSTRAINTS.md` 第十节（版本、未打包改动、未在设备验证）以及受影响的章节。
6. 设备 shell 里的 `grep` 不支持 BRE 的 `\|`，排障命令一律用 `grep -E`（与本计划无直接关系，写排障说明时注意）。

---

## A. 锁安全

### A1. 背景：为什么要做

`hook/launch/ActivityStarterZoomInjector.kt` 钩住 `ActivityStarter.execute` 与 `ActivityStarter.executeRequest`。这两个方法运行时，当前线程**已持有 `WindowManagerGlobalLock`**（ATMS 的 `mGlobalLock`）。

1.4.0 的死锁（trace 存于 `tmp/traces_SystemServer_WDT_1.4.0_deadlock.txt`）：

- 线程 A 在 starter 钩子里持 WM 锁，同步调用 `ContextImpl.sendBroadcast`，等 AMS 锁；
- 线程 B 在 `attachApplication` 里持 AMS 锁，等 WM 锁；
- AB-BA 死锁，看门狗重启 system_server。

现状是靠人记住「钩子里别同步做这些事」，已经出过一次事故，且后续又加了关窗重开等副作用。目标是把规则做成代码结构 + 运行时检测 + 构建前静态检查三道防线。

### A2. 盘点：钩子路径上的所有调用

执行者第一步：逐个确认下表，必要时补充遗漏项。「钩子路径」指从 `install()` 里 `intercept { }` 进入、到返回之前同步执行的所有代码，包括 `trace`、`preemptWithZoomWindow`、`inject`、`reasonFor` 及其调用的一切函数。

| 调用 | 位置（函数） | 现状 | 处理 |
| --- | --- | --- | --- |
| `systemContext().sendBroadcast(...)` | `requestBannerCollapse` | 已投递到主线程 | 改走 A3 的统一入口 |
| `StatusBarManager.collapsePanels()` 反射 | `collapseShadeImmediately` | **钩子里同步调用** | 需评估（见下） |
| `StatusBarManager.collapsePanels()` 反射 | `collapseShade` | 已 `postDelayed` | 改走统一入口 |
| `OplusZoomWindowManager.startZoomWindow` | `preemptWithZoomWindow` | 钩子里同步调用（持 WM 锁、可重入） | 保留，理由见下 |
| `OplusZoomWindowManager.startZoomWindow` | `reopenInZoom` 的延迟任务 | 已在主线程 | 改走统一入口 |
| `OplusZoomWindowManager.getCurrentZoomWindowState` | `isShowingInZoom` | 钩子里同步调用 | 保留：已反编译核实只拿 WM 锁（可重入）与 `OplusFlexibleInfoManager` 内部叶子锁 |
| `ZoomTaskCloser.closer`（内部 `exitFlexibleTask`） | `reopenInZoom` | 已在主线程 | 改走统一入口 |
| `packageManager.getApplicationInfo / resolveActivity / getPackagesForUid` | `isThirdPartyApplication`、`resolveImplicitTarget`、`systemUiUid`、`launcherUid`、`owningPackageOf` | 钩子里同步调用 | 需评估；至少把 uid 类结果缓存 |
| `KeyguardManager.isKeyguardLocked` | `ModuleEnvironmentState.isDeviceUnlocked(refreshKeyguard = true)` → `refreshKeyguardState` | 钩子里同步调用 | 需评估 |
| `InterAppLaunchTracker.record / consumeIfReturning` | 多处 | 纯内存 | 无需处理 |
| `ModuleLog.debug` / `module.log` | 多处 | 写 LSPosed 日志 | 确认不拿 AMS 锁（应为本地 IO / binder 到 lspd）；记录结论即可 |
| `FlexibleWindowLimitHook` 的钩子体 | `hook/launch/FlexibleWindowLimitHook.kt` | 同样可能在 WM 锁内被调用 | 一并盘点 |

**保留 `startZoomWindow` 同步调用的理由**：该调用走 ATMS 的同一把 WM 锁（可重入），且已实测稳定（1.4.0 死锁的元凶是广播，不是它）。但仍要在 A4 的运行时检测里把它列为「已知可在 WM 锁内调用」的白名单项，并在 KDoc 里写明依据。

**「需评估」项的判定方法**（执行者在本机可做的部分）：

1. 反编译依据：之前已从设备拉取 `/system/framework/oplus-services.jar` 与 `oplus-framework.jar` 做过 dexdump，可以在 `framework.jar` / `services.jar` 中对相应服务实现（`StatusBarManagerService.collapsePanels`、`WindowManagerService.isKeyguardLocked`、`PackageManagerService` 的查询路径）查是否进入 `ActivityManagerService` 的 `synchronized (this)` 或 `mProcLock`。没有设备时，参考 AOSP 对应版本源码（Android 15）。
2. 结论写进对应函数的 KDoc：「可在 WM 锁内调用，因为……」或「不可，已改为投递」。
3. 拿不准的一律视为**不可**：结果能缓存的就缓存（如 `systemUiUid`、`launcherUid` 只在首次或包变化时查询），不能缓存的副作用一律投递。

`collapseShadeImmediately` 特别说明：它的作用是在 zoom 启动前立刻收起通知面板，投递到主线程会引入一帧以上延迟，可能让小窗出现在面板之下。若评估结论是「不安全」，改为 `handler.postAtFrontOfQueue`，并在「未在设备验证」里加一条：从通知面板点链接时，小窗是否被面板遮挡。

### A3. 统一的「离开 starter 线程」入口

新建 `app/src/main/kotlin/io/github/zailinn/flymefreeform/hook/launch/StarterSideEffects.kt`：

```kotlin
package io.github.zailinn.flymefreeform.hook.launch

/**
 * starter 钩子里一切副作用的唯一出口。
 *
 * ActivityStarter.execute / executeRequest 运行时当前线程持有 WindowManagerGlobalLock。
 * 在这里同步发广播、调用会拿 AMS 锁的服务，会与持 AMS 锁等 WM 锁的线程形成 AB-BA 死锁
 * （1.4.0 实例见 CONSTRAINTS.md「本轮的坑」）。所以副作用一律经本对象投递到主线程执行。
 */
internal class StarterSideEffects(
    private val handler: android.os.Handler,
    private val lockProbe: StarterLockProbe,
    private val log: (code: String, throwable: Throwable?) -> Unit,
) {
    /** 投递到主线程执行；[name] 用于日志与违规定位。 */
    fun post(name: String, block: () -> Unit)

    fun postDelayed(name: String, delayMs: Long, block: () -> Unit)

    /** 收面板等对时延敏感的副作用：插到主线程队首。 */
    fun postAtFront(name: String, block: () -> Unit)
}
```

要求：

- `block` 内抛出的 `RuntimeException` 捕获并记录 `STARTER_EFFECT_FAILED_<name>`，不得让异常飞到主线程。
- `ActivityStarterZoomInjector` 内现有的 `handler.post / handler.postDelayed` 全部改为调用这个类；`handler` 字段从 injector 里移除，只保留在 `StarterSideEffects` 内。
- `sendBroadcast`、`collapsePanels`、`ZoomTaskCloser.closer`、延迟的 `startZoomWindow` 这几类调用的**代码文本**只允许出现在 `StarterSideEffects.kt` 或其调用的小函数所在文件里（为 A5 的静态检查服务）。推荐做法：在 `StarterSideEffects` 里提供具名方法，如 `requestBannerCollapse()`、`collapseShade(delayMs)`、`reopenInZoom(intent, target, delayMs)`，injector 只调这些具名方法。

### A4. 运行时锁检测

新建 `hook/launch/StarterLockProbe.kt`：

- 第一次进入 starter 钩子时，从 `chain.thisObject`（`ActivityStarter`）读 `mService` 字段（`ActivityTaskManagerService`），再读其 `mGlobalLock` 字段，保存为弱引用。读不到时探针失效，只记一次 `STARTER_LOCK_PROBE_UNAVAILABLE`，此后所有检查直接返回「未知」。
- 提供 `fun holdsWmLock(): Boolean? = lock?.let { Thread.holdsLock(it) }`。
- 可选：AMS 锁对象。system_server 内 `android.app.ActivityManager.getService()` 返回的就是 `ActivityManagerService` 本身（本地 binder），AMS 的大锁是 `synchronized (this)`，因此 `Thread.holdsLock(ActivityManager.getService())` 可判断是否持 AMS 锁。用反射获取，失败则忽略。

用法：

1. 在 `StarterSideEffects` 的每个具名副作用**真正执行时**（即已在主线程的 `block` 里）检查：如果此时 `holdsWmLock() == true`，说明有人绕过了投递直接调用，记录 `STARTER_LOCK_VIOLATION_<name>`（WARN 级、常开，不受详细日志开关控制，但同一 `name` 每 10 秒最多记一次）。
2. 提供 `fun assertOffStarter(name: String)`，给那些**不经 StarterSideEffects、但理论上不该在 WM 锁内运行**的代码调用（例如 `ZoomTaskCloser.closer` 的实现、`OutsideTapCloseHookInstaller.closeTopZoomTaskOf`）。违规时同样记日志；**不要抛异常**，system_server 里抛异常的代价远大于一条日志。
3. 对白名单项（`isShowingInZoom`、钩子内同步的 `startZoomWindow`）不做检查，在 KDoc 里写明原因。

验收：单测覆盖 `StarterLockProbe` 的纯逻辑部分（用一个普通 `Any()` 当锁对象，在 `synchronized(lock) { }` 内外分别断言 `holdsWmLock()`）。

### A5. 构建前静态检查

新建 `scripts/check-starter-safety.sh`（POSIX sh，macOS 自带 grep 可用）：

- 在 `app/src/main/kotlin/io/github/zailinn/flymefreeform/hook/launch/` 下，除 `StarterSideEffects.kt` 外的所有 `.kt` 文件中，查找以下模式，命中即打印文件与行号并以非零退出：
  - `sendBroadcast(`
  - `collapsePanels`
  - `exitFlexibleTask`
  - `Handler(` 、`.post(`、`.postDelayed(`（逼所有投递都经 `StarterSideEffects`）
  - `ZoomTaskCloser.closer`
- 允许用行尾注释 `// starter-safety: allow <原因>` 豁免单行，脚本跳过含该注释的行；豁免必须写原因。
- 在 `local-release.sh` 的单测步骤之前调用该脚本，失败则中止构建。只改 `local-release.sh` 里调用这一处，不要动签名、对齐等其他步骤。

### A6. 写进守则

在 `CONSTRAINTS.md`「九、改动守则」加一条（正常中文行文）：

> starter 钩子（`ActivityStarter.execute / executeRequest`）运行时持有 WindowManagerGlobalLock。钩子路径上不得同步发广播、不得同步调用任何可能拿 AMS 锁的服务；所有副作用经 `StarterSideEffects` 投递。新增同步调用前，必须在其 KDoc 写明「为何可在 WM 锁内调用」及依据（反编译或源码位置）。`scripts/check-starter-safety.sh` 在构建前强制检查。

同时在「三、各功能怎么实现的」里简述 `StarterSideEffects` / `StarterLockProbe` 的作用，在「二、目录」表里登记新文件。

### A7. A 部分验收

- `sh scripts/check-starter-safety.sh` 在改造后的代码上退出码为 0；故意在 injector 里加一行 `sendBroadcast(` 时退出码非 0。
- `compileReleaseKotlin` 与 `testDebugUnitTest` 通过。
- injector 内不再出现 `handler.` 调用。
- `CONSTRAINTS.md` 已更新，「未在设备验证」新增：常开日志里不应出现 `STARTER_LOCK_VIOLATION_`；Telegram 连点 X / Chrome 链接，system_server 不卡死。

---

## B. 启动判定纯函数化

### B1. 现状

`ActivityStarterZoomInjector.kt` 约 1000 行，判定逻辑与反射读字段、副作用交织：

- `preemptWithZoomWindow`：决定是否吞掉本次启动并改走 zoom 入口（含中转页自我重启、跨应用链接、目标已在小窗时关窗重开、重复链接吞掉、系统分发代发、控制中心磁贴）。
- `inject`：决定是否往原启动注入小窗参数，原因由 `reasonFor` 给出（`notification`、`control_center`、`control_center_launcher`、`inter_app`、`system_launch`、`launcher_card`、`launcher_settings`、`launcher_shortcut`、`self_relaunch`）。
- 状态：`lastZoomTarget / lastZoomAt / lastZoomHops / lastLinkKey / lastLinkAt`，以及 `InterAppLaunchTracker` 内的发起方 / 目标记录。

这些逻辑依赖 `Intent`、反射字段和 `PackageManager`，JVM 单测里无法构造（`android.jar` 桩会抛异常），所以目前 183 项单测几乎覆盖不到它。

### B2. 目标结构

全部放在 `hook/launch/decision/`（新包）下，**不得引用任何 `android.*` 类型**（`SystemClock` 也不行，时间由参数传入），保证能在 JVM 单测里直接跑。

1. `LaunchSnapshot`（data class）：一次启动的只读快照，由反射层填充。字段至少包括：
   - `stage: String`（`execute` / `executeRequest`）
   - `action: String?`、`dataString: String?`（`hasData` 由它推出）、`flags: Int`
   - `componentPackage: String?`、`explicitPackage: String?`、`resolvedTarget: String?`（反射层已解析好的目标包，含隐式解析结果）
   - `callingPackage: String`、`callingUid: Int`、`realCallingUid: Int`、`requestRealCallingUid: Int`
   - `hasResultTo: Boolean`、`requestCode: Int`
   - `hasPendingIntent: Boolean`
   - `zoomSourceExtra: Int`（`ZoomLaunchSpec.EXTRA_SOURCE`）、`zoomAppliedExtra: Boolean`、`quickSettingsExtra: Boolean`
   - `optionsAlreadyZoom: Boolean`
   - 以及 `reasonFor` / `launcherOriginReason` 用到的其余输入，执行者逐行对照补齐。
2. `LaunchEnvironment`（interface）：需要查询系统的判断，反射层实现、单测用假实现。
   - `deviceUnlocked(refresh: Boolean): Boolean`
   - `isExcluded(pkg: String): Boolean`
   - `isThirdParty(pkg: String): Boolean`
   - `systemUiUid: Int`、`launcherUid: Int`
   - `dispatchOwner(callingPackage: String, realCallingUid: Int, requestRealUid: Int): String?`（对应 `pendingDispatchOwner`）
   - `zoomShownPackage(): String?`（对应 `isShowingInZoom`，返回当前小窗包名或 null）
   - `fromControlCenter(pkg: String): Boolean`
   - 设置项：`enabled`、`zoomLaunch`（或直接传 `ModuleSettingsSnapshot` 的子集）。
   
   注意惰性：现有代码只在需要时才查 `PackageManager`，`LaunchEnvironment` 的方法保持按需调用，不要在构造快照时预先全部查询。
3. `LaunchMemory`（class，纯内存、可变）：吸收 `lastZoom*`、`lastLink*` 与 `InterAppLaunchTracker` 的状态和规则（`markZoomed`、`isSelfRelaunch` 的时间窗与跳数、重复链接窗口、`consumeIfReturning`、`record`）。所有方法显式接收 `now: Long`。`InterAppLaunchTracker` 可以保留为薄包装或并入，执行者选其一并说明。
4. `LaunchDecision`（sealed interface）：
   - `PassThrough(log: String?)`：不干预，原样启动。
   - `SwallowDuplicate(target)`：吞掉本次启动（重复链接）。
   - `Preempt(target, linkKey: String?, selfRelaunch: Boolean, callingPackage)`：吞掉并同步 `startZoomWindow`。
   - `ReopenInZoom(target, linkKey, callingPackage)`：吞掉，投递「关旧小窗 → 延迟重开」。
   - `Inject(reason: String, markSelfRelaunch: Boolean, recordInterApp: Pair<String, String>?)`：往原启动注入小窗参数。
   - 每个分支携带生成原有日志所需的信息，日志仍由执行层输出，**文本与现在逐字一致**。
5. `LaunchDecider`（object 或 class）：
   - `fun decidePreempt(snapshot, env, memory, now): LaunchDecision`：对应现 `preemptWithZoomWindow` 的判定部分。
   - `fun decideInject(snapshot, env, memory, now): LaunchDecision`：对应现 `inject` + `reasonFor` + `launcherOriginReason` 的判定部分。
   - 判定函数只读 `memory`；状态更新由执行层在副作用成功后调用 `memory` 的方法完成（例如 `Preempt` 只有 `startZoomWindow` 返回成功才 `markZoomed` 与记录 `linkKey`，与现状一致）。
6. 执行层（留在 `ActivityStarterZoomInjector.kt`）：
   - `readSnapshot(starter, stage): LaunchSnapshot?`：所有反射读字段集中于此。
   - 按 `LaunchDecision` 执行副作用（通过 A3 的 `StarterSideEffects`）、写 options、输出日志、更新 `memory`。
   - 目标：injector 文件缩到 400 行以内，常量表（`SKIP_TARGETS`、`PICKER_ACTIONS` 等）迁到 `decision/LaunchRules.kt`。

### B3. 施工顺序（每步都要能编译、单测通过）

1. **只加不改**：新建 `decision/` 下的数据类与接口，空实现，编译通过。
2. **搬判定**：把 `isSelfRelaunch`、`isPlainInterAppLink`、`reasonFor`、`launcherOriginReason`、`preemptWithZoomWindow` 中的 `eligible` / 重复 / 已在小窗分支，**逐行搬**到 `LaunchDecider`，用 `snapshot` / `env` / `memory` 替换原来的字段读取。不重写条件、不合并分支、不「顺手优化」。搬完后做一次自查：原函数每个 `return` 在新函数里都有对应分支。
3. **接线**：injector 改为 `readSnapshot → decide → execute`；删除旧判定代码。
4. **写测试**（B4）。
5. **A 部分**与 B 部分可以分两个提交，建议先做 A（改动小、风险集中），再做 B。

### B4. 回归测试：用真实日志序列

新建 `app/src/test/kotlin/io/github/zailinn/flymefreeform/hook/launch/decision/LaunchDeciderTest.kt`。每个场景按设备日志里的真实顺序喂多条 `LaunchSnapshot`，同一个 `LaunchMemory` 贯穿，时间用日志里的毫秒差。以下 flag 值、字段取值均来自 2026-11-08 的设备日志（`ZOOM_TRACE` 行）。

| # | 场景 | 输入序列（要点） | 期望 |
| --- | --- | --- | --- |
| 1 | Telegram 点 X 链接，X 不在小窗 | ① VIEW，`cp=org.telegram.messenger.web`，`tg=com.twitter.android`，`fl=0x10000400`，`rt=1`，`requestCode=-1`，有 data；② 约 60 ms 后同链接 `fl=0x10000000`；③ 约 250 ms 后 `cp=tg=com.twitter.android`，MAIN，`fl=0x1801c000`，无 data | ① `Preempt`（成功后执行层 `markZoomed`、记 linkKey）；② `SwallowDuplicate`；③ `decidePreempt` 为 `PassThrough`、`decideInject` 为 `Inject("self_relaunch", markSelfRelaunch = true)` |
| 2 | X 已在小窗时再点链接 | 同 1 的 ①②，但 `env.zoomShownPackage() == "com.twitter.android"` | ① `ReopenInZoom`；② `SwallowDuplicate` |
| 3 | Telegram 点 Chrome 链接 | ① VIEW 到 `com.android.chrome`；② Chrome 自身以 NEW_TASK 重启，带 data（`IntentDispatcher → CustomTabActivity`，`fl=0x1a080000`），在 3 s 内 | ① `Preempt`；② `Preempt(selfRelaunch = true)` |
| 4 | 中转页连续重启跳数上限 | 场景 3 之后（已接管 1 次自我重启）再来两次同样的自我重启 | 第 1 次仍为 `Preempt(self)`（累计 2 次，达到 `MAX_SELF_RELAUNCH_HOPS`），第 2 次 `PassThrough` |
| 5 | 中转页超时 | 场景 1 的 ③ 延后到 3 s 之后 | 不再判为 `self_relaunch` |
| 6 | 等结果的启动 | `hasResultTo = true`，`requestCode = 100` | `PassThrough`（登录、授权回传链不被打断） |
| 7 | 应用内翻页 | `cp == tg`，无 NEW_TASK | `PassThrough` |
| 8 | 桌面全局搜索 | `action = android.search.action.GLOBAL_SEARCH` | `decideInject` 不注入（对应 `ZOOM_SKIP_GLOBAL_SEARCH`） |
| 9 | 锁屏 | `env.deviceUnlocked() == false` | `PassThrough` |
| 10 | 小窗跳转总开关关闭 | `zoomLaunch = false` | 跨应用链接、中转页一律 `PassThrough` |
| 11 | 原路返回 | 先 1 的 ① 成功，随后 X 以 VIEW 打开 Telegram（`cp=com.twitter.android`，`tg=org.telegram.messenger.web`） | 后者 `PassThrough`（`consumeIfReturning`） |
| 12 | 排除列表 | 目标在排除列表内 | `PassThrough` / 不注入 |
| 13 | 选择器类动作 | `action` 属于 `PICKER_ACTIONS` | 不注入（`ZOOM_SKIPPED_PICKER`） |
| 14 | 发起方是桌面或 SystemUI | `cp=com.android.launcher` 的 VIEW | 不按跨应用链接接管 |
| 15 | 各 `reasonFor` 原因 | 为 `notification`、`control_center`、`control_center_launcher`、`system_launch`、`launcher_card`、`launcher_settings`、`launcher_shortcut` 各构造一条最小输入 | 返回对应原因 |
| 16 | 系统分发代发 | `env.dispatchOwner(...) != null`，目标不在 `SKIP_TARGETS` | `Preempt` |
| 17 | 已注入过 | `zoomAppliedExtra = true` | `decideInject` 不重复注入 |

另外为 `LaunchMemory` 单独写时间窗测试：`DUPLICATE_LINK_MS`（1500 ms）边界前后、`SELF_RELAUNCH_WINDOW_MS`（3000 ms）边界前后。

### B5. 行为等价的保证

因为旧代码无法在 JVM 上跑，不能做新旧对拍。用以下方式兜底：

1. B3 第 2 步严格「逐行搬」，提交时在提交说明里列出旧函数 → 新函数的对应关系。
2. B4 的场景表即行为规格；任何一条与现有设备日志不符，以设备日志为准修正测试，不改判定。
3. 完成后在 `CONSTRAINTS.md`「未在设备验证」里登记完整的设备回归清单（见 B7），由用户装机验证。

### B6. 不得改变的日志码

以下日志码的文本、触发条件、级别保持不变（排障依赖它们）：

`ZOOM_TRACE`、`ZOOM_ENTRY_`、`ZOOM_DECISION_`（含 `_ok _rt _self _link _pi _su _cp _tg` 各段顺序）、`ZOOM_PREEMPTED_`、`ZOOM_PREEMPT_DUPLICATE_`、`ZOOM_PREEMPT_FAILED`、`ZOOM_LINK_TARGET_IN_ZOOM_`、`ZOOM_REOPEN_SCHEDULED_`、`ZOOM_REOPEN_UNAVAILABLE_`、`ZOOM_REOPENED_`、`ZOOM_INJECTED_<reason>`、`ZOOM_INJECT_FAILED`、`ZOOM_NATIVE_CHANNEL_`、`ZOOM_OPTIONS_WRITE_FAILED`、`ZOOM_SKIP_GLOBAL_SEARCH`、`ZOOM_SKIP_INTER_APP_FULLSCREEN_`、`ZOOM_SKIPPED_EXCLUDED_`、`ZOOM_SKIPPED_PICKER`、`ZOOM_INTER_APP_RETURN`、`ZOOM_LEFT_SHADE_TO_NATIVE`、`ZOOM_SHADE_IMMEDIATE`、`ZOOM_SHADE_IMMEDIATE_FAILED`、`ZOOM_SHADE_COLLAPSED`、`ZOOM_SHADE_COLLAPSE_FAILED`、`ZOOM_SHADE_UNAVAILABLE`、`ZOOM_BANNER_COLLAPSE_REQUESTED`、`ZOOM_BANNER_COLLAPSE_FAILED`、`ZOOM_STARTER_HOOK_INSTALLED_`、`ZOOM_STARTER_UNAVAILABLE`、`ZOOM_STARTER_LINKAGE_FAILED`、`ZOOM_STARTER_METHODS_MISSING`、`INTER_APP_RECORD`、`INTER_APP_RETURN_`、`INTER_APP_CLEAR_`。

新增日志码只有 A 部分的 `STARTER_EFFECT_FAILED_<name>`、`STARTER_LOCK_VIOLATION_<name>`、`STARTER_LOCK_PROBE_UNAVAILABLE`，需登记到 `CONSTRAINTS.md`「七、日志与排障」。

### B7. B 部分验收

- `testDebugUnitTest` 通过，新增测试覆盖 B4 全部 17 个场景与时间窗边界。
- `compileReleaseKotlin` 通过；`decision/` 包内 `grep -E "import android\."` 无结果。
- `ActivityStarterZoomInjector.kt` ≤ 400 行。
- 日志码清单（B6）在代码里仍能逐个 grep 到。
- `CONSTRAINTS.md` 第十节「未在设备验证」登记设备回归清单：
  1. Telegram 点 Chrome 链接：小窗打开（`ZOOM_PREEMPTED_com.android.chrome`）。
  2. Telegram 点 X 链接：小窗打开并直达帖子。
  3. X 小窗开着时再点另一条 X 链接：旧小窗关闭、重开为小窗并显示新帖子（`ZOOM_REOPENED_closedtrue_startedtrue`）。
  4. 连点链接十次以上，system_server 不卡死，常开日志无 `STARTER_LOCK_VIOLATION_`。
  5. 登录 / 授权类 `startActivityForResult` 仍全屏且能回传。
  6. 应用内翻页不被误接管。
  7. 通知点击、控制中心磁贴长按、桌面卡片 / 快捷方式 / 设置入口各点一次，小窗行为与改造前一致。
  8. 桌面全局搜索仍全屏。

---

## C. 交付物清单

- 新文件：`hook/launch/StarterSideEffects.kt`、`hook/launch/StarterLockProbe.kt`、`hook/launch/decision/{LaunchSnapshot,LaunchEnvironment,LaunchMemory,LaunchDecision,LaunchDecider,LaunchRules}.kt`、`scripts/check-starter-safety.sh`、对应单测文件。
- 修改：`ActivityStarterZoomInjector.kt`、`InterAppLaunchTracker.kt`（并入或变薄）、`OutsideTapCloseHookInstaller.kt`（`closeTopZoomTaskOf` 加 `assertOffStarter`）、`local-release.sh`（只加一行检查调用）、`gradle.properties`（versionCode +1）、`CONSTRAINTS.md`。
- 不做：构建 APK、安装、重启设备进程、推送。完成后向用户汇报改动与设备验证清单，由用户决定是否构建。
