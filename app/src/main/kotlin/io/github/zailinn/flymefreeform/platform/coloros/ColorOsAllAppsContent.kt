package io.github.zailinn.flymefreeform.platform.coloros

import android.animation.Animator
import android.content.Context
import android.os.IInterface
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isNotEmpty
import io.github.zailinn.flymefreeform.window.AllAppsPanelGeometry

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** 仅适配已核对的侧栏版本：独立创建内容视图，不修改原生面板状态。 */
internal class ColorOsAllAppsContent(loader: ClassLoader, target: ColorOsSidebarVisualTarget) {
    private val allClass = loader.loadClass(ALL_CLASS)
    private val searchClass = loader.loadClass(SEARCH_CLASS)
    private val mainClass = loader.loadClass(MAIN_CLASS)
    private val userClass = loader.loadClass(USER_CLASS)
    private val cardClass = loader.loadClass("com.coui.appcompat.cardview.COUICardView")
    private val handlerClass = loader.loadClass(HANDLER_CLASS)
    private val handler = handlerClass.getField("INSTANCE").get(null)
    private val getMain = handlerClass.getMethod("getMPanelMainView")
    private val getResident = handlerClass.getMethod("getMResidentProcessHandler")
    private val getState = mainClass.getMethod("getMState")
    private val getUser = mainClass.getMethod("getMPanel")
    private val getLeft = mainClass.getMethod("getMIsLeft")
    private val getRadius = cardClass.getMethod("getRadius")
    private val getWeight = cardClass.getMethod("getWeight")
    private val getColor = userClass.getMethod("getUserPanelBgColor")
    private val getSearch = allClass.getMethod("getSearchHelper")
    private val getRouter = searchClass.getMethod("getAppDataHandler")
    private val load = searchClass.getMethod("loadData")
    private val stop = searchClass.getMethod("stopLoadData")
    private val getSearchState = searchClass.getMethod("getSearchState")
    private val getData = searchClass.getMethod("getCurrentDataList")
    private val dataClass = loader.loadClass(DATA_CLASS)
    private val getKey = dataClass.getMethod("getKey")
    private val getEntry = dataClass.getMethod("getEntryBean")
    private val getType = getEntry.returnType.getMethod("getType")
    private val adapterClass = loader.loadClass(ADAPTER_CLASS)
    private val getClick = adapterClass.getMethod("getOnItemClick")
    private val setClick = adapterClass.getMethod("setOnItemClick", getClick.returnType)
    private val invokeClick = getClick.returnType.getMethod("invoke", Any::class.java, Any::class.java)
    private val nativeUnit = checkNotNull(target.unit.get(null))
    private val cancelSearch = allClass.getMethod("cancelSearchEditing", Boolean::class.javaPrimitiveType)
    private val enterSearch = optionalAnimator("getEnterSearchAnimatorSet")
    private val exitSearch = optionalAnimator("getExitSearchAnimatorSet")
    private val resource = checkNotNull(target.resource.get(null))
    private val isLandscape = target.landscape
    private val isPortrait = target.portrait
    private val isTabletop = target.hover
    private val gap = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.utils.MainPanelSizeHelper").getMethod("getPanelGap")
    private val sidebarBlur = ColorOsSidebarBlur(target.blur)
    private val shadow = target.shadow

    // Service 使用平台默认主题；原厂内容依赖 App 的配置/主题包装，不能直接传 Service。
    private val context = themedContext(loader)

    val view = construct(allClass) as ViewGroup
    private val search = getSearch.call(view)!!
    val router = getRouter.call(search)!!
    // 系统点击实现是「currentDataList.get(index) → onAppItemClicked(data)」，
    // 搜索时列表会换成另一份，所以这里保留直接按“行”分发的通道。
    private val setClickType =
        loader
            .loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.beans.ItemBean")
            .getMethod("setClickType", Int::class.javaPrimitiveType)
    private val onAppItemClicked =
        router.javaClass.getMethod("onAppItemClicked", dataClass, Boolean::class.javaPrimitiveType)
    private var closed = false
    private var loading = false
    private var blur: ColorOsSidebarBlur.Session? = null
    private var blurView: View? = null
    private var blurPrepared = false
    private var card: FrameLayout? = null

    init {
        require(ViewGroup::class.java.isAssignableFrom(allClass))
        require(FrameLayout::class.java.isAssignableFrom(cardClass))
        require(getRouter.returnType.name == ROUTER_CLASS)
        allClass.getMethod("showAllPanel", Boolean::class.javaPrimitiveType).call(view, leftSide())
    }

