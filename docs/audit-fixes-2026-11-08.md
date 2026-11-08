# 2026-11-08 全量审计修复结果

基线 `4825e37`，分支 `refactor/starter-hook`。修复方案见 `plan-audit-fixes.md`。
本轮仅修改源码、静态检查、Kotlin 编译、JVM 单测及本地提交。versionName 保持 1.4.2，versionCode 按五组增加至 2026110839。

| 审计项 | 修复结果 | 主要位置 / 验证 |
|---|---|---|
| 1. 绑定瞬间 Binder 死亡 | 捕获 RemoteException 所属的 Exception；死亡回调校验当前 Binder；迟到连接回调校验绑定身份 | ColorOsSidebarEntryConnection |
| 2. 缺包异常与未知 UID | 三个接收端共用 SettingsBroadcastSender；负 UID、查询失败和 checked exception 均拒绝 | TrustedSenderTest |
| 3. 取消准备导致永久 busy | CancellableWork 对取消前排队、执行失败、队列拒绝统一完成通知；释放后唤起当前请求，不再每 30 ms 重试 | CancellableWorkTest，4 项 |
| 4. 控制中心短期缓存漏判 | 取消 100 ms 跨启动缓存；在 execute / resolveActivity 阶段，探针确认未持 WM 锁后读取，并在本次启动内传递 | ActivityStarterZoomInjector / StarterQueryCache；未知或持锁时保守放行 |
| 5. 延迟重开丢请求、重复和过期操作 | 复制 Request 普通字段，保留 caller、UID/PID、userId、resultTo 和 URI grants；每阶段复核开关、排除、锁屏与用户可见性；失败尝试原参数启动；新链接取代旧票据，用户切换取消待办 | StarterRequestReplay、DeferredLaunchTest、PendingLaunchesTest |
| 6. 改写入口绕过排除列表 | 接管与注入统一先检查排除；注入统一检查锁屏与 zoomLaunch；来源 extra 须匹配 SystemUI UID | LaunchDeciderTest，新增所有入口与伪造标记回归 |
| 7. 排除应用仍被抢触摸 | system_server 发布许可，SystemUI / 桌面验证 SYSTEM_UID 后只读缓存；前台、焦点、配置、环境和用户切换触发更新；启动前未知状态拒绝 | GesturePermissionChannel、SystemServerHookInstaller；真实广播 / 输入时序待设备验证 |
| 8. 异步写回覆盖搜索 / 原生列表 | 回调绑定会话身份；执行时再检查面板存活、adapter、查询和模式；关闭 / 更换面板清理会话 | PanelWritebackTest |
| 9. 用户和解绑上下文丢失 | 使用 Request.userId；各用户分别保存重复 / 返回记忆；关窗核对 Task.mUserId；绑定保留实际 Context；用户切换断开旧链并清除待启动票据 | LaunchMemoriesTest、PendingLaunchesTest、ColorOsSidebarEntryConnection |
| 10. 黑名单快速操作覆盖 | UI 只发送包名与目标状态；串行队列在最新快照上合并；选择页保留待确认的即时反馈 | BlacklistEditsTest |
| 11. 窗外关闭与底层点击同时发生 | 把实际 protected 状态传入 captured，从 DOWN 未捕获的流不触发关闭 | OutsideTapCloseHookInstaller；已有 OutsideTapGestureEngineTest 覆盖未捕获流 |
| 12. 静态检查失败被掩盖 | 缺目录、空目录、读取错误均失败；识别跨行调用、尾随 lambda、广播变体；decision 禁止 android.*；纳入 Gradle 编译前置、CI 和本地发布 | scripts/test-starter-safety.sh：9 项；Gradle 注入违规源码验证通过 |
| 13. 方形图标再次被圆裁 | 去掉绘制层额外圆裁切，使用已塑形位图的 alpha | CornerRadialOverlayView |

## 中央图标清晰度补充

