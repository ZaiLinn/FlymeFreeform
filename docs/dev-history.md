# 开发记录

按时间倒序记录每次源码改动、构建、安装与发布，由 `CONSTRAINTS.md` 第十节迁出（2026-11-09）。
当前状态、待验证与下一步只看 `CONSTRAINTS.md` 第十节；本文件只追加，不改写旧条目。

## 条目

2026-11-09 提交与发布 1.5.0 / versionCode 2026110916，删除 v1.4.10：用户要求「把1,4,10删了,重新发布1.5.0」（此前说的「装」被打断，未安装）。提交 0916 代码（feat(ui): 设置页记住上次停留的页面）与版本 `f6601ad`（chore(release): 1.5.0），fetch 后快进推送 `a34a3ad..f6601ad`。`gh release delete v1.4.10 --cleanup-tag` 删除 Release 与远端标签，本地标签一并删除（`624f590` 提交仍在 main 历史中）。`gh release create v1.5.0 --target f6601ad8709d89e34c4f3a41284cfb0df1771f96`，资产 `out/FlymeFreeform-v1.5.0.apk`，GitHub digest 与本地 sha256 `9a836192…c43a89e` 一致，为 Latest；说明沿用 1.4.10 版并补「设置页记住上次停留的页面」，注明 1.4.9、1.4.10 未单独发布。随后提交本条文档。

2026-11-09 构建 1.5.0 / versionCode 2026110916：用户要求「构建1.5.0,版本号最后一位到9了下一次构建就进一」。版本号规则补充：versionName 末位只用 0–9，到 9 后下一次构建进位（1.5.9 → 1.6.0），1.4.10 之后直接 1.5.0；已写入第六节与第九节第 11 条。`sh local-release.sh` 通过（starter 静态检查、单测 275 项 0 失败 3 跳过、assembleRelease、zipalign -P 16、release.keystore v1/v2/v3 签名与校验）。产物 `out/FlymeFreeform-v1.5.0.apk`，2228527 字节，sha256 `9a8361920fc5941cce7762bf0dd2214c97514d493210a02647b20dba6c43a89e`，aapt2 确认 versionName 1.5.0、versionCode 2026110916；包内 dex 无读取不存在的静态字段。较 1.4.10 只多 2026110916（设置页记住上次页面）。未安装、未提交、未推送。

2026-11-09 源码 versionCode 2026110916：用户反馈设置页停在二级菜单后回桌面，过一会再打开变回首页（后台被墓碑冻结），要求修复。设备上排查：装有 Thanox；本应用在最近任务里有多个任务（#11、#27 隐藏，#32），`am kill` 后从桌面图标启动回到首页，但测试中途有一次人为点图标、且我先前用 `am start -n` 可能另开了任务，干扰未排除，未确认 rememberSaveable 恢复失败的具体原因。页面栈本身由 miuix `rememberNavBackStack`（rememberSaveable + kotlinx 序列化）保存，release 映射里 `AppRoute` 序列化器保留；冻结 / 清理工具以强行停止或移除任务结束进程时保存状态随之丢失。修复不依赖系统保存状态：新增 `NavStackStore`，`FlymeFreeformNavHost` 以 `snapshotFlow` 在页面栈变化时写本应用 SharedPreferences（`ui_state` / `back_stack`），`rememberNavBackStack` 的初始值改为读出的栈（有保存状态时仍以保存状态为准）；栈底不是首页、记录损坏时退回首页，旧路由 `EnvironmentScope` 恢复为 `ZoomOptions`。新增 `NavStackStoreTest` 3 项。单测 275 项（0 失败、3 跳过）、`minifyReleaseWithR8`、lintVitalRelease 通过，R8 产物 dex 无读取不存在的静态字段。未构建、未安装、未提交。

2026-11-09 设备状态：用户说明装 1.4.9 后已自行重启，不需要代为重启。系统部分运行 1.4.9 的钩子，与 1.4.10 相同（1.4.10 只改设置页）。

2026-11-09 提交与发布 1.4.10 / versionCode 2026110915：用户要求「提交发布github,发布release」。2026110902–0915 跨版本改同一批文件，无法按 versionCode 拆分，合为一次代码提交 `f7e62d8`（feat，正文按通知与跳转、开机与侧边栏、多用户、占用、设置页分组）+ 版本提交 `624f590`（chore(release): 1.4.10）。推送前 fetch，`origin/main` 无新提交，快进推送 `17f7078..624f590`。`gh release create v1.4.10 --target 624f590ea955cf0dfb3c90f799c7130615068f71`，资产 `out/FlymeFreeform-v1.4.10.apk`，GitHub 资产 digest 与本地 sha256 `11f868b3…9c9098` 一致；说明覆盖 1.4.8 以来全部改动（1.4.9 未单独发布）。随后提交本条文档。

2026-11-09 安装 1.4.10 / versionCode 2026110915：用户要求「装」。`adb install -r out/FlymeFreeform-v1.4.10.apk`（设备 111680cd）成功，22:53 生效。清空 crash 缓冲区后 `am start -W` 冷启动 `MainActivity`，TotalTime 377 ms，进程存活，crash 缓冲区无本包记录；截图为「小窗设置」页正常显示。1.4.9 → 1.4.10 只改设置页（`AppIconLoader` 的 `@Stable` 与 keep 规则），未重启 system_server 与侧边栏。

2026-11-09 构建 1.4.10 / versionCode 2026110915：用户要求「构建」。versionName 1.4.9 → 1.4.10，`sh local-release.sh` 通过（starter 静态检查、单测 272 项 0 失败 3 跳过、assembleRelease、zipalign -P 16、release.keystore v1/v2/v3 签名与校验）。产物 `out/FlymeFreeform-v1.4.10.apk`，2228527 字节，sha256 `11f868b34607c029f17bb64894820a99fb3d95532d8028f3b439708a849c9098`，aapt2 确认 versionName 1.4.10、versionCode 2026110915；扫描包内 dex：19 处 `$stable` 读取字段都在，无读取不存在静态字段。修复 1.4.9 设置页闪退。未安装、未提交、未推送。

2026-11-09 源码 versionCode 2026110915：用户装 1.4.9 后报「应用闪退，但功能正常」。`adb logcat -b crash`：`java.lang.NoSuchFieldError: No static field $stable of type I in class Lor0;`，栈顶 `MainActivity` 组合。mapping 显示 `or0` = `LauncherAppRepository$AppIconLoader`；Kotlin 产物里该类有 `$stable`，`MainActivity` 以 `getstatic AppIconLoader.$stable` 运行时读取（内部类，Compose 编译器推断不出稳定性；2026110913 给它加了快照状态 `version` 后作为 Composable 参数传入），R8 产物 dex 里字段被删、读取保留，全包只有这一处。模块钩子不经设置页，所以功能正常。修复：`AppIconLoader` 标 `@Stable`（变化经快照状态通知，编译期按稳定处理，不再运行时读）；`rules.keep` 保留 `io.github.zailinn.flymefreeform.**` 的 `public static final int $stable` 兜底。单测 272 项 0 失败 3 跳过，`minifyReleaseWithR8`、lintVitalRelease 通过；扫描 R8 产物 dex：19 处 `$stable` 读取字段都在，无任何读取不存在静态字段。未重新构建 APK、未安装。

2026-11-09 构建 1.4.9 / versionCode 2026110914：用户要求「构建apk给我」。versionName 1.4.8 → 1.4.9，`sh local-release.sh` 通过（starter 静态检查、单测 272 项 0 失败 3 跳过、assembleRelease、zipalign -P 16、release.keystore v1/v2/v3 签名与校验）。产物 `out/FlymeFreeform-v1.4.9.apk`，2228527 字节，sha256 `21f38bc48df2ccec2715ec0559fbecd78772f63fabe8945736102f99140ad8f3`，aapt2 确认 versionName 1.4.9、versionCode 2026110914。包含 2026110902–0914 全部未提交改动。未安装、未重启、未提交、未推送。