    fun ready(): Boolean = view.isNotEmpty() && getMain.call(handler) != null &&
        (getResident.call(handler) as? IInterface)?.asBinder()?.isBinderAlive == true

    fun sidebarHidden(): Boolean = (getMain.call(handler)?.let { getState.call(it) } as? Enum<*>)?.name == "FLOAT_BAR_SHOWING"

    fun leftSide(): Boolean = getMain.call(handler)?.let { getLeft.call(it) as Boolean } ?: false

    fun startLoading() {
        if (closed || loading) return
        loading = true
        load.call(search)
    }

    fun refresh() {
        if (closed || !loading) return
        stop.call(search)
        load.call(search)
    }

    @Suppress("DiscouragedApi")
    /** 「关闭」动作，供快捷方式拉起后自动收起面板用。 */
    private var closeAction: (() -> Unit)? = null

    fun bindClose(action: () -> Unit) {
        closeAction = action
        if (closed) return
        val toolbarId = context.resources.getIdentifier("all_app_toolbar", "id", ColorOsSidebarTarget.PACKAGE_NAME)
        val closeId = context.resources.getIdentifier("close", "id", ColorOsSidebarTarget.PACKAGE_NAME)
        val toolbar = if (toolbarId == 0) null else view.findViewById<View>(toolbarId)
        val close = if (closeId == 0) null else view.findViewById<View>(closeId)
        when {
            toolbar != null -> {
                val menu = toolbar.javaClass.getMethod("getMenu").call(toolbar) as Menu
                checkNotNull(menu.findItem(resourceId("cancel", "id")))
                    .setOnMenuItemClickListener { action(); true }
            }
            close != null -> close.setOnClickListener { action() }
            else -> throw IllegalStateException("SIDEBAR_CLOSE_CONTROL_UNAVAILABLE")
        }
    }

    private fun optionalAnimator(name: String): Method? = try {
        searchClass.getMethod(name).also { require(Animator::class.java.isAssignableFrom(it.returnType)) }
    } catch (_: NoSuchMethodException) {
        null
    }

