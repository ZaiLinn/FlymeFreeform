# starter 同步调用与锁边界

本表覆盖 `execute / executeRequest → trace / preempt / inject` 以及限制钩子。
“可以”仅指模块新增的调用，不对 `chain.proceed()` 的原厂实现作额外保证。

| 调用 | 结论 | 处理及依据 |
| --- | --- | --- |
| 反射读取 Request、Intent、ResolveInfo 与 options 字段 | 可以 | 只读本地字段；Intent / Bundle 的取值、复制与标记不调用服务 |
| `ActivityOptions.toBundle / fromBundle`、starter `setActivityOptions` | 可以 | AOSP ActivityStarter.setActivityOptions 仅构造 SafeActivityOptions，沿用原启动上下文 |
| `systemContext` | 可以 | 反射读取当前 ActivityThread 已有对象，不获取系统服务 |
| `sendBroadcast` | 已投递 | 1.4.0 trace 证实 WM → AMS 反转；统一至 StarterSideEffects |
| `collapsePanels`（即时与延迟） | 已投递 | AOSP Android 15 StatusBarManagerService.collapsePanels → checkCanCollapseStatusBar 包含权限、CompatChanges 与 ATMS 检查，厂商扩展锁序未知；即时版本投递队首 |
| PM `getApplicationInfo` | 已投递 | ComputerEngine.getApplicationInfoInternal → isRecentsAccessingChildProfiles → ActivityManager.getCurrentUser，不能假定任意调用身份下安全 |
| PM `resolveActivity` | 已投递 | ResolveIntentHelper.resolveIntentInternal 依赖跨用户、包过滤与解析子系统；不为厂商全链路作安全假设。钩子读缓存，未命中放行，后续查询填充 |
| PM `getPackagesForUid`、桌面与 SystemUI uid | 已投递 | AOSP ComputerEngine 查询快照但含可见性过滤；与其他 PM 查询统一缓存，包变化时失效；预热保留 PM 原始 shared uid 包名顺序 |
| `isDeviceUnlocked(refreshKeyguard = true)` | 已投递/缓存 | AOSP WMS → PhoneWindowManager → KeyguardServiceDelegate → KeyguardStateMonitor 读取本地状态；无法证明 ColorOS 扩展相同。钩子改用环境缓存，刷新仍由原环境主线程广播完成 |
| `startZoomWindow`（同步接管） | 可以，白名单 | 按计划 A2 保留：既有 ColorOS 路径重入 ATMS WM 锁，已有设备验证；1.4.0 trace 的反转点是广播。此依据不是所有 ROM 的安全证明 |
| `getCurrentZoomWindowState` | 可以，白名单 | 既有 ColorOS 反编译结论：只拿 WM 可重入锁与 OplusFlexibleInfoManager 叶子锁，见计划 A2 |
| `ZoomTaskCloser.closer`、延迟重开 | 已投递 | 只经 StarterSideEffects，真实关窗处增加 assertOffStarter |
| `OplusListManagerImpl.isFromControlCenterPkg` | 已投递 | 厂商来源标记查询缺少字节码证据，按不安全处理，主线程刷新 100 ms 缓存；冷缓存保守返回 false，设备须核对控制中心代理入口 |
| `InterAppLaunchTracker`、启动时间窗/跳数、ThreadLocal | 可以 | 纯内存与单调时钟，不获取 AMS；关系日志走现有 LSPosed 出口 |
| `ModuleLog.debug / module.log` | 可以，有限依据 | 模块本地出口直接调用 XposedModule.log，目标为框架日志服务而非 AMS；框架内部锁不由本模块控制，未改日志触发条件 |
| `FlexibleWindowLimitHook` | 可以 | 钩子新增部分只读配置、前台包和集合；返回常量或调用原方法，没有系统查询/广播/投递 |
| `StarterLockProbe` | 可以 | 反射只读 WM 锁引用，Thread.holdsLock 不获取锁；探针自身的短临界区不调用系统服务 |

源码依据（AOSP `android15-release`）：

- https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/statusbar/StatusBarManagerService.java （collapsePanels、checkCanCollapseStatusBar）
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/pm/ComputerEngine.java （getApplicationInfoInternal、isRecentsAccessingChildProfiles、getPackagesForUidInternal）
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/pm/ResolveIntentHelper.java （resolveIntentInternal）
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/wm/WindowManagerService.java （isKeyguardLocked）
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/policy/keyguard/KeyguardStateMonitor.java （isShowing）

验证只包含编译、JVM 单测与静态脚本，未操作设备。冷缓存可能导致首次隐式启动按系统行为放行，需设备核对；未证明的厂商同步入口不能据本表宣称已彻底消除锁风险。