2026-11-09 源码 versionCode 2026110914：用户要求为第三轮六项修复增加对应回归验证。新增 21 项 JVM 单测：副本升级完整快照、手势目标稳定、面板格子离开释放与迟到回调、加载器替换、连续浏览只持有可见行、用户快照发布资格、非主用户各 UID 来源与全屏放行、图标失效后拒绝旧写回与回调、用户上下文复用与切换。为可测性提取并在生产路径使用 `RadialCatalogUpdate`、`VisibleIconRequest`、`UserScopedCache` 与 `AppCatalogSnapshot.usableFor`。补测时修正：面板图像改由格子及加载器独立持有（旧 effect 清理不会导致新格子跳过请求、永久空图）；设置页图标缓存改用 `CacheRevision.commit/invalidate` 原子化代数判断、写回与失效。高清尺寸和固定项高清缓存策略不变。debug 编译与全量单测通过：272 项，269 通过、0 失败、3 项原有厂商样本测试跳过；starter 安全扫描、git diff --check 通过。具体覆盖与真机清单见 `docs/regression-2026-11-09.md`。未构建 APK、未安装、未重启、未推送；本轮未重跑 release 编译与 lint。

2026-11-09 源码 versionCode 2026110913：处理第三轮审查 6 条，逐条核对后都成立。① 本地副本换真实目录：条目相同时 `updateRadialAppearance` 原先只换图像，浮层仍拿着副本（没有高清 / 面板加载器、面板为空），中央预览不高清、内置「更多」为空，要重新呼出。改为 `adoptSameTargets` 整份换目录，保留选中与手势；加载器变了就作废旧请求、向新加载器重请求（目录合并在途任务），已显示的高清图只在条目图标对象变了时丢弃；分圈或面板条数变了才 `updateLayout`。浮层 `catalog` 改存 State（`referentialEqualityPolicy`），换目录即重组；点按检测不再以 catalog 为键（点按时现读）。面板模式下 `updatePanelCatalog` 只在面板为空或顺序相同时换列表与加载器。② 内置面板 `panelImages` 原先存下浏览过的全部图标，滚完整个列表后持有量随应用数增长，绕过 8 MiB 上限。改为格子离开组合时移除，滚回时经新增的 `PanelIconLoader.cached` 当场取目录缓存（不空一帧），请求回调在格子已离开时不写入。③ 切换用户：发布只比设置、保存时重读 `CurrentUser.id()`，旧用户任务在切换后完成会被采用并写进新用户副本。改为 `load` 开头取一次用户号，索引（`AppIndex.userId`）、快照（`AppCatalogSnapshot.userId`）都带上；协调器维护 `currentUserId`（启动时读，切换时取 `EXTRA_USER_HANDLE`），只采用、只保存同一用户的快照；切换时清空当前快照与待用副本、读新用户的副本（读完时已不是该用户则丢弃）；`invalidateUser` 清空侧边栏条目；侧边栏目录新增 `userSwitched`：取消在途读取、清空条目、换新用户本地副本（旧副本读取按代数丢弃），有侧边栏固定项时重读。④ 启动来源：`StarterQueryCache` 存 appId，`LaunchDecider` 却拿完整 callingUid / realCallingUid 比，非主用户下桌面快捷项、SystemUI 来源判定失效。新增 `sameApp`（取 appId 再比，`LaunchRules.PER_USER_RANGE`），uid 原值不改；新增单测 `secondaryUserSourcesMatchByAppId`。没有在 starter 里加「是否当前用户」校验：取当前用户要进 AMS，starter 可能持 WM 锁；不同应用 appId 必不相同，按 appId 比不会把别的应用认成 SystemUI / 桌面。⑤ 设置页图标：可见行的 remember 不含版本，包更新清掉缓存后行仍持有旧图；在途任务回调不查代数。`AppIconLoader` 加 Compose 状态 `version`，`invalidatePackages` 递增；广播时作废一次，`load` 重读入口后再作废一次（广播后、重读前按旧入口画的图不留）；行以 `version` 为键重新取图；代数变了的结果不进缓存、不回调。⑥ 补位统计：最近任务、使用统计、桌面解析改用 `createContextAsUser` 的 context（AOSP `ActivityManager.getRecentTasks` 与 `UsageStatsManager.queryUsageStats` 都传 `mContext.getUserId()`；ColorOS 未反编译确认），常用缓存按用户号区分；关掉 2026110910 登记的遗留项。编译（debug / release）、lintVitalRelease、starter 静态检查、单测（251 项，0 失败，3 跳过）通过；未构建、未安装。装机须重启 system_server，设置页覆盖安装即生效。

2026-11-09 源码 versionCode 2026110912：用户要求给内置「更多」面板预画第一屏。2026110910 去掉全量补图后，内置面板（侧边栏面板打不开时才用）打开时格子先空白再陆续出图。改为：选中「更多」时（`CornerRadialOverlayView.showMorePanel`，在 `onMorePanelRequested` 之前）取第一屏及相邻缓冲行（`PanelVisualMetrics.visibleItemRange(n, 0f)`，量不出布局取前 24 项）里仍是占位图的条目，经新增的 `PanelIconLoader.prefetch` → `ColorOsAppCatalog.prefetchPanelIcons` 以非紧急任务预画进 `iconCache`；之后格子请求同一图标时单线程顺序执行，`rawIcon` 先查缓存，不会重复绘制。代价：侧边栏面板正常打开时也会画这一屏（约二三十个，进 8 MiB 缓存，下次命中不再画）。编译、lintVitalRelease、单测（250 项，0 失败，3 跳过）通过；未构建、未安装。装机须重启 system_server。

2026-11-09 源码 versionCode 2026110911：用户要求优化「开机后头几次呼出扇形为空」。原因（读代码）：首次读目录要等锁屏解除、手势可用（`isGestureAllowed`），解锁前凭据加密区不可读；目录发布前 `catalogSnapshot` 为空。本次两处：① 新增 `RadialSnapshotStore`：每次真实目录发布后合并 10 s 写 `/data/system/flymefreeform/radial_snapshot_u<用户>.bin`（名称、塑形图标 PNG、角标、角标偏移、分圈数及决定扇形的设置 `Key`），单项 PNG 按位图对象复用，摘要相同不写；`start()` 读出后暂存，`applySettings` 时若 `Key` 与当前设置一致且目录未发布，采用为 `AppCatalogSnapshot(provisional = true)`（无面板、无高清预览加载器）；provisional 期间 `applySettings` 照常刷新并 reloadApps，真实目录发布即替换；设置变化使副本不匹配时丢弃。② `CornerRadialOverlayView.updateRadialAppearance`：当前扇形为空而新目录不空时 `replaceEmptyCatalog` 整体换上（预览、图像、面板图、布局），并按手指当前位置重新判定选中项，不必松手再呼出。编译（debug / release）、lintVitalRelease、starter 静态检查、单测（250 项，0 失败，3 跳过）通过；依赖 Bitmap 与文件，未写单测。未构建、未安装。装机须重启 system_server。

