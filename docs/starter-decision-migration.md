# 启动判定迁移对应关系

原判定以 `aef03c5`（本轮开始前的实现）为基线。A 的缓存与投递边界见 `starter-lock-audit.md`；B 不再改判定条件。

| 原函数/状态 | 新位置 | 保留的顺序或更新时机 |
| --- | --- | --- |
| `preemptWithZoomWindow` | `LaunchDecider.decidePreempt` + injector `executePreempt` | 重入/总开关、目标、等结果、自重启、普通链接、已在小窗、重复、重开、eligible 各分支依次保留 |
| `isSelfRelaunch` | `LaunchDecider.isSelfRelaunch` + `LaunchMemory.selfRelaunch` | 开关、同包、最近目标、3 秒、2 跳、NEW_TASK、排除项；成功后才记跳数 |
| `isPlainInterAppLink` | `LaunchDecider.isPlainInterAppLink` | VIEW、调用包、桌面/SystemUI、全屏名单、排除项、第三方、原路返回；短路求值顺序保持 |
| `inject` | `LaunchDecider.decideInject` + injector `executeInject` | 总开关、已注入、无 data 自重启、reason、标记、原生 options、参数写回、成功记录 |
| `reasonFor` | `LaunchDecider.reasonFor` | 逐行迁移；来源标记、全局搜索、目标、排除、返回、通知、选择器、应用互跳、桌面、系统代发、SystemUI、控制中心代理 |
| `launcherOriginReason` | `LaunchDecider.launcherOriginReason` | 卡片、设置、快捷项；QQ 的 MAIN + 0x14000000 仍可命中快捷项 |
| `lastZoom* / lastLink*` | `LaunchMemory` | 同步接管成功才写；重开在调度时记链接，成功后记 zoom；无关失败不制造重复记录 |
| `InterAppLaunchTracker.consumeIfReturning` | `LaunchMemory.checkReturning / apply` | 判定只返回 ReturnCheck；执行层按事件顺序应用清空/返回指纹，再输出原日志。120 秒过期与 2 秒返回指纹保持 |
| `InterAppLaunchTracker` | 保留薄包装 | 共享 LaunchMemory，无复制状态；原构造与可空 tracker 语义保持 |
| 常量表 | `LaunchRules` | 包名、动作、flags 与时间窗逐项保留 |

原函数的提前返回均在新函数中保留；原生 options 仍在执行层标记 Intent 后提前返回，不更新跳数或跨应用记录。
同步接管失败后重新读取 Request / Intent / options 再决定注入，保留嵌套 starter 回调可能写入的已注入标记。

回归测试覆盖计划 B4 的 17 类场景，并增加失败接管不记忆、查询惰性、来源标记优先级及返回状态只读测试。
真实设备日志中的 flags 保留为 0x10000400、0x10000000、0x1801c000、0x1a080000、0x14000000。

两点规格澄清（保留旧代码，不顺手修行为）：

- `requestCode >= 0` 只阻止抢占重发，旧 inject 的 `inter_app` 分支仍可往原启动补 options，不删除 resultTo。
- 锁屏检查原本位于抢占/重开路径；本次未新增全局 inject 锁屏过滤。来源标记原本先于排除项/选择器，仍保留此顺序。

设备回归由用户后续单独授权：Telegram → Chrome / X、X 已开小窗时新链接、连点十次、登录授权回传、页内跳转、通知/磁贴/桌面各入口、全局搜索与无锁违规日志。A 的冷缓存和队首收面板时序需要一起验证。
