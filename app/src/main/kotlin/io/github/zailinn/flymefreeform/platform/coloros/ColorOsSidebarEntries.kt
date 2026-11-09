package io.github.zailinn.flymefreeform.platform.coloros

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.IInterface
import android.os.UserHandle
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import io.github.zailinn.flymefreeform.apps.SelectableEntry
import io.github.zailinn.flymefreeform.config.PinnedTarget
import io.github.zailinn.flymefreeform.config.PinnedTargetCodec
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 侧边栏目录及执行适配；不保存原生条目，不自行初始化厂商数据所有者。
 * 每次请求新建一个实例（[resolvedBeans] 只在本次请求内有效），反射查到的类与方法按 ClassLoader 复用。
 */
internal class ColorOsSidebarEntries(private val context: Context, loader: ClassLoader) {
    private val meta = Meta.of(loader)
    private val active = meta.active
    private val tools = meta.tools
    private val apps = meta.apps
    private val shownApps = meta.shownApps
    private val beanClass = meta.beanClass
    private val type = meta.type
    private val category = meta.category
    private val alias = meta.alias
    private val label = meta.label
    private val pkg = meta.pkg
    private val activity = meta.activity
    private val cloned = meta.cloned
    private val cloneUserId = meta.cloneUserId
    private val hidden = meta.hidden
    private val iconRes = meta.iconRes
    private val launchTool = meta.launchTool
    private val launchShortcut = meta.launchShortcut
    private val launchApp = meta.launchApp
    private val shortcuts = meta.shortcuts
    private val shortcut = meta.shortcut
    private val shortcutIcon = meta.shortcutIcon
    private val handler = meta.handler
    private val getMain = meta.getMain
    private val getResident = meta.getResident
    private val getState = meta.getState

    fun ready(): Boolean = active.call(null) != null && getMain.call(handler) != null &&
        (getResident.call(handler) as? IInterface)?.asBinder()?.isBinderAlive == true

    fun sidebarHidden(): Boolean = getMain.call(handler)?.let { (getState.call(it) as? Enum<*>)?.name } == "FLOAT_BAR_SHOWING"

    /** 目录加载时顺手记住 target → 原生 bean，启动时不必再枚举一遍全表。 */
    private val resolvedBeans = java.util.concurrent.ConcurrentHashMap<PinnedTarget.Sidebar, Any>()

    /** 与原生全部面板一致，在工作线程获取目录；图标压到固定上限后才跨进程传输。 */
    fun load(userId: Int): List<SelectableEntry> = entries(includeClones = true, userId).mapNotNull { bean ->
        val target = target(bean, userId) ?: return@mapNotNull null
        resolvedBeans[target] = bean
        val packageName = pkg.call(bean) as? String ?: ""
        val cloneInfo = if (target.kind == PinnedTarget.Kind.CloneApp) {
            clonedActivityInfo(bean, packageName) ?: return@mapNotNull null
        } else null
        val title = (label.call(bean) as? String)?.takeIf { it.isNotBlank() }
            ?: cloneInfo?.label?.toString()?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        SelectableEntry(
            target,
            title.take(160),
            packageName.take(256),
            // 图标资源读不到的工具是其他模块注入的（如 LuckyTool 的「Run in background」），不进挑选列表。
            icon(bean, target, packageName, cloneInfo) ?: return@mapNotNull null,
            cloneInfo?.let(::cloneBadge),
        )
    }.distinctBy(SelectableEntry::target).also { require(it.size <= SidebarEntryProtocol.MAX_ENTRIES) }

    /** 目录查询在工作线程完成；主线程只校验已解析条目并调用原生路由。 */
    fun resolve(target: PinnedTarget.Sidebar): Any? =
        resolvedBeans[target]?.takeIf { beanClass.isInstance(it) }
            ?: entries(includeClones = target.kind == PinnedTarget.Kind.CloneApp, target.userId)
                .firstOrNull { target(it, target.userId) == target }
                ?.also { resolvedBeans[target] = it }