2026-11-09 源码 versionCode 2026110910：按用户定的顺序处理第二轮审查。① system_server 面板图标：去掉每次加载后遍历全部应用补图的循环（内置「更多」面板只在侧边栏面板打不开时才用），面板条目与快照一律用占位图，格子出现时按需画，`iconCache` 改为 8 MiB `LruCache`；扇形应用条目直接用塑形图，不再顺带把原图放进面板缓存；`shapedCache` 改为访问顺序最多 32 项。去掉随之无用的 `requestGeneration`。② 非主用户：新增 `CurrentUser`（`ActivityManager.getCurrentUser` + `UserHandle.getUserHandleForUid` + `createContextAsUser`），扇形目录 `getActivityList` 与 `ColorOsFreeformLauncher` 的组件校验、别名回退、`startZoomWindow` 用户号、`startActivity` 回退都按当前前台用户；协调器监听 `ACTION_USER_SWITCHED` → `invalidateUser` 清空名称、图标、预览与常用统计缓存后重读。侧边栏进程 `FavoriteGroupInjector` 的用户设置 XML 由写死 `/data/user/0` 改为按本进程 uid 取用户号（侧边栏 directBootAware，未用 filesDir；0 号用户路径不变）。未处理：最近任务、使用统计在 system_server 里按调用者（0 号用户）查询，分身用户下补位来源可能仍是主用户的。③ 恢复手势不再 `reloadApps`，只在目录为空时重读；包、语言、用户各有广播负责失效。侧边栏目录恢复时仍重读（侧边栏内改工具没有广播），未限频。④ `radialDependsOnForeground`：外圈固定项不少于数量上限且内圈都已自定义或不补位时，前台切换不刷新，只记 `panelOrderStale`，下次呼出时后台补刷一次。⑤ 模块关闭时 `ColorOsEntryCatalogBroker.release()` → `ColorOsSidebarEntryConnection.release()` 解除绑定；锁屏、横屏、游戏模式暂停仍保留热连接。⑥ `ActivityStarterZoomInjector.readField / intField` 按「类 + 字段名」缓存字段（含不存在的，`FieldSlot(null)`），不再每次 `getDeclaredField` 并抛异常；快照仍读两次（两阶段之间可能改状态，未合并）。⑦ 小窗触摸区域刷新只在功能开启时安排，按 task 作令牌，区域已受保护或功能已关即 `removeCallbacksAndMessages(task)` 取消余下几次（原先功能关闭时也会 4 次调用 `updateCaptionTouchRegion`）。编译（debug / release）、lintVitalRelease、starter 静态检查、单测（250 项，0 失败，3 跳过）通过；未构建、未安装。装机须重启 system_server，侧边栏进程另需 `pkill -f com.coloros.smartsidebar`（XML 路径）。

2026-11-09 源码 versionCode 2026110909：用户决定「固定的应用全都保持高清绘制尺寸」。审查指出浮层呼出时会请求并持有全部扇形项的高清图，2026110907 的 32 MiB 上限省不下内存，反而让被淘汰的项下次呼出重画。改为：扇形里的固定项（`settings.allPinnedTargets` 与本次扇形的交集）全部常驻 `pinnedPreviews`，不受上限淘汰，全部预取；补位项仍进 32 MiB `LruCache`，只预取放得下的；固定项变化时 `regroupPreviews` 在两处之间挪动已有位图，不重画。浮层 `disposeOverlay` 清空 `previewImages`，收起即放开引用。绘制尺寸不变。编译、lintVitalRelease、单测（250 项，0 失败，3 跳过）通过；未构建、未安装。装机须重启 system_server。

2026-11-09 源码 versionCode 2026110908：排除列表选择页 `CardSegment` 由 `clip(RoundedCornerShape)` + `background` 改为 miuix `squircleSurface(color, topStart, topEnd, bottomEnd, bottomStart)`，首末行圆角与 miuix Card（`squircleSurface`、16 dp）同为平滑圆角，消除 2026110907 记下的外观差别。编译、lintVitalRelease、单测（250 项，0 失败，3 跳过）通过；未构建、未安装。只影响 App 进程，覆盖安装即生效。

2026-11-09 源码 versionCode 2026110907：用户要求在保证功能的前提下减少重复操作与各类占用，按审查清单全部改。① 侧边栏目录：包广播不再 `invalidate()` 清空（清空后读取失败会让固定工具一直消失），改为 `packageChanged`：卸载（非 `EXTRA_REPLACING`）立即移除该包条目，其余保留，有侧边栏固定项时后台重读，读取途中有变化则完成后补读。② 设置页目录请求：系统端 500 ms 节流由静默丢弃改为回 `BUSY`（新协议码 15），客户端 600 ms 后同 ID 重发；页面暂停不再取消进行中的请求，恢复时合并（去掉 `onCancelCatalog` 链）。③ 语言：system_server 监听 `ACTION_LOCALE_CHANGED` → `ColorOsAppCatalog.invalidateLabels` 清名称、面板并重读，有侧边栏固定项时重读侧边栏目录；App 进程同样重读名称。④ 中央预览：同条目 / 外形 / 代数且尺寸够的在途任务合并，执行前再查缓存，选中项排图标线程最前（排队中的预取被选中时挪到最前）；缓存改为按字节 32 MiB 的 `LruCache`，预取只取放得下的前几项，避免与选中互相挤出。⑤ 字母顺序按应用列表、`labelEpoch`、语言缓存；「更多」顺序未变时不重建、不重发；补图让位留下的占位格在下次加载时补发。⑥ 设置页 `LauncherAppRepository` 改为只存组件与名称（图标占位），包变化只重读该包，图标经 `AppIconLoader` 按可见行加载（LIFO、可取消、12 MiB）；本机 537 个入口原先约 24–60 MB 位图常驻，现在随缓存上限。方形模板改由仓库提供。`rememberShapedIcon` 结果进 4 MiB 共用缓存。排除列表选择页由一张 Card 内全部行改为懒加载行（`CardSegment` 仿 Card 外观，圆角为普通圆角，与 miuix Card 的平滑圆角略有差别）。⑦ 应用列表状态 Loading / Ready / Failed：失败显示重试，读完为空显示「没有可添加的应用」。⑧ `SidebarCatalogStore.save` 先逐项比对（`Bitmap.sameAs`），相同不编码。⑨ `ColorOsSidebarEntries` 反射元数据按 ClassLoader 单格复用（bean 仍每次请求重查）；搜索 `AppSearch`：去首尾空格，纯字母查询对含汉字名称按 ICU `Han-Latin; Latin-ASCII; Any-Lower` 全拼 / 首字母匹配，后台过滤并缓存。编译（debug / release）、lintVitalRelease、单测（250 项，0 失败，3 跳过）通过；新逻辑依赖 Bitmap / ICU / LauncherApps，未写单测。未构建、未安装。装机须重启 system_server（目录、预览、语言、broker），侧边栏进程另需 `pkill -f com.coloros.smartsidebar`（反射复用）；设置页改动覆盖安装即生效。

2026-11-09 文档整理（用户要求，未改代码，versionCode 不变）：`CONSTRAINTS.md` 第十节只留现状（源码 / 未提交改动 / 设备 / GitHub / 待验证 / 下一步），逐次记录迁到本文件；原「本轮的坑」整理为第十一节「经验与坑」，原文留在本文件末尾。另：第一节补侧边栏目录链路与 SystemUI 前台判定；第二节补侧边栏目录、分身、DexKit 相关文件，去掉过时的 injector 行数；第三节新增「固定项里的侧边栏条目」，starter 段补 PM 缓存补种与预热；第四节新增「执行层」（原参数解包、只写纯小窗参数、写入回退）；第七节把 starter 日志并入表格并补缓存预热、`ZOOM_NATIVE_CHANNEL_`、面板工具排除日志；第九节重新编号并补混淆名按签名查找、decision 包纯 Kotlin、版本号三条；第六节补脚本不入库与关于页。