按用户确认，优先清晰度，允许使用应用原始高清图标，外形服从设置。

- 单独加载高清图，不复用扇形小位图作为最终预览。先显示小图的过渡仍可能短暂出现。
- 按最终显示像素与本体放大倍数，以 2× 线性尺寸绘制，再下采样显示；最长 1024 px，每份缓存最多 4 张；初始化异步预取前 3 个条目。
- 系统只返回低清 BitmapDrawable 时，尝试 APK 内的自适应 / 矢量或更高分辨率资源；取不到则保留系统图标。
- 原始资源仍经 IconShapes 处理为所选圆形 / 圆角方形。主题外观可能变化，这是用户已确认的取舍。
- PREVIEW_ICON_SOURCE 日志记录源类型、固有尺寸、绘制尺寸和是否使用原始资源，便于后续设备核对。普通低清位图无法仅靠增加输出像素恢复细节；侧边栏工具 / 快捷方式仍受原素材限制。
- 缓存失效与写回由 CacheRevision 原子校验；View 也校验请求代数，拒绝旧配置回调。CacheRevisionTest 与 PreviewIconSizingTest 已通过。

## 验证

```sh
sh scripts/test-starter-safety.sh
ANDROID_HOME="$HOME/Library/Android/sdk" sh gradlew -q :app:compileReleaseKotlin :app:testDebugUnitTest
```

- release Kotlin 编译通过；injector 398 行。
- 240 项 JVM 单测：237 通过，0 失败 / 错误，3 跳过。跳过的是 LauncherDexQueryTest 的 1 项与 SidebarDexQueryTest 的 2 项，缺少真实 APK / 原生 DexKit 测试夹具；不能将它们计作兼容性已验证。
- 静态脚本 9 项场景通过；临时添加非法 handler.post 调用后，compileReleaseKotlin 被 checkStarterSafety 前置任务拒绝。临时夹具已移除。
- PM 预热移到专用工作线程；包变化选择性失效，查询写回按代数校验；工作队列拒绝后释放 pending / warming，允许后续重试。隐式启动目标只取当前 Request 的实际解析信息，避免旧 Intent 解析缓存影响排除判定。
- 对比基线，starter 原有 55 个诊断码前缀全部保留（当前 56 个）。
- 桌面在 Application.onCreate 异步预热许可，首次触摸保留异步兜底；侧边栏用户广播登记也离开 systemReady 构造线程，避免在未知框架锁内登记广播。
- 未构建 APK、未安装、未重启、未推送。厂商反射兼容性、真实输入时序与清晰度仍需设备验收。

## 后续设备验收（需另行授权）

1. 服务端重启后直接打开设置页，死亡对象自动剔除，写入恢复；不同有效 Binder 仍隔离。
2. 侧边栏准备中取消 / 超时、绑定时进程死亡、连续开关面板与用户切换后连接恢复。
3. Telegram → X / Chrome；X 已在小窗时换链接；A→B→A 连点；登录授权回传；工作资料 / 分身保留用户与 URI 授权。
4. 延迟期间关闭开关、加入排除、锁屏、切换用户；不得关闭其他用户的窗口；失败日志与原参数回退路径可见。
5. 控制中心当前启动来源、通知、桌面入口与冷缓存；STARTER_LOCK_VIOLATION_ 不应出现。厂商同步 zoom 白名单保持既有实现，未补充完整厂商锁链证据，不能宣称已排除所有死锁。
6. 排除应用与关键系统页面中的底角触摸保持原行为；重点检查切换边界的广播调度延迟。
7. 搜索 / 原生列表模式下异步结果不改列表；频繁切换图标形状后无旧缓存回写；中央大图的清晰度、圆 / 方外形和主题回退。

AOSP 参考：ActivityStarter 的 Request 字段、execute 锁位置与原参数执行流程，见 https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/wm/ActivityStarter.java 。这些只能支持 AOSP 部分，不能替代当前设备厂商实现的验收。