    fun launch(target: PinnedTarget.Sidebar, current: Any): Boolean {
        if (!beanClass.isInstance(current) || target(current, target.userId) != target) return false
        // startNormalApp 以 bean 的 getMutiUserId 启动（16.11.3 / 17.11.2 字节码核对），
        // isCloned 只影响 activity 为空时补组件的查询用户，侧边栏误报分身不会打开本体。
        when (target.kind) {
            PinnedTarget.Kind.Tool -> launchTool
            PinnedTarget.Kind.Shortcut -> launchShortcut
            PinnedTarget.Kind.AppEntry, PinnedTarget.Kind.CloneApp -> launchApp
        }.call(null, current)
        return true
    }

    private fun entries(includeClones: Boolean, userId: Int): List<Any> {
        val owner = active.call(null) ?: throw IllegalStateException("SIDEBAR_ENTRIES_NOT_READY")
        // 分身判断交给 target()：不只看侧边栏的 isCloned，见 SidebarEntryKind.fromNative。
        val result = (tools.call(owner) as? List<*>).orEmpty() + (apps.call(owner) as? List<*>).orEmpty() +
            if (includeClones) (shownApps.call(owner) as? List<*>).orEmpty().filter { bean ->
                bean != null && beanClass.isInstance(bean) && target(bean, userId)?.kind == PinnedTarget.Kind.CloneApp
            } else emptyList()
        return result.filterNotNull().filter(beanClass::isInstance)
    }

    private fun target(bean: Any, userId: Int): PinnedTarget.Sidebar? {
        if (hidden.call(bean) == true) return null
        val kind = SidebarEntryKind.fromNative(
            type = type.call(bean) as? Int ?: return null,
            category = category.call(bean) as? Int ?: return null,
            nativeCloned = cloned.call(bean) == true,
            entryUserId = cloneUserId.call(bean) as? Int ?: return null,
            ownerUserId = userId,
        ) ?: return null
        val value = alias.call(bean) as? String ?: return null
        val result = PinnedTarget.Sidebar(kind, value, userId)
        return result.takeIf { PinnedTargetCodec.decode(PinnedTargetCodec.encode(it)) == it }
    }