2026-11-09 源码 versionCode 2026110906：用户反馈「部分侧边栏版本会无法找到分身应用」，并要求顺带修面板工具入口。字节码对比 16.11.3 与 17.11.2：分身数据链路一致（`getShownApps` → `loadAllAppIfNeed` 按当前用户与 `OplusMultiAppManager.getMultiAppUserInfoList` 逐用户 `LauncherApps.getActivityList`，`createAppBean(info, user.hashCode())` 填 `mMutiUserId`）；`isCloned()` 只在 `OplusOSVERSION >= 38` 时问 `isMultiAppUserId`，否则只认 999。手头没有出问题的版本，根因未在真机确认。本次三处：① `SidebarEntryKind.fromNative` 不再只信侧边栏的 isCloned：应用条目（type 2，非 category 11）用户号有效且不同于当前用户即视为分身，参数改名 `nativeCloned`；工具、快捷方式、桌面快捷入口不受影响。`ColorOsSidebarEntries.entries` 的分身筛选改用同一判断。② `clonedActivityInfo` 精确匹配 activity 失败时退回该用户下该包第一个入口（与侧边栏 `findLauncherActivityInfo` 一致）；该用户下查不到入口仍丢弃。启动无需改：`startNormalApp` 以 `getMutiUserId` 调 `startActivity(intent, userId)` / `startActivityAsZoomWindow`，isCloned 只决定 activity 为空时 `queryIntentActivitiesAsUser` 补组件用哪个用户。③ 面板工具小窗入口按签名查找（见第三节「面板工具小窗」），修复 17.11.2（`n` 多了 Kotlin 回调参数）与 16.11.3（方法名 `m`）上 `PANEL_TOOL_ZOOM_UNAVAILABLE`。编译、单测（250 项，0 失败，3 跳过）通过；未构建、未安装。改动都在侧边栏进程，装机后 `pkill -f com.coloros.smartsidebar` 即可，无需重启 system_server。待验证：17.11.2 上 `PANEL_TOOL_ZOOM_INSTALLED_n_4`、16.11.3 上 `_m_3`，付款码 / 扫一扫进小窗；分身固定项能列出并打开分身而不是本体；出问题的侧边栏版本上分身是否出现。

2026-11-09 源码 versionCode 2026110905：用户反馈每次重启后固定项里的工具和分身应用要等几秒才出现。原因（读代码）：这些条目要在解锁后向侧边栏进程读取，冷路径为唤醒 provider → bindService → 侧边栏每 50 ms 轮询面板就绪 → 逐项画图标回传，7 秒超时后不重试。本次三处：① 新增 `SidebarCatalogStore`：每次读到完整目录后写 `/data/system/flymefreeform/sidebar_catalog_u<用户>.bin`（自定义二进制，图标 PNG，`AtomicFile` 原子写，内容摘要相同不写盘，空目录也写），`ColorOsEntryCatalogBroker.start()` 先读副本显示，真实目录到达后整体替换（`liveReceived` 之后的副本不再采用；`invalidate` 不写盘）。副本只用于显示，启动仍经侧边栏。② 目录读取失败（超时、侧边栏未就绪）按 2 s、5 s 各重试一次；外部刷新重置次数，`cancel` / `invalidate` 主动取消不触发重试。③ 分段耗时日志见第七节「侧边栏目录耗时」。编译、单测、lintVitalRelease 通过；`SidebarCatalogStore` 依赖 Bitmap，无 Robolectric，未写单测。未构建、未安装。装机须重启 system_server（侧边栏端日志改动另需 `pkill -f com.coloros.smartsidebar`）。待验证：首次装机后解锁取到目录即写出副本（`ls -l /data/system/flymefreeform`）；再重启 system_server 后，解锁前已记 `SIDEBAR_CATALOG_CACHE_READY_`，解锁后扇形里工具与分身应用立即可见；副本显示期间点工具能否打开（经冷路径，可能稍慢）；两行耗时日志各段数值；SELinux 拒绝写 `/data/system` 时见 `SIDEBAR_CATALOG_CACHE_WRITE_FAILED`。

2026-11-09 源码 versionCode 2026110904：缩短开机后 starter 应用信息缓存的冷窗口（`StarterQueryCache`）。① `seedFromLaunch`：每次读启动快照时，用 Request 已解析的目标 `ActivityInfo.applicationInfo` 补入「是否第三方 + uid」；发起方为 SystemUI 或桌面且 callingUid == realCallingUid 时记下其 uid（`observedUids`，PM 结果到位后以 PM 为准）。只读字段、写内存，可在持 WM 锁时调用。② 预热先单查 `com.android.systemui`、`com.android.launcher`，再读全量列表。③ uid 统一按 appId（去用户号）保存。④ 新增常开 INFO `STARTER_QUERY_WARMUP_READY_PRIORITY_<ms>MS_APPS_<ms>MS_<n>_OWNERS_<ms>MS`（自首次触发预热起算），用来实测开机冷窗口。编译、单测、starter 静态检查通过；未构建、未安装。装机须重启 system_server。待验证：开机后这行日志的三段耗时；开机后立即点链接 / 通知是否仍出现误判全屏。

2026-11-09 源码 versionCode 2026110903：小窗跳转全量检查后修读代码能确认的三处。① `ActivityStarterZoomInjector.optionsBundle` 原先对 `Request.activityOptions`（SafeActivityOptions，无 toBundle）调 toBundle 必然失败，原参数恒为 null：`optionsAlreadyZoom`、`optionsFullscreen`（2026110902 的通知全屏标记）都读不到。改为先试 toBundle，不行再按 AOSP `mergeActivityOptions` 合并 `mOriginalOptions` 与 `mCallerOptions`。原参数只用于判定；注入与延迟重开仍只写小窗参数（`ZoomLaunchSpec.bundle(null)`），不并入原参数——原参数可能带远程动画、launchTaskId 等受限字段，以 callingUid 重新包装会在 SafeActivityOptions 权限检查抛 SecurityException。行为变化：已带小窗参数的启动现在走 `ZOOM_NATIVE_CHANNEL_<reason>` 不再覆盖。② `setActivityOptions` 不可用时的回退由写 `starter.mOptions`（之后会被覆盖，无效）改为 `SafeActivityOptions.fromBundle(Bundle, pid, uid)` 写 `Request.activityOptions`。③ 面板工具小窗（`SidebarHookInstaller.applyToolLaunchZoom`）补查排除列表，跳过记 `PANEL_TOOL_ZOOM_SKIPPED_EXCLUDED_<包>`。另把第十节「登录授权类仍全屏」改为与代码一致（inter_app 注入）。编译、单测（249 项，0 失败，3 跳过）、starter 静态检查通过；未构建、未安装。装机须重启 system_server，面板工具改动在侧边栏进程（重启 system_server 后另 `pkill -f com.coloros.smartsidebar`）。待验证：`ZOOM_NATIVE_CHANNEL_` 是否出现（出现即原参数已能读出）；通知、桌面卡片、快捷项、链接小窗与之前一致；前台应用通知全屏且无 `ZOOM_INJECTED_` 跟随。

2026-11-09 源码 versionCode 2026110902：修正 2026110901。复查发现 2026110901 的前台判定写在 `NotificationZoomSourceHook`，而该类从未安装（SystemUI 有意不接管点击入口），通知小窗实际由 `NotifBundleHook.rewriteBundle` 写参数，所以前台规则没生效。本次：① 前台判定移入 `NotifBundleHook`，成立时只写 `android.activity.windowingMode=1`，system_server `reasonFor` 首段与抢占路径 SystemUI 分支见 `optionsFullscreen` 即不改写（日志 `ZOOM_SKIP_SYSTEMUI_FULLSCREEN_<包>`），撤销 2026110901 的来源标记 2 与 `SOURCE_NOTIFICATION_FULLSCREEN`；② `NotifBundleHook` 跳过名单由 systemui / android 改为 `LaunchRules.SKIP_TARGETS`，设置、权限控制器发的通知不再开小窗；③ 删除未安装的 `NotificationZoomSourceHook`（含会丢点击的延迟浮窗路径与探针）。单测改为 `systemUiExplicitFullscreenKeepsNotificationFullscreen`、`untrustedFullscreenOptionsDoNotBlockInterApp`；249 项，0 失败，3 跳过；release Kotlin 编译通过。未构建、未安装。装机须重启 system_server（连带 SystemUI）。待验证：前台全屏应用点自己的通知全屏打开，且转场、横幅收起与原生一致；桌面或其他应用在前台时仍小窗；该应用正在小窗时仍小窗；`NOTIF_FOREGROUND` 前台包是否正确（为 null 或总是桌面，说明 SystemUI 取不到任务列表）；设置类通知全屏。风险：若 ColorOS 自身给某些通知启动写了 windowingMode=1，这类通知会改为全屏，日志 `ZOOM_SKIP_SYSTEMUI_FULLSCREEN_` 出现在非前台场景即属此情况。