    fun bindAdapter(adapter: Any, onClick: (Boolean, () -> Unit) -> Unit) {
        panelLog(
            "PANEL_BIND " + adapter.javaClass.simpleName +
                " count=" + runCatching { adapter.javaClass.getMethod("getItemCount").invoke(adapter) }.getOrNull(),
        )
        // 每次打开都强制重算：最近组要反映刚用过的应用，不能吃 15s 缓存；
        // 先用上一份结果立刻上屏，算完再原地回写新顺序。
        FavoriteGroupInjector.prepareAsync(context, adapter.javaClass.classLoader, force = true)
        FavoriteGroupInjector.apply(adapter, context)
        // 之后系统每次重灌 currentDataList（加载完成、退出搜索等）都由 SidebarHookInstaller 的
        // refill 钩子回调 [onNativeListWritten] 当场接管，不再按时间点轮询或挂数据观察者。
        boundAdapter = adapter
        val original = getClick.call(adapter) ?: return
        val callback = Proxy.newProxyInstance(getClick.returnType.classLoader, arrayOf(getClick.returnType)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    if (!closed) {
                        val index = args?.getOrNull(0) as? Int
                        val clickType = args?.getOrNull(1) as? Int
                        val alias = index?.let { aliasAt(adapter, it) }
                        val item = index?.let { itemAt(adapter, it) }
                        val list = getData.call(search) as? List<*>
                        // 系统点击实现取的是 currentDataList.get(index)。列表被重排、
                        // 或者面板搜索后换成搜索结果时，这一行未必还是那份列表里的同一个对象，
                        // 此时按索引分发会打开错误的条目（例如搜索「计算器」却点开其它应用）。
                        val aligned =
                            index != null && item != null && list != null &&
                                index in list.indices && list[index] === item
                        // 详细日志：还原一次点击的完整现场。搜索后索引与 currentDataList
                        // 对不上是历史 bug 的源头（点搜索结果却打开了收藏组第一条），
                        // 这里把 index / 实际条目 / 是否对齐 / 当前搜索词全部打出来。
                        val query = currentQuery()
                        panelLog(
                            "PANEL_CLICK index=" + index +
                                " alias=" + alias +
                                " aligned=" + aligned +
                                " clickType=" + clickType +
                                " size=" + (list?.size ?: -1) +
                                " mdata=" + (runCatching { (adapter.javaClass.getMethod("getMData").invoke(adapter) as? List<*>)?.size }.getOrNull() ?: -1) +
                                " query=" + query,
                        )
                        val data = when {
                            aligned -> item
                            alias != null -> list?.firstOrNull { sameAlias(it, alias) }
                            else -> null
                        }
                        // 工具（type=0，微信扫一扫/支付宝付款码这类）和现造的快捷方式都不在
                        // 系统的应用表里，按位置分发会找不到；工具走它自己的 handle()，
                        // 快捷方式按 pkg/activity 拉起。
                        if (data == null || itemType(item) == 0) {
                            // 工具（截屏/录屏这类取屏入口）与现造快捷方式都先收起面板、等窗口提交
                            // 不可见帧后再执行，否则取屏工具会把手里的面板一起截进画面。
                            onClick(true) { launchEntry(alias, item) }
                            return@newProxyInstance nativeUnit
                        }
                        when {
                            // 索引对不上：按“屏幕上被点的这一行”直接交给系统 handler。
                            !aligned && item != null && clickType != null && dataClass.isInstance(item) -> {
                                panelLog(
                                    "PANEL_LAUNCH_DISPLAYED " +
                                        (runCatching { getKey.call(item) }.getOrNull() ?: "?") +
                                        " type=" + itemType(item),
                                )
                                onClick(itemType(item) == 0) {
                                    dispatchDisplayed(item, clickType)
                                    closeAction?.invoke()
                                }
                            }
                            aligned && item != null && clickType != null -> {
                                val key = getKey.call(item)
                                val entry = getEntry.call(item)
                                val tool = entry != null && getType.call(entry) == 0
                                panelLog("PANEL_LAUNCH_ALIGNED " + key + " tool=" + tool)
                                onClick(tool) {
                                    // 动画期间目录可能刷新，执行前按原条目标识重新定位，不能复用旧索引。
                                    val current = getData.call(search) as? List<*>
                                    val targetIndex = current?.indexOfFirst {
                                        it != null && dataClass.isInstance(it) && getKey.call(it) == key
                                    } ?: -1
                                    if (!closed && targetIndex >= 0) invokeClick.call(original, targetIndex, clickType)
                                }
                            }
                        }
                    }
                    nativeUnit
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "AllAppsClickCallback"
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        setClick.call(adapter, callback)
    }

    /**
     * 直接按“屏幕上被点的那一行”分发：等价于系统实现里的
     * `entry.setClickType(type); handler.onAppItemClicked(data, false)`，
     * 只是不再经过 currentDataList 的索引。
     */
    private fun dispatchDisplayed(item: Any?, clickType: Int?): Boolean = try {
        val data = item?.let { dataClass.cast(it) } ?: return false
        val entry = getEntry.call(data)
        if (entry != null && clickType != null) setClickType.invoke(entry, clickType)
        panelLog("PANEL_SEARCH_ROW_DISPATCH " + (runCatching { getKey.call(data) }.getOrNull() ?: "?"))
        onAppItemClicked.invoke(router, data, false)
        true
    } catch (_: Throwable) {
        false
    }

    /** 当前搜索框里的词；面板搜索用的是系统 EditText，遍历视图树取非空输入。 */
    private fun currentQuery(): String = try {
        val edits = ArrayList<android.widget.EditText>()
        collectEdits(view, edits)
        val text = edits.firstOrNull { it.text?.isNotBlank() == true }?.text?.toString() ?: ""
        text.replace(' ', '_')
    } catch (_: Throwable) {
        ""
    }

    private fun collectEdits(root: android.view.View, out: MutableList<android.widget.EditText>) {
        if (root is android.widget.EditText) {
            out.add(root)
            return
        }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) collectEdits(root.getChildAt(i), out)
        }
    }

    fun logEvent(code: String) = panelLog(code)

    /** 详细日志：面板里的工具/快捷方式启动结果。 */
    private fun panelLog(code: String) {
        io.github.zailinn.flymefreeform.log.ModuleLog.debug("FFLAUNCH") { code }
    }

    private fun itemType(item: Any?): Int? = try {
        val bean = item?.javaClass?.getMethod("getEntryBean")?.invoke(item) ?: return null
        bean.javaClass.getMethod("getType").invoke(bean) as? Int
    } catch (_: Throwable) {
        null
    }

    /**
     * 工具类入口：从 ToolEntryHelper.mTools 取 AbsTool 实例，调它的 handle()。
     *
     * 调 handle() 之前登记目标包：工具的启动由系统 uid 代发（见 [PanelToolLaunchBridge]），
     * 小窗参数只能在代发那一刻并入。登记后无论成功失败都要收敛，避免影响后续启动。
     */
    private fun launchTool(alias: String): Boolean = try {
        val helper = Class.forName(
            "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.ToolEntryHelper",
            false,
            view.javaClass.classLoader,
        )
        val instance = helper.getMethod("getActiveInstance").invoke(null)
        val field = helper.getDeclaredField("mTools").apply { isAccessible = true }
        val map = field.get(instance) as? Map<*, *>
        val tool = map?.get(alias)
        if (tool == null) {
            panelLog("PANEL_TOOL_MISSING_" + alias)
            false
        } else {
            PanelToolLaunchBridge.arm(toolTargetPackage(tool))
            tool.javaClass.getMethod("handle").invoke(tool)
            panelLog("PANEL_TOOL_HANDLED_" + alias)
            true
        }
    } catch (t: Throwable) {
        PanelToolLaunchBridge.cancel()
        false
    }

    /** 工具依赖的包：优先 getValidPkg，其次 getPkgs 首项；取不到时不登记。 */
    private fun toolTargetPackage(tool: Any): String? {
        val valid = runCatching { tool.javaClass.getMethod("getValidPkg").invoke(tool) as? String }.getOrNull()
        if (!valid.isNullOrEmpty()) return valid
        val packages = runCatching { tool.javaClass.getMethod("getPkgs").invoke(tool) as? Array<*> }.getOrNull()
        return packages?.firstOrNull() as? String
    }

    /** 取 adapter 第 [index] 个条目本身。 */
    private fun itemAt(adapter: Any, index: Int): Any? = try {
        (adapter.javaClass.getMethod("getMData").invoke(adapter) as? List<*>)?.getOrNull(index)
    } catch (_: Throwable) {
        null
    }

    /**
     * 直接拉起一个条目：
     * 1. 工具（type=0，如微信扫一扫/支付宝付款码）调它自己的 `handle()`，系统就是这么做；
     * 2. 应用快捷方式按 pkg/activity 或 intentString 启动。
     */
    private fun launchEntry(alias: String?, item: Any?): Boolean {
        if (!alias.isNullOrEmpty() && launchTool(alias)) return true
        panelLog("PANEL_TOOL_FALLBACK_" + (alias ?: "?"))
        val bean = try {
            item?.javaClass?.getMethod("getEntryBean")?.invoke(item)
        } catch (_: Throwable) {
            null
        } ?: return false
        val cls = bean.javaClass
        val pkg = runCatching { cls.getMethod("getPkg").invoke(bean) as? String }.getOrNull()
        val activity = runCatching { cls.getMethod("getActivity").invoke(bean) as? String }.getOrNull()
        if (!pkg.isNullOrEmpty() && !activity.isNullOrEmpty()) {
            val done = runCatching {
                val intent = android.content.Intent()
                    .setClassName(pkg, activity)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrElse { false }
            if (done) return true
        }
        val uri = runCatching { cls.getMethod("getIntentString").invoke(bean) as? String }.getOrNull()
        if (!uri.isNullOrEmpty()) {
            val done = runCatching {
                val intent = android.content.Intent.parseUri(uri, 0)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrElse { false }
            if (done) return true
        }
        return false
    }

    /** 取 adapter 第 [index] 个条目的 alias（重排后按 alias 回查系统列表）。 */
    private fun aliasAt(adapter: Any, index: Int): String? = try {
        val data = adapter.javaClass.getMethod("getMData").invoke(adapter) as? List<*> ?: return null
        val item = data.getOrNull(index) ?: return null
        val bean = item.javaClass.getMethod("getEntryBean").invoke(item) ?: return null
        bean.javaClass.getMethod("getAlias").invoke(bean) as? String
    } catch (_: Throwable) {
        null
    }

    private fun sameAlias(data: Any?, alias: String): Boolean = try {
        val bean = data?.let { dataClass.cast(it) }?.let { getEntry.call(it) } ?: return false
        bean.javaClass.getMethod("getAlias").invoke(bean) == alias
    } catch (_: Throwable) {
        false
    }

    /** 本面板绑定的原厂 adapter；其 mData 与 SearchHelper.currentDataList 是同一个列表对象。 */
    private var boundAdapter: Any? = null

    fun ownsSearch(candidate: Any?): Boolean = candidate != null && candidate === search

    /**
     * 原厂刚把 currentDataList 整份重灌。带分组标题的是全量列表（首次加载、退出搜索、
     * 工具折叠切换），当场换成「收藏 + 最近」并通知刷新；搜索结果不带标题，原样保留。
     *
     * 钩子在原方法返回后同步执行，与原厂的 notify 处于同一条消息，下一帧布局读到的就是模块列表，
     * 原厂列表不会出现在屏幕上。
     */
    fun onNativeListWritten() {
        if (closed) return
        val adapter = boundAdapter ?: return
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { onNativeListWritten() }
            return
        }
        val list = getData.call(search) as? List<*> ?: return
        val hasTitles = list.any { it?.javaClass?.simpleName == "TitleLabelData" }
        if (!hasTitles || currentQuery().isNotEmpty()) {
            panelLog("PANEL_REFILL_SKIPPED size=" + list.size + " titles=" + hasTitles)
            return
        }
        panelLog("PANEL_REFILL_TAKEOVER size=" + list.size)
        FavoriteGroupInjector.apply(adapter, context, notify = true)
    }

    /**
     * 原厂 onBindViewHolder 返回后调用。分组标题行（LabelViewHolder）的文案来自 viewType
     * 对应布局的固定字符串，原厂绑定时不改它；这里按数据行登记的角色写入「收藏 / 最近」。
     * 只改模块登记过的标题，原厂列表的「工具 / 应用」保持原样。
     */
    fun onRowBound(adapter: Any?, holder: Any?, position: Int) {
        if (closed || adapter == null || adapter !== boundAdapter || holder == null) return
        runCatching {
            val data = adapter.javaClass.getMethod("getMData").invoke(adapter) as? List<*> ?: return
            val role = FavoriteGroupInjector.titleRole(data.getOrNull(position)) ?: return
            val itemView = itemViewOf(holder) ?: return
            val title = firstTextView(itemView) ?: return
            val label = PanelGroupLabels.of(role)
            if (title.text?.toString() != label) title.text = label
        }.onFailure { panelLog("PANEL_TITLE_BIND_FAILED " + it.javaClass.simpleName) }
    }

    /** RecyclerView.ViewHolder.itemView：字段名未被混淆（16.11.3 核对），沿父类链查找。 */
    private fun itemViewOf(holder: Any): View? {
        var type: Class<*>? = holder.javaClass
        while (type != null && type != Any::class.java) {
            val current: Class<*> = type
            val field = runCatching { current.getDeclaredField("itemView") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field.get(holder) as? View
            }
            type = current.superclass
        }
        return null
    }

    private fun firstTextView(root: View): android.widget.TextView? {
        if (root is android.widget.TextView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) firstTextView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    fun leaveSearch(): Boolean {
        if (getSearchState.call(search) != true) return false
        cancelSearch.call(view, true)
        return true
    }

    fun createCard(): FrameLayout {
        val original = getMain.call(handler)?.let { getUser.call(it) }
            ?: throw IllegalStateException("SIDEBAR_STYLE_NOT_READY")
        val surface = construct(cardClass) as FrameLayout
        cardClass.getMethod("setRadius", Float::class.javaPrimitiveType).call(surface, getRadius.call(original))
        cardClass.getMethod("setWeight", Float::class.javaPrimitiveType).call(surface, getWeight.call(original))
        cardClass.getMethod("setPreventCornerOverlap", Boolean::class.javaPrimitiveType).call(surface, false)
        cardClass.getMethod("setUseCompatPadding", Boolean::class.javaPrimitiveType).call(surface, false)
        cardClass.getMethod("setCardBackgroundColor", Int::class.javaPrimitiveType).call(surface, getColor.call(original))
        surface.clipToOutline = true
        surface.isForceDarkAllowed = false
        shadow?.setup?.call(null, surface)
        shadow?.update?.call(null, surface, 1f)
        surface.addView(view, FrameLayout.LayoutParams(-1, -1))
        card = surface
        return surface
    }

    /** 新增背景 View 后必须先完成一次布局，再让厂商模糊管理器读取其窗口坐标。 */
    fun attachBlur(): Boolean {
        val surface = card ?: return false
        if (blur != null) return true
        if (!blurPrepared) {
            blurPrepared = true
            if (!sidebarBlur.available) return true
            blurView = View(context).also { surface.addView(it, 0, FrameLayout.LayoutParams(-1, -1)) }
            return false
        }
        val background = blurView ?: return true
        if (!background.isLaidOut || background.isLayoutRequested ||
            background.width != surface.width || background.height != surface.height
        ) return false
        val session = sidebarBlur.attach(background)
        if (session == null) {
            surface.removeView(background)
            blurView = null
            return true
        }
        background.background?.setBounds(0, 0, background.width, background.height)
        cardClass.getMethod("setCardBackgroundColor", Int::class.javaPrimitiveType).call(surface, 0)
        blur = session
        blurView = background
        updateBlur(1f)
        return true
    }

    fun updateBlur(alpha: Float) {
        blur?.update(alpha)
        card?.let { shadow?.update?.call(null, it, alpha) }
    }

    fun dimensions() = AllAppsPanelGeometry.Dimensions(
        dimension("all_app_panel_max_width"), dimension("all_app_panel_max_height"),
        dimension("all_app_panel_height_small_portrait"), dimension("all_app_panel_margin_min"),
        dimension("all_app_panel_margin_max"), gap.call(null) as Int,
    )

    fun mode(): AllAppsPanelGeometry.Mode = when {
        isTabletop.call(resource, false) == true -> AllAppsPanelGeometry.Mode.Tabletop
        isLandscape.call(resource) == true -> AllAppsPanelGeometry.Mode.Landscape
        isPortrait.call(resource) == true -> AllAppsPanelGeometry.Mode.Portrait
        else -> AllAppsPanelGeometry.Mode.LargePortrait
    }

    fun close() {
        if (closed) return
        closed = true
        boundAdapter = null
        try {
            if (view.isNotEmpty()) {
                (enterSearch?.call(search) as? Animator)?.cancel()
                (exitSearch?.call(search) as? Animator)?.cancel()
            }
        } finally {
            try {
                if (loading) stop.call(search)
            } finally {
                loading = false
                blurView?.background = null
                blurView = null
                sidebarBlur.close()
                blur = null
                card = null
            }
        }
    }

    private fun dimension(name: String): Int = context.resources.getDimensionPixelSize(resourceId(name, "dimen"))

    @Suppress("DiscouragedApi")
    private fun resourceId(name: String, type: String): Int =
        context.resources.getIdentifier(name, type, ColorOsSidebarTarget.PACKAGE_NAME).also { require(it != 0) }

    private fun Method.call(receiver: Any?, vararg args: Any?): Any? = try {
        invoke(receiver, *args)
    } catch (exception: InvocationTargetException) {
        val cause = exception.cause
        if (cause is Error) throw cause
        throw exception
    }

    private fun construct(type: Class<*>): Any = try {
        type.getConstructor(Context::class.java).newInstance(context)
    } catch (exception: InvocationTargetException) {
        val cause = exception.cause
        if (cause is Error) throw cause
        throw exception
    }

    @Suppress("DiscouragedApi")
    private fun themedContext(loader: ClassLoader): Context {
        val native = (getMain.call(handler) as? View)?.context
            ?: loader.loadClass("com.coloros.common.App").getField("sContext").get(null) as? Context
            ?: throw IllegalStateException("ALL_APPS_THEME_CONTEXT_UNAVAILABLE")
        for (name in listOf("couiColorPrimaryTextOnPopup", "couiColorPrimaryNeutral", "couiColorSurfaceWithCard")) {
            val id = native.resources.getIdentifier(name, "attr", ColorOsSidebarTarget.PACKAGE_NAME)
            val value = TypedValue()
            check(id != 0 && native.theme.resolveAttribute(id, value, true) &&
                value.type != TypedValue.TYPE_ATTRIBUTE && value.type != TypedValue.TYPE_NULL
            ) { "ALL_APPS_THEME_ATTRIBUTE_UNAVAILABLE" }
        }
        return native
    }

    companion object {
        const val ALL_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.allpanel.AllAppPanelView"
        const val ADAPTER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.allpanel.AllAppRecyclerAdapter"
        const val ROUTER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.viewdatahandlers.AllAppDataHandlerImpl"
        const val DATA_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.AppLabelData"
        const val SEARCH_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.utils.SearchHelper"
        const val HANDLER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.viewdatahandlers.ViewDataHandlerImpl"
        const val MAIN_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.PanelMainView"
        const val USER_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.mainpanel.UserPanelView"
    }
}