    /** 返回 null 表示工具声明了图标资源但读不到，调用方据此排除该条目。 */
    private fun icon(bean: Any, target: PinnedTarget.Sidebar, packageName: String, cloneInfo: LauncherActivityInfo?): Bitmap? {
        val drawable: Drawable? = when (target.kind) {
            PinnedTarget.Kind.Tool -> {
                val id = iconRes.call(bean) as Int
                if (id == 0) null else toolIcon(id, packageName) ?: return null
            }
            // 角标单独生成，避免圆形图标裁剪时切掉右下角。
            PinnedTarget.Kind.CloneApp -> try {
                cloneInfo?.getIcon(context.resources.displayMetrics.densityDpi)
            } catch (_: RuntimeException) { null }
            else -> {
                val owner = shortcuts.call(null)
                val source = owner?.let { shortcut.call(it, target.alias) }
                val bytes = source?.let { shortcutIcon.call(it) } as? ByteArray
                val bitmap = bytes?.takeIf { it.size <= 1_048_576 }?.let {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                        val options = BitmapFactory.Options().apply {
                            // Options 默认值是 0；解码器会归一化，但这里的采样计算需要正除数。
                            inSampleSize = 1
                            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > SidebarEntryProtocol.ICON_SIZE * 2) inSampleSize *= 2
                        }
                        BitmapFactory.decodeByteArray(it, 0, it.size, options)
                    }
                }
                bitmap?.toDrawable(context.resources)
            }
        }
        val source = drawable ?: try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            context.packageManager.defaultActivityIcon
        }
        return source.toIconBitmap()
    }

    /**
     * 工具图标的资源号不一定属于侧边栏自身（例如其他模块注入的工具），侧边栏 context 读不到时
     * 改从工具所属应用的资源读取；仍读不到返回 null，由调用方排除该工具。
     * 单个图标失败不得让整个目录失败。
     */
    private fun toolIcon(id: Int, packageName: String): Drawable? = try {
        context.getDrawable(id)
    } catch (_: Resources.NotFoundException) {
        try {
            packageName.takeIf { it.isNotEmpty() && it != context.packageName }
                ?.let { context.packageManager.getResourcesForApplication(it).getDrawable(id, null) }
        } catch (_: Exception) {
            null
        }
    }

    /** 让系统在透明底图上绘制分身用户角标，位置与比例沿用系统徽标规则。 */
    private fun cloneBadge(info: LauncherActivityInfo): Bitmap? {
        val size = SidebarEntryProtocol.ICON_SIZE
        val blank = createBitmap(size, size, Bitmap.Config.ARGB_8888).toDrawable(context.resources)
        val badged = try {
            context.packageManager.getUserBadgedIcon(blank, info.user)
        } catch (_: RuntimeException) {
            return null
        }
        if (badged === blank) return null
        return badged.toIconBitmap().takeIf { bitmap ->
            IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }.any { it ushr 24 != 0 }
        }
    }

    private fun Drawable.toIconBitmap(): Bitmap =
        createBitmap(SidebarEntryProtocol.ICON_SIZE, SidebarEntryProtocol.ICON_SIZE, Bitmap.Config.ARGB_8888).also {
            setBounds(0, 0, it.width, it.height)
            draw(Canvas(it))
        }

    /**
     * 核对分身用户下确有该包的启动入口；查不到即视为不是分身（或分身已卸载），条目不进目录。
     * 记录的 activity 与现有入口不符（应用更新后改了入口）时，与侧边栏自身的
     * findLauncherActivityInfo 一致，退回该用户下该包的第一个入口，只用于标签、图标和角标。
     */
    private fun clonedActivityInfo(bean: Any, packageName: String): LauncherActivityInfo? {
        val userId = cloneUserId.call(bean) as? Int ?: return null
        val activityName = activity.call(bean) as? String
        if (userId !in 1..21_474 || packageName.isEmpty()) return null
        return try {
            val list = context.getSystemService(LauncherApps::class.java)
                ?.getActivityList(packageName, UserHandle.getUserHandleForUid(userId * 100_000))
                .orEmpty()
            activityName?.let { name -> list.firstOrNull { it.componentName == ComponentName(packageName, name) } }
                ?: list.firstOrNull()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun Method.call(receiver: Any?, vararg args: Any?): Any? = try {
        invoke(receiver, *args)
    } catch (exception: InvocationTargetException) {
        val cause = exception.cause
        if (cause is Error) throw cause
        throw exception
    }

    /** 反射元数据：只有类、方法与 Kotlin object 单例，不含任何原生条目；侧边栏进程只有一个 ClassLoader，单格缓存即可。 */
    private class Meta(val loader: ClassLoader) {
        val helperClass = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.EntryBeanHelper")
        val active = helperClass.getMethod("getActiveInstance")
        val tools = helperClass.getMethod("getAllAppListForTool")
        val apps = helperClass.getMethod("getShownShortcuts")
        val shownApps = helperClass.getMethod("getShownApps")
        val beanClass = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.beans.EntryBean")
        val type = beanClass.getMethod("getType")
        val category = beanClass.getMethod("getCategory")
        val alias = beanClass.getMethod("getAlias")
        val label = beanClass.getMethod("getLabel")
        val pkg = beanClass.getMethod("getPkg")
        val activity = beanClass.getMethod("getActivity")
        val cloned = beanClass.getMethod("isCloned")
        val cloneUserId = beanClass.getMethod("getMutiUserId")
        val hidden = beanClass.getMethod("isHidden")
        val iconRes = beanClass.getMethod("getIconRes")
        val routeClass = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.utils.PageRoutUtils")
        val launchTool = routeClass.getMethod("startSysTool", beanClass)
        val launchShortcut = routeClass.getMethod("startShortcut", beanClass)
        val launchApp = routeClass.getMethod("startNormalApp", beanClass)
        val shortcutClass = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.ShortcutEntryHelper")
        val shortcuts = shortcutClass.getMethod("getActiveInstance")
        val shortcut = shortcutClass.getMethod("getShortcut", String::class.java)
        val shortcutIcon = shortcut.returnType.getMethod("getIcon")
        val handlerClass = loader.loadClass(ColorOsAllAppsContent.HANDLER_CLASS)
        val handler = handlerClass.getField("INSTANCE").get(null)
        val getMain = handlerClass.getMethod("getMPanelMainView")
        val getResident = handlerClass.getMethod("getMResidentProcessHandler")
        val getState = getMain.returnType.getMethod("getMState")

        companion object {
            @Volatile private var cached: Meta? = null

            fun of(loader: ClassLoader): Meta =
                cached?.takeIf { it.loader === loader } ?: Meta(loader).also { cached = it }
        }
    }
}