2026-11-09 源码 versionCode 2026110901（跨日，原记为 2026110852；**未生效，已由 2026110902 取代**）：用户要求点击通知时，若通知所属应用已在前台，就直接全屏跳转，不在前台才小窗跳转。SystemUI 侧 `NotificationZoomSourceHook` 新增前台判定（见第三节「通知目标已在前台」）：成立时 `onNotificationClicked` 不进浮窗通道，`startNotificationIntent` 写来源标记 2；`LaunchDecider.reasonFor` 在标记 1 判定后、`systemUiPending` 前遇到标记 2 即返回 null（记 `ZOOM_SKIP_NOTIFICATION_FOREGROUND_<包>`）。补单测 `foregroundNotificationMarkerKeepsFullscreen`。编译、单测通过，未构建、未安装。装机须重启 system_server 与 SystemUI（两边都改了；重启 system_server 会连带 SystemUI）。待验证：前台全屏应用收到自己的通知，从面板 / 横幅点击后全屏打开；桌面或其他应用在前台时仍小窗；该应用正在小窗里时仍走小窗；`getRunningTasks` 在 SystemUI 能否取到窗口模式与 isVisible（日志 `ZOOM_NOTIFICATION_FOREGROUND_*`；点击后没有这条即未取到前台全屏任务）。

2026-11-08 GitHub 清理（用户要求）：用户已删除 Release v1.4.6；随后删除远端与本地标签 v1.4.6，并删除 15 次旧的「Build APK」Actions 运行及其产物，只保留 v1.4.8 提交 `17f7078` 的那一次（成功）。现存 Release / 标签：v1.3.6、v1.3.7、v1.3.10、v1.4.8。

2026-11-08 22:06 安装 1.4.8（2026110851，sha256 `86c49cf145d74b522799de80aae7ae2456bf8c51682a8158aa8c4f004e4c6007`，覆盖 1.4.7 `e06501ef…4eb7`）并经 restart-gate 重启 system_server。重启后 adb 断开，未读日志、未验证；待验证：4 个进程 `MODULE_CONFIG_READY`，改「更多」颜色后下次呼出即变色，开关中央预览后立即预取。按用户要求推送 main 并发布 GitHub Release v1.4.8（说明覆盖 1.3.10 以来改动）。注意：GitHub 上 v1.4.6 只有标签、没有 Release（第十节此前记为已发布，实际 Release 列表只有 v1.3.6 / v1.3.7 / v1.3.10）。

2026-11-08 构建 1.4.8（2026110851），`out/FlymeFreeform-v1.4.8.apk`，sha256 `86c49cf145d74b522799de80aae7ae2456bf8c51682a8158aa8c4f004e4c6007`，release.keystore 签名，v1/v2/v3 与 zipalign 校验通过。在 1.4.7 基础上加入「更多」颜色改后立即生效（2026110850）与本轮两处修复（2026110851）。未安装、未提交、未发布；设备仍为 1.4.7。装机须重启 system_server。

源码 versionCode 2026110851：复查后修两处。① 取色器选中全透明黑色（0x00000000）时，因与「跟随系统」的 0 相同，会被悄悄存成跟随系统；新增 `ModulePreferences.customRadialMoreColor`（透明度抬到 0x33 以上，结果永不为 0），取色器「确定」与对话框预览改用它，`coerceRadialMoreColor` 对非 0 值复用它；补单测 `pickedTransparentBlackStaysCustom`。② 删去已无调用的 `FrameworkConnectionRepository.setRadialMultiArcEnabled` / `setRadialArcCount`（圆弧条数统一走 `setRadialArcs`）。两处都只在模块 App 进程。编译、单测通过，未构建。装机须知不变：相对设备上的 1.4.7（2026110849），2026110850 改了 system_server 内的扇形绘制（协调器传入「更多」颜色），装下一包仍须重启 system_server。

源码 versionCode 2026110850：用户反馈 1.4.7 把「更多」按钮改成白色后不立即生效。原因（读代码确认）：扇形按 `catalog.settings.radialMoreColor` 绘制，目录快照只在 `AppCatalogSnapshot.matches()` 不匹配时才重新加载，而 `matches()` 不含新加的颜色，要等前台切换等其他原因重载目录才变色。改为协调器在呼出时用最新设置 `lastSettings.radialMoreColor` 传给 `CornerRadialOverlayView.begin(moreColor)`，与中央预览各项同法；`matches()` 补上 `radialCenterPreview`、`radialCenterSizeDp`，开关或调整中央预览后立即重新加载并预取。编译、单测、lintVitalRelease 通过，未构建。1.4.7 的 GitHub Release 尚未发布。

2026-11-08 构建 1.4.7（2026110849），`out/FlymeFreeform-v1.4.7.apk`，sha256 `e06501ef387fe95d04d8e43d2850ea1e6e74afa6b9b7448b4103ed7f29854eb7`。在 1.4.6 基础上加入「更多」按钮颜色与设置页整理。未安装、未提交、未发布。装机须重启 system_server（颜色绘制与中央预览在 system_server）。

源码 versionCode 2026110849：设置页整理（用户要求）。新增 `SettingsGroup`（miuix `SmallTitle` + `Card`）。扇形设置分五组：呼出（触发位置、触发范围、保持挂起、横屏 / 游戏模式暂停）、扇形内容（扇形应用、补位来源、数量上限、扇形多圆弧）、外观与判定（图标形状、「更多」按钮颜色、命中范围）、中央预览（关闭时收起「显示应用名」「图标大小」）、「更多」面板（显示原有列表）。横屏 / 游戏模式按用户决定从小窗设置移回扇形设置。小窗设置：排除列表卡片置顶，下分「打开方式」（小窗跳转、解除限制、小窗数量）与「小窗操作」（小窗关闭、快速上滑）。扇形多圆弧由「开关 + 展开选条数」改为与触发位置相同的弹出列表 1–4 条，经 `setRadialArcs` 一次写入（1 条即关闭多圆弧并保留上次条数）；删去 `MultiArcPreference`、`ArcCountSelector` 及其四条字符串，摘要去掉「「更多」入口保留在默认圆弧」。横屏 / 游戏模式摘要按用户要求去掉「小窗功能不受影响」（README 同步）。设置的存储与功能不变。编译、单测、lintVitalRelease 通过，未构建。

源码 versionCode 2026110848：新增「更多」按钮颜色（扇形设置，图标形状下方）。键 `radial_more_color`（ARGB Int），0 为跟随系统；自定义保留透明度但不低于 0x33（`coerceRadialMoreColor`）。扇形 `drawMoreItem` 自定义时底板取纯色，三点按 `MoreButtonColors.dotFor` 在 0xFF181818 / 0xFFE9E9E9 中取 WCAG 对比度更高者（半透明按叠在黑色上算），白底即黑点（用户要求）。设置页用 miuix `OverlayDialog` + `ColorPicker`，带按钮预览，可「跟随系统」或「确定」。经设置广播全量快照热生效。编译、单测 246 项（0 失败，3 跳过）、lintVitalRelease 通过，未构建。

2026-11-08 构建 1.4.6（2026110847，sha256 `a78dda82c691df55237087765c62566c63cff734b72283cfb251ddd94d6ce517`），推送 main（`8ea53c4`）。按用户要求删除 GitHub Release v1.4.5 及其标签，发布 v1.4.6（说明覆盖 1.3.10 以来改动，并加入中央预览不再闪动）。1.4.6 尚未装机；设备仍为 1.4.5。

源码 versionCode 2026110847：中央预览高清图全部缓存。用户确认取舍：内存随扇形条目数（默认尺寸每张约 0.9 MB，18 项约 16 MB），换取不再先显示小图再换图。目录每次加载后 `prefetchPreviews` 在图标线程补画缺的那几项、裁掉不在扇形里的（记 `SYSTEM_PREVIEW_PREFETCH_<缺>_OF_<总>`），中央预览关闭时不预取也不保留；包变化只丢该包的图，不再整体清空。视图请求与预取共用 `PreviewIconSizing.renderSizeFor`，保证按宽度判定命中；视图去掉 4 张上限，呼出时请求全部条目（同步命中）。此前设备日志同一应用高清图重复绘制 8–10 次，全缓存后每项只画一次。

源码 versionCode 2026110846：用户反馈中央预览图标闪一下、像变亮。设备日志：深色模式开启；`PREVIEW_ICON_SOURCE_*_AdaptiveIconDrawable_284x284_TO473_raw0`，即高清图直接绘制 AdaptiveIconDrawable，未走 ColorOS 原生塑形（扇形小图走），两张图亮度与占比不同；且每次呼出都清空预览，缓存命中也晚一帧才换图。改为：高清图与扇形同一条处理链（`shapedIcon` 新增按预览边长重建的参数，`IconShapes.apply` 参数同扇形，独立 renderer 实例供图标线程用），去掉读 APK 原始资源的回退；缓存命中时回调在主线程同步写入，首帧即用高清图。日志改为 `PREVIEW_ICON_SOURCE_<pkg>_<类>_<尺寸>_TO<px>_native<0|1>`。代价：主题只给低清位图的图标，清晰度回到系统水平。未构建。

2026-11-08 发布 v1.4.5：`refactor/starter-hook` 按功能补提交 5 个后快进合入 main，再合并远端 `f98371b`（README 兼容系统改为 ColorOS 15），推送 ZaiLinn/FlymeFreeform main（`f6dfaca`）。Release v1.4.5 附 `FlymeFreeform-v1.4.5.apk`（sha256 `61288bd1…d014`），说明覆盖 1.3.10 以来的改动；1.4.0–1.4.4 未单独发布。用户已在设备上确认「更多」不再左移。

2026-11-08 20:52 安装 1.4.5（2026110845，sha256 `61288bd1b65513b6d3465a3911b142e0289b9cfb099e29785d02fec34841d014`）并重启 system_server，随后强行停止模块 App。重启后 4 个进程均 `MODULE_CONFIG_READY`，starter 钩子、手势 coordinator、双击横条钩子已装；`STARTER_EFFECT_FAILED_query_warmup` 不再出现，无锁违规、探针不可用或手势许可失败。首次打开「更多」：`PANEL_INDEX_INSET laidOut=true` 紧接 `PANEL_INDEX_INSET_BEFORE_DRAW`，即内边距在首帧绘制前补上、该帧被取消（pre-draw 时已布局属正常）；是否仍左移待用户目测。X 小窗内换链接重开尚未测。

源码 versionCode 2026110845：用户反馈 1.4.4 打开「更多」时图标仍左移。原因推断（adb 未连接，未看日志）：建卡片时列表还没挂上本面板 adapter，`recyclerView(exact = true)` 找不到目标；adapter 与列表首次布局同帧完成，逐帧摆索引条时才补内边距，已晚一帧。改为在卡片上挂 `OnPreDrawListener`，每帧绘制前查找，一旦找到列表就补内边距并取消该帧，记 `PANEL_INDEX_INSET_BEFORE_DRAW`；每次补内边距记 `PANEL_INDEX_INSET laidOut=<bool>`，设备上据此核对。关闭面板时移除监听。编译与单测通过，未构建。

同一 versionCode 另修：1.4.4 日志在 system_server 启动早期出现一次 `STARTER_EFFECT_FAILED_query_warmup`（`IActivityManager.registerReceiverWithFeature` 空指针，AMS 尚未就绪），预热整体放弃；之后首次启动经过钩子时会重试，影响仅限开机最初几次启动。去掉 `install()` 里的预热，改由首次启动触发。

2026-11-08 构建 1.4.4（versionCode 2026110844），`out/FlymeFreeform-v1.4.4.apk`，sha256 `fe3e0d8c8c4a40a934542d7272f535c2093709370aa960b9bcf650370bf00bf7`。在 1.4.3 基础上加入设置页合并与说明修正。未安装、未重启。装机须重启 system_server。

源码 versionCode 2026110844：合并页里横屏 / 游戏模式的说明由「暂停模块增强」改为「暂停扇形呼出；小窗功能不受影响」，与 `ModuleEnvironmentState` 实际行为一致（只有排除列表同时影响小窗跳转）。README 设置说明改为两组，删去「生效范围」小节与 `docs/images/settings-scope.png` 引用，`settings-zoom.png` 待重截。编译、单测与 lintVitalRelease 通过。

源码 versionCode 2026110843：合并入口与页面标题由「小窗交互与生效范围」精简为「小窗设置」，继续共用同一字符串资源，release Kotlin 编译通过。仅文案调整，排除列表位置与说明不变；未构建 APK、未安装、未重启、未推送。

源码 versionCode 2026110842：基于当前目录最新代码，将「小窗交互」与「生效范围」合为「小窗交互与生效范围」。排除列表在合并页最上方；排除列表说明、空状态和「小窗跳转」摘要明确共用同一列表。原小窗设置与横屏 / 游戏模式暂停保留；首页只显示合并入口，旧生效范围导航状态兼容到合并页。release Kotlin 编译通过，单测 242 项、3 项跳过、0 失败；静态检查通过。保留本轮开始前已有的未提交启动链路与图标优化改动。仅编译与单测，不构建 APK、不安装、不重启、不推送。

2026-11-08 构建 1.4.3（versionCode 2026110841），`out/FlymeFreeform-v1.4.3.apk`，sha256 `9e02bf894bc392db8188bf759e8c2ead8e90d8715b3cb37eedadd88589997b14`。包含 refactor/starter-hook 分支全部改动及本轮复核修复，未安装、未重启。装机须重启 system_server。

源码 versionCode 2026110841：复核审计修复后的五处问题。
1. 延迟重开写入小窗参数时带原调用方 pid/uid（`setActivityOptions(Bundle,int,int)`，缺失时用 `SafeActivityOptions.fromBundle(Bundle,int,int)`），不再用单参数版本在主线程以 SYSTEM_UID 包装调用方 options；回退原参数启动时沿用复制来的 SafeActivityOptions。两种写法都不可用时不关窗、不接管。
2. 手势许可广播按值去重，客户端请求、用户切换、启动时强制发送。
3. `UserManager.isUserVisible` 反射失败时退回「是否当前前台用户」并记一次 `ZOOM_REOPEN_USER_VISIBILITY_FALLBACK`；重开校验不通过记 `ZOOM_REOPEN_INVALID_<pkg>`。
4. 锁探针增加 mSupervisor / mController 路径和按类型查找；探针不可用时控制中心查询仅在 execute 阶段放行，不再整体失效。
5. starter PM 缓存在用户解锁 / 切换时只增量补充，不清空；查询带 MATCH_UNINSTALLED_PACKAGES。
仅编译与单测（242 项，0 失败，3 跳过）。

**装机须知**：手势许可由 system_server 发布，只重启 SystemUI 或桌面而不重启 system_server 时，新 SystemUI 收不到许可，角落手势不可用。装这一批必须重启 system_server。

源码 versionCode 2026110840：中央预览绘制倍数由 2× 降为 1.5×（`PreviewIconSizing`），默认 96 dp、约 3.5 倍密度下单张由约 2.8 MB 降到约 1.6 MB，4 张缓存常驻 system_server 由约 11 MB 降到约 6 MB；最大尺寸仍触及 1024 px 上限（4 MiB）。仅编译与单测。

第五组源码 versionCode 2026110839：starter 静态扫描遇到缺目录、空目录或读取错误均失败，并检查跨行调用与尾随 lambda；Gradle 编译 / check、CI 和本地发布接入。取消任务与队列拒绝均统一执行完成通知，回归验证准备状态释放。最终复核增加用户隔离记忆、重开票据身份、当前 Request 目标解析和 PM 缓存写回代数，队列拒绝后允许重试；关闭前核对任务用户及用户可见性。扫描器是源码模式检查，不替代锁序审查或运行时探针。

第四组源码 versionCode 2026110838：面板异步写回绑定当前会话并复核搜索和原生列表开关；图标失效与缓存写回按同一代数原子校验。中央预览按屏幕尺寸 2× 重绘（最长 1024 px、缓存 4 张），低清主题位图尝试 APK 自适应/矢量源（用户已授权优先清晰度），最终统一按设置的圆形 / 圆角方形塑形；新增 PREVIEW_ICON_SOURCE 日志用于确认来源。位图保留原 alpha，不再强制圆形裁切；侧边栏工具仍受原始位图分辨率限制。

第三组源码 versionCode 2026110837：手势入口收到 system_server 经 UID 校验的许可后才接管；许可在前台、设置、环境和用户切换时更新，触摸路径只读缓存。广播有调度延迟，前台切换边界仍须设备验证。窗外关闭只接受从 DOWN 实际捕获的流。黑名单变更在串行写入队列按包合并，选择页即时反馈待确认状态。

第二组源码 versionCode 2026110836：所有改写先核对排除和锁屏；用户来自 Request；当前启动控制中心来源仅在探针确认未持 WM 锁时读取，嵌套阶段共享本次观察；PM 查询移到专用工作线程。延迟重开复制 Request 身份与 grants，执行前复核，失败回退原参数启动；反射预检失败则不关窗、不接管。

审计修复五组已完成，源码 versionCode 2026110839。方案见 `docs/plan-audit-fixes.md`，逐项结果见 `docs/audit-fixes-2026-11-08.md`。第一组修复连接死亡、身份校验、准备任务取消释放、绑定上下文和用户切换；最终复核统一了三个接收端的异常处理。仅编译与单测，不操作设备。

## 2026-11-08 状态表与实测（已过时，留档）

以下为迁出时第十节的旧状态表、实测与下一步，版本信息停在 1.4.7 前后，现状以 `CONSTRAINTS.md` 为准。

更新时间：2026-11-08（本地重构；未查询或操作设备）

| 项 | 值 |
|---|---|
| 版本 | 工作副本 1.4.7 / 2026110850（1.4.7 包为 2026110849，`out/FlymeFreeform-v1.4.7.apk`，sha256 `e06501ef…4eb7`，未装机、未发布；GitHub 最新 Release 为 v1.4.6）；设备为 1.4.5 / 2026110845（`out/FlymeFreeform-v1.4.5.apk`，sha256 `61288bd1…d014`，2026-11-08 20:52 装并重启 system_server，之后强行停止模块 App）。v1.3.10 曾按用户要求覆盖重发（破例未加 versionName），重发资产为 2026110821 |
| 本地构建 | `out/FlymeFreeform-v1.4.1.apk`，sha256 `04d7b8e7662f83d8542a036cef3d3c73689fd85596cfe51caa62d554c63cccbf`，用户密钥签名，已装设备、未发布。1.4.0 `1e847fe3…f358` 有死锁勿用；1.3.12 `2ce93a54…6cfd`；1.3.11 `b56e0400…42bd`；`out/FlymeFreeform-v1.3.10.apk` 为重发的 Release 资产 `afc03c6b…65f0`，首发包 `83d94a5e…2bf3` 备份在 `tmp/FlymeFreeform-v1.3.10-83d94a5e.apk` |
| 工作副本 | `~/Documents/Code/FlymeFreeform`，本轮分支 `refactor/starter-hook`，基线 `c7f8c21`；A=`55b6510`，B=`813837b`，框架服务=`4825e37`；审计第一至四组=`763f57e` / `725dfcd` / `576e8f3` / `6604b29`，第五组包括最终复核与构建闸门，均未推送。原 main = `origin/main`。v1.3.7（`5ce7888`）之后：用户网页提交 `bdb6a30`（README 致谢精简），`a454470` 新设置项、`5a89d85` 「更多」原有列表 + 字母索引 + 回桌面收起、`9d1c35d` 扇形外大内小 + 屏幕中央所选应用 + 内置面板回桌面收起、`c1d0893` 添加应用快速点击闪动，之后是 1.3.10 版本号与文档、用户网页提交 `e3681e4`（README 去上游链接）、方形「更多」修复、README 换 png 截图，重发 1.3.10 的版本号与文档（v1.3.10 tag 所指，`b3562e0`）。之后 2026-11-08 按功能分组提交 1.3.11–1.4.3 的改动：`8d015c7` 侧边栏目录缓存与悬空绑定、`530fd2b` 设置广播共享身份与完整快照、`bd5f490` 「最近」排除扇形固定项、`1dd1297` 「更多」打开时图标左移、`bdc667b` 双击横条关闭、`aef03c5` 应用互跳小窗与死锁修复（含关窗重开）、`ac8cb85` 文档与版本号；中间提交未单独编译，HEAD 编译与单测通过；均未推送。前三个功能提交均在临时 worktree 单独编译通过；`upstream` = Hoerire |
| 已发布 | Release [v1.3.10](https://github.com/ZaiLinn/FlymeFreeform/releases/tag/v1.3.10)，资产 `FlymeFreeform-v1.3.10.apk`（sha256 见「本地构建」）；上一版 v1.3.7 资产 `a8344244…61d3` |
| 设备已装 | `04d7b8e7`（1.4.1 / 2026110827），17:54 装后经 restart-gate 重启 system_server。此前 1.4.0 在 17:20:50 死锁、17:23 看门狗软重启 |
| 设备另有 | 旧包 `io.github.hoerire.flymefreeform` 仍在，应在 LSPosed 停用并卸载 |
| 设备反馈 | 1.3.8：中央卡片有黑底板、图标发糊、字母索引条不出现，1.3.9 已改；1.3.9：添加应用快速点击闪动，1.3.10 已改。第 2 圈「其余变灰」核实为该圈已存满 5 项（两个工具 + DeepSeek、Duolingo、Firefox），按设计 |
| 未打包改动 | 框架服务恢复：按底层 Binder 去重，清理死亡服务并重新选主；监听 Binder 死亡且写入前复核，失效旧对象不再导致 MultipleServices 锁住设置页。 B：纯 Kotlin 判定与启动记忆已迁出，injector 为 342 行，日志码及条件顺序保留。 A 锁安全重构：统一副作用出口、WM 锁检测、静态检查、异步 PM 缓存；未构建 APK。 相对设备上的 1.4.2：① X 已在小窗时再点链接，1.4.2 放行普通启动后 X 以 CLEAR_TASK 重启自身、系统不认小窗参数而退成全屏（日志 `setScenario --> 0`、无 `collectFlexibleTaskInfo`）；改为吞掉本次启动，主线程先经 `ZoomTaskCloser`（OutsideTapCloseHookInstaller 在 `addFlexibleTask` 时记下 FlexibleTaskController）对该包最上层普通小窗 `exitFlexibleTask`，400 ms 后复制原 Request 并以 zoom options 重开，保留调用者 / 用户 / grants，失败尝试原参数启动，走首次打开的路径（日志 `ZOOM_REOPEN_SCHEDULED_` / `ZOOM_REOPENED_closed…_started…`）；Telegram 重发的同一链接按 linkKey 吞掉。② 「更多」原有列表模式打开时图标往左挪：字母索引条在拼音算完、列表已布局后才给列表加右内边距，网格列宽重算；改为建卡片、绑定 adapter、逐帧找列表时就先预留（只认绑定了本面板 adapter 的列表），索引条不显示也不撤。本机编译与 183 项单测通过，未构建。
| 未在设备验证 | 框架服务：后续用户授权重启 system_server 后直接打开设置页应自动恢复连接和写入，不需要强停；重复包装同一 Binder 不报多服务，不同有效服务仍保持隔离。 B 完整回归：① Telegram→Chrome 小窗；② Telegram→X 直达帖子；③ X 小窗中再点链接，关旧窗重开并送达新帖子；④ 连点十次以上不死锁、无 STARTER_LOCK_VIOLATION_；⑤ 登录/授权回传；⑥ 页内跳转；⑦ 通知、磁贴长按、桌面卡片/快捷项/设置；⑧ 桌面全局搜索仍全屏。 A：通知面板收起改为队首投递后小窗是否被遮挡；启动早期冷缓存、安装/卸载后目录缓存恢复；锁屏/解锁切换；Telegram 连点 X/Chrome 不死锁且无 STARTER_LOCK_VIOLATION_。 下一包：Telegram 点 Chrome 链接小窗（`_link1` + `ZOOM_PREEMPTED_com.android.chrome`）、点 X 链接小窗且直达帖子（中转页重启是否接受注入，`ZOOM_INJECTED_self_relaunch`；若被 FlexibleTaskController 拒则全屏但链接仍在）、X 已在小窗时再点链接：旧小窗关闭后重开为小窗并显示新帖子（`ZOOM_REOPENED_closedtrue_startedtrue`），不再退成全屏；「更多」原有列表模式打开时图标不再左挪；装新包并重启一次后，再装新包不重启，改图标形状 / 圆弧数 / 角落开关 / 小窗关闭方式能否即时生效（详细日志 `SETTINGS_BROADCAST_APPLIED_FULL`）；「双击横条」关普通小窗（`CAPTION_DOUBLE_TAP_CLOSE`），迷你小窗双击仍为原生行为，该模式下点窗外不关、连点链接不再卡死 system_server；登录授权类 startActivityForResult 按 inter_app 注入小窗参数且结果能回传（被柔性任务控制器拒绝时全屏，单测 requestCode=100 断言 inter_app）；应用内翻页不被误接管；1.3.12：增删固定项不重启即生效；之后再装新包（不重启 system_server）增删固定项仍生效；搜索添加时连点应用，列表顶部不再弹出「正在读取侧边栏目录」、工具与快捷入口不变灰、不再出现「不可用」；方形模式下「更多」底板外形、大小是否与相邻应用图标一致（深浅色各看一次）；1.3.10 相对 1.3.9 的添加应用闪动修正与三处占用优化；屏幕中央所选应用卡片（无底板、高清图标、应用名省略、横屏位置、换项弹动）；扇形图标外大内小（方形模式圆角是否被裁、选中放大是否越出屏幕）；字母索引条（日志 `PANEL_ALPHABET_SHOWN` / `PANEL_ALPHABET_NO_LIST`；右列图标能否点、跳转、震动、深色文字颜色）；回桌面收起「更多」（`ALL_APPS_CLOSE_SYSTEM_DIALOGS_<reason>` / `SYSTEM_MORE_PANEL_CLOSE_<reason>`）；跟随系统时 LuckyTool 上限生效 |
| 待办计划 | 全量审计修复见 `docs/audit-fixes-2026-11-08.md`，五组完成，本轮 240 项测试 / 3 跳过、静态回归 9 项通过，仅待获准设备验收。前轮 `docs/plan-starter-hook-refactor.md`：starter 钩子锁安全（统一副作用出口、运行时锁检测、构建前静态检查）与启动判定纯函数化 + 真实日志回归测试，A、B 与框架服务自动清理均已完成；release Kotlin 编译通过，215 项测试中 212 项通过、3 项跳过。B 覆盖 17 类场景、时间窗边界、惰性查询与失败路径，服务候选清理新增 6 项测试。未构建 APK、未装机、未重启、未推送 |
| 待查 | 装新包后增减应用扇形不更新：已定位为固定项广播未共享身份（`sentFromUid` 为 -1 被接收端丢弃），平时只靠 LSPosed 远程配置回调，而模块重装后 Hook 进程里该回调不再触发。1.3.12 已让广播共享身份；图标形状、圆弧数等其他设置原先只走远程配置，现已改为全量广播（待设备验证）；1.4.0 重启后侧边栏目录不可用：推断为上游已修的悬空绑定（system_server 起来后侧边栏进程晚到，连接回调晚于超时即放弃建链且不再重绑），现有版本无日志可证，移植后看 `SIDEBAR_ENTRY_TIMEOUT_LIST` 与是否恢复 |
| 单元测试 | 240 项：237 项通过、3 项跳过，release Kotlin 编译通过；静态脚本 9 项回归通过，Gradle 编译前拒绝违规调用的集成验证通过；injector 398 行；未构建 APK |
| 工具 | `local-release.sh`、`restart-gate.sh`（均列入 `.gitignore`，不随仓库分发） |
| 关于页 | 只保留一个「项目地址」→ `ZaiLinn/FlymeFreeform`；上游地址与致谢在 README |
| 签名 | 用户密钥 `release.keystore`（`CN=ZaiLinn`）；口令从环境变量 / 钥匙串 / 交互读取 |

### 实测（`dc0bcfef` 版，即目录两步发布首版，重启后）

- system_server 重启后约 46 秒扇形就绪：`SYSTEM_CATALOG_RADIAL_552MS_6`；第一次呼出 `APPS_6`
- 之后每次刷新扇形 2–98 ms；首次补齐面板 530 个图标 14.4 秒（期间「更多」为空），之后 11–50 ms

### 下一步（按序）

0. 当前授权仅源码修复、编译、单测与静态检查，已完成；未获新的装机授权前不构建 APK、不安装、不重启。下一轮设备验收按 `docs/audit-fixes-2026-11-08.md` 的清单执行。
1. 卸载旧包 `io.github.hoerire.flymefreeform`
2. 新包上逐项实测多圆弧、补位方式、跨圈移动、震动、预览、「更多」面板首开耗时（`SYSTEM_CATALOG_PANEL_ORDER`；图标形状让面板图标多一步处理）
3. 待评估：滑选时显示应用名

## 坑（原文）

长期有效的结论已整理进 `CONSTRAINTS.md` 第十一节；此处保留原始记录与证据路径。

- 扇形视图里只跟显示有关的新设置（如「更多」按钮颜色）不能读 `catalog.settings`：目录快照只在 `AppCatalogSnapshot.matches()` 列出的字段变化时重载。新增显示类设置应由协调器在 `begin()` 时从 `lastSettings` 传入；影响目录挑选或预取的设置要加进 `matches()`。

- **重启后设置页报「检测到多个框架服务」**：1.4.2 重启 system_server 后马上打开设置页出现，强停应用再开即恢复「已连接」。
  推断 lspd（未随 system_server 重启）在模块应用启动时把服务递了两次，`liveServices` 里有两个对象，于是禁用写入防配置分叉；未取到绑定日志（应用日志超配额被丢）。现已增加底层 Binder 去重与死亡清理，待用户授权设备验证。
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
