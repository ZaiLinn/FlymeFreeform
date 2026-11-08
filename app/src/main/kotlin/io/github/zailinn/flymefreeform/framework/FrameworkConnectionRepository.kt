package io.github.zailinn.flymefreeform.framework

import android.annotation.SuppressLint
import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.zailinn.flymefreeform.config.ModulePreferences
import io.github.zailinn.flymefreeform.config.ModuleSettingsSnapshot
import io.github.zailinn.flymefreeform.config.OutsideTapCloseMode
import io.github.zailinn.flymefreeform.config.PinnedTarget
import io.github.zailinn.flymefreeform.config.PinnedTargetCodec
import io.github.zailinn.flymefreeform.config.SettingsTransfer
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Service 102 的进程级所有者；所有同步 Binder 调用都串行限制在单一后台线程。 */
internal class FrameworkConnectionRepository(private val context: Context) {
    private val started = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, WORKER_THREAD_NAME) }
    private val serviceBinders = IdentityHashMap<XposedService, IBinder>()
    private val serviceDeaths = IdentityHashMap<XposedService, IBinder.DeathRecipient>()
    private val liveServices = IdentityHashMap<XposedService, Unit>()
    private val diedBeforeBind = IdentityHashMap<XposedService, Unit>()
    private val pendingServices = IdentityHashMap<XposedService, Unit>()
    private val unavailableServices = IdentityHashMap<XposedService, FrameworkConnectionState>()
    private var activeConnection: ActiveConnection? = null

    private val mutableState = MutableStateFlow(FrameworkConnectionState())
    val state: StateFlow<FrameworkConnectionState> = mutableState.asStateFlow()

    private val serviceListener =
        object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                worker.execute { handleServiceBind(service) }
            }

            override fun onServiceDied(service: XposedService) {
                worker.execute { handleServiceDied(service) }
            }
        }

    fun start() {
        check(started.compareAndSet(false, true)) { "Xposed Service listener is already registered" }
        XposedServiceHelper.registerListener(serviceListener)
    }

    fun setModuleEnabled(enabled: Boolean) =
        updateSettings { it.copy(enabled = enabled) }

    fun setCornerSides(leftEnabled: Boolean, rightEnabled: Boolean) =
        updateSettings {
            val bothDisabled = !leftEnabled && !rightEnabled
            it.copy(
                leftCornerEnabled = if (bothDisabled) true else leftEnabled,
                rightCornerEnabled = if (bothDisabled) true else rightEnabled,
            )
        }

    fun setCornerTriggerRangeDp(rangeDp: Int) =
        updateSettings {
            it.copy(
                cornerTriggerRangeDp =
                    ModulePreferences.coerceCornerTriggerRangeDp(rangeDp),
            )
        }

    fun setRadialHitRangeDp(rangeDp: Int) =
        updateSettings {
            it.copy(
                radialHitRangeDp =
                    ModulePreferences.coerceRadialHitRangeDp(rangeDp),
            )
        }

    fun setOutsideTapCloseMode(mode: OutsideTapCloseMode) =
        updateSettings { it.copy(outsideTapCloseMode = mode) }

    fun setHandleSwipeUpToMiniEnabled(enabled: Boolean) =
        updateSettings { it.copy(handleSwipeUpToMiniEnabled = enabled) }

    fun setPauseInLandscape(enabled: Boolean) =
        updateSettings { it.copy(pauseInLandscape = enabled) }

    fun setPauseInGameMode(enabled: Boolean) =
        updateSettings { it.copy(pauseInGameMode = enabled) }

    fun setVerboseLogEnabled(enabled: Boolean) =
        updateSettings { it.copy(verboseLogEnabled = enabled) }

    fun setRadialKeepOpen(enabled: Boolean) =
        updateSettings { it.copy(radialKeepOpen = enabled) }

    fun setPanelNativeList(enabled: Boolean) =
        updateSettings { it.copy(panelNativeList = enabled) }

    fun setRadialCenterPreview(enabled: Boolean) =
        updateSettings { it.copy(radialCenterPreview = enabled) }

    fun setRadialCenterLabel(enabled: Boolean) =
        updateSettings { it.copy(radialCenterLabel = enabled) }

    fun setRadialMoreColor(color: Int) =
        updateSettings { it.copy(radialMoreColor = ModulePreferences.coerceRadialMoreColor(color)) }

    fun setRadialCenterSizeDp(sizeDp: Int) =
        updateSettings { it.copy(radialCenterSizeDp = ModulePreferences.coerceRadialCenterSizeDp(sizeDp)) }

    fun setRadialMaxItems(count: Int) =
        updateSettings {
            it.copy(radialMaxItems = ModulePreferences.coerceRadialMaxItems(count))
        }

    /**
     * 圆弧条数一次写入：1 条即关闭多圆弧（保留上次的条数），2–4 条同时打开多圆弧并设条数。
     * 合在一次 updateSettings 里，避免分两次写入时中间状态被广播出去。
     */
    fun setRadialArcs(count: Int) =
        updateSettings {
            val arcs = ModulePreferences.coerceRadialArcCount(count)
            if (arcs <= 1) it.copy(radialMultiArcEnabled = false) else it.copy(radialMultiArcEnabled = true, radialArcCount = arcs)
        }

    fun setRadialFillSource(source: Int) =
        updateSettings {
            it.copy(radialFillSource = ModulePreferences.coerceRadialFillSource(source))
        }

    fun setIconShape(shape: Int) =
        updateSettings {
            it.copy(iconShape = ModulePreferences.coerceIconShape(shape))
        }

    fun setZoomMaxWindowCount(count: Int) =
        updateSettings {
            it.copy(
                zoomMaxWindowCount = ModulePreferences.coerceZoomMaxWindowCount(count),
            )
        }

    fun setZoomAllowAnyApp(enabled: Boolean) =
        updateSettings { it.copy(zoomAllowAnyApp = enabled) }

    fun setZoomLaunch(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunch = enabled) }

    fun setBlacklistPackage(packageName: String, excluded: Boolean) =
        updateSettings { it.copy(blacklistPackages =
            io.github.zailinn.flymefreeform.config.BlacklistEdits.apply(it.blacklistPackages, packageName, excluded)) }

    fun setPinnedTargets(components: List<PinnedTarget>) =
        updateSettings {
            it.copy(
                pinsSaved = true,
                pinnedTargets =
                    io.github.zailinn.flymefreeform.config.PinnedTargetCodec.normalize(components),
            )
        }

    /** 第 [innerIndex] 条内侧圆弧（0 对应第 2 条）的固定项；空列表表示用最近使用补满。 */
    fun setInnerArcTargets(innerIndex: Int, targets: List<PinnedTarget>) =
        updateSettings { settings ->
            if (innerIndex !in settings.innerArcTargets.indices) return@updateSettings settings
            val normalized =
                io.github.zailinn.flymefreeform.config.PinnedTargetCodec.normalize(
                    targets,
                    io.github.zailinn.flymefreeform.gesture.RadialArcPlan.pinCapacity(innerIndex + 1),
                )
            settings.copy(
                innerArcTargets =
                    settings.innerArcTargets.mapIndexed { index, current ->
                        if (index == innerIndex) normalized else current
                    },
            )
        }

    /**
     * 把已固定在其他圈的 [target] 移到第 [toArc] 圈（0 为默认圆弧）。两圈在同一次提交里改，
     * 只写一次配置、只发一次广播，system_server 不会读到条目同时在两圈或都不在的中间状态。
     */
    fun moveTargetToArc(target: PinnedTarget, toArc: Int) =
        updateSettings { settings ->
            val arcs = listOf(settings.pinnedTargets) + settings.innerArcTargets
            val moved =
                io.github.zailinn.flymefreeform.config.ArcPins.move(arcs, target, toArc)
                    ?: return@updateSettings settings
            settings.copy(
                pinsSaved = settings.pinsSaved || toArc == 0,
                pinnedTargets = moved.first(),
                innerArcTargets = moved.drop(1),
            )
        }

    fun requestMissingScopes() {
        worker.execute {
            reconcileServices()
            val connection = activeConnection ?: return@execute
            val current = mutableState.value
            if (!current.canRequestScope) return@execute
            val requested = current.missingScopes.toList()
            val requesting = current.copy(isRequestingScope = true, issue = null)
            activeConnection = connection.copy(confirmedState = requesting)
            mutableState.value = requesting
            try {
                connection.service.requestScope(
                    requested,
                    object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(approved: List<String>) {
                            worker.execute { refreshAfterScopeRequest(connection) }
                        }

                        override fun onScopeRequestFailed(message: String) {
                            worker.execute { finishScopeRequestWithError(connection) }
                        }
                    },
                )
            } catch (exception: XposedService.ServiceException) {
                finishScopeRequestWithError(connection)
            } catch (exception: UnsupportedOperationException) {
                finishScopeRequestWithError(connection)
            } catch (exception: SecurityException) {
                finishScopeRequestWithError(connection)
            } catch (exception: RuntimeException) {
                finishScopeRequestWithError(connection)
            }
        }
    }

    private fun updateSettings(transform: (ModuleSettingsSnapshot) -> ModuleSettingsSnapshot) {
        worker.execute {
            reconcileServices()
            val connection = activeConnection ?: return@execute
            val previous = mutableState.value
            if (!previous.canChangeSettings) return@execute
            val next = transform(previous.settings)
            if (next == previous.settings) return@execute
            mutableState.value = previous.copy(isUpdating = true, issue = null)
            val committed = commitSettings(connection.preferences, next)
            if (activeConnection?.service !== connection.service) return@execute
            if (committed) {
                val confirmed = previous.copy(settings = next, isUpdating = false, issue = null)
                activeConnection = connection.copy(confirmedState = confirmed)
                mutableState.value = confirmed
                // 任何设置变化都推给各 Hook 进程：模块重装后远程配置回调失效，只靠广播热生效。
                notifyPinnedTargetsChanged(next)
            } else {
                isolateFailedConnection(connection, previous)
            }
        }
    }

    private fun notifyPinnedTargetsChanged(settings: ModuleSettingsSnapshot) {
        val intent = Intent(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED).apply {
            putExtra(
                ModulePreferences.EXTRA_PINNED_TARGETS,
                PinnedTargetCodec.encodeList(settings.pinnedTargets),
            )
            putExtra(ModulePreferences.EXTRA_PINS_SAVED, settings.pinsSaved)
            putStringArrayListExtra(
                ModulePreferences.EXTRA_INNER_ARC_TARGETS,
                ModuleSettingsSnapshot.encodeInnerArcTargets(settings.innerArcTargets),
            )
            putExtra(ModulePreferences.EXTRA_MODULE_ENABLED, settings.enabled)
            // 完整快照；上面的旧字段留给尚未重启、仍跑旧版代码的 Hook 进程。
            putExtra(SettingsTransfer.EXTRA_SETTINGS, SettingsTransfer.toBundle(settings))
        }
        // 接收端按 sentFromUid 核对发送方；不共享身份时该值为 INVALID_UID，推送会被丢弃，
        // 只能等 LSPosed 远程配置的变更回调，而模块重装后该回调在 Hook 进程里不再触发。
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        try {
            context.sendBroadcast(intent.setPackage("android"), null, options)
            context.sendBroadcast(intent.setPackage("com.coloros.smartsidebar"), null, options)
            context.sendBroadcast(intent.setPackage("com.android.systemui"), null, options)
            context.sendBroadcast(intent.setPackage("com.android.launcher"), null, options)
        } catch (_: RuntimeException) {
            // Remote preference commit remains authoritative if a target process is unavailable.
        }
    }

    private fun handleServiceBind(service: XposedService) {
        if (diedBeforeBind.remove(service) != null) return
        if (liveServices.put(service, Unit) != null) return
        pendingServices[service] = Unit
        watchBinderDeath(service)
        // 新包装优先，避免同一 Binder 的旧 RemotePreferences 缓存被继续复用。
        pruneServices(service)
        reconcileServices()
    }

    private fun handleServiceDied(service: XposedService) {
        if (!liveServices.containsKey(service)) {
            diedBeforeBind[service] = Unit
            return
        }
        discardService(service)
        reconcileServices()
    }

    /** Service 102 没有公开 Binder，字段名由 keep 规则保护；取不到时回退到只读 API 探测。 */
    private fun serviceBinder(service: XposedService): IBinder? = serviceBinders[service] ?: runCatching {
        val field = XposedService::class.java.getDeclaredField("mService").also { it.isAccessible = true }
        (field.get(service) as? IInterface)?.asBinder()
    }.getOrNull()?.also { serviceBinders[service] = it }

    private fun watchBinderDeath(service: XposedService) {
        val binder = serviceBinder(service) ?: return
        val recipient = IBinder.DeathRecipient { worker.execute { handleServiceDied(service) } }
        try {
            binder.linkToDeath(recipient, 0)
            serviceDeaths[service] = recipient
        } catch (_: android.os.RemoteException) {
            // 已死亡的 Binder 会在紧随其后的 pruneServices 中移除。
        } catch (_: RuntimeException) {
            // 监听不可用时仍保留框架原有死亡回调，不能阻止连接激活。
        }
    }

    /** 死亡是确定状态；权限、能力或暂时性读取失败不代表旧对象已经失效。 */
    private fun serviceAlive(service: XposedService): Boolean {
        serviceBinder(service)?.let { return it.isBinderAlive }
        return try {
            service.apiVersion
            true
        } catch (error: RuntimeException) {
            generateSequence<Throwable>(error) { it.cause }.none { it is DeadObjectException }
        }
    }

    private fun pruneServices(preferred: XposedService? = null) {
        val candidates = buildList {
            if (preferred != null && liveServices.containsKey(preferred)) add(preferred)
            liveServices.keys.filterTo(this) { it !== preferred }
        }
        FrameworkServiceSelection.obsolete(candidates, ::serviceAlive, ::serviceBinder).forEach(::discardService)
    }

    private fun discardService(service: XposedService) {
        liveServices.remove(service)
        pendingServices.remove(service)
        unavailableServices.remove(service)
        val binder = serviceBinders.remove(service)
        val recipient = serviceDeaths.remove(service)
        if (binder != null && recipient != null) runCatching { binder.unlinkToDeath(recipient, 0) }
        if (activeConnection?.service === service) activeConnection = null
    }

    private fun reconcileServices() {
        pruneServices()
        if (liveServices.size > 1) {
            mutableState.value = multipleServicesState()
            return
        }
        val service = liveServices.keys.firstOrNull()
        if (service == null) {
            activeConnection = null
            mutableState.value = FrameworkConnectionState()
            return
        }
        activeConnection?.takeIf { it.service === service }?.let { connection ->
            mutableState.value = connection.confirmedState
            return
        }
        unavailableServices[service]?.let { state ->
            mutableState.value = state
            return
        }
        if (pendingServices.remove(service) != null) {
            activateService(service)
            return
        }
        mutableState.value =
            FrameworkConnectionState(
                status = FrameworkConnectionStatus.Error,
                issue = FrameworkConnectionIssue.ConnectionFailed,
            )
    }

    private fun activateService(service: XposedService) {
        val result = probeService(service)
        if (result.connection != null) {
            unavailableServices.remove(service)
            activeConnection = result.connection
        } else {
            activeConnection = null
            unavailableServices[service] = result.state
        }
        mutableState.value = result.state
    }

    private fun probeService(service: XposedService): ProbeResult {
        var frameworkName: String? = null
        var frameworkVersion: String? = null
        var apiVersion: Int? = null
        return try {
            apiVersion = service.apiVersion
            frameworkName = service.frameworkName.normalizedMetadata()
            frameworkVersion = service.frameworkVersion.normalizedMetadata()
            val properties = service.frameworkProperties
            when {
                apiVersion < XposedService.API_102 ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.ServiceApiTooOld)
                properties and XposedService.PROP_CAP_REMOTE == 0L ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.RemoteCapabilityMissing)
                properties and XposedService.PROP_CAP_SYSTEM == 0L ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.SystemCapabilityMissing)
                else -> {
                    val preferences = service.getRemotePreferences(ModulePreferences.GROUP)
                    val settings = readOrRepairSettings(preferences) ?: return ProbeResult.error(
                        frameworkName,
                        frameworkVersion,
                        apiVersion,
                        FrameworkConnectionIssue.WriteFailed,
                    )
                    val scopes = service.scope.filterTo(linkedSetOf()) { it in FrameworkConnectionState.REQUIRED_SCOPES }
                    ProbeResult.connected(service, preferences, frameworkName, frameworkVersion, apiVersion, settings, scopes)
                }
            }
        } catch (exception: XposedService.ServiceException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        } catch (exception: UnsupportedOperationException) {
            ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.RemoteCapabilityMissing)
        } catch (exception: SecurityException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        } catch (exception: RuntimeException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        }
    }

    private fun readOrRepairSettings(preferences: SharedPreferences): ModuleSettingsSnapshot? =
        try {
            ModuleSettingsSnapshot.readFrom(preferences)
        } catch (exception: ClassCastException) {
            ModuleSettingsSnapshot().takeIf { defaults -> commitSettings(preferences, defaults) }
        }

    // KTX edit 返回 Unit；这里必须将远端 commit 的失败结果传回 UI。
    @SuppressLint("UseKtx")
    private fun commitSettings(preferences: SharedPreferences, settings: ModuleSettingsSnapshot): Boolean =
        try {
            settings.writeTo(preferences.edit()).commit()
        } catch (exception: XposedService.ServiceException) {
            false
        } catch (exception: UnsupportedOperationException) {
            false
        } catch (exception: SecurityException) {
            false
        } catch (exception: RuntimeException) {
            false
        }

    private fun isolateFailedConnection(connection: ActiveConnection, previous: FrameworkConnectionState) {
        if (!serviceAlive(connection.service)) {
            discardService(connection.service)
            reconcileServices()
            return
        }
        activeConnection = null
        val failed =
            previous.copy(
                status = FrameworkConnectionStatus.Error,
                isUpdating = false,
                isRequestingScope = false,
                issue = FrameworkConnectionIssue.WriteFailed,
            )
        unavailableServices[connection.service] = failed
        mutableState.value = failed
    }

    private fun refreshAfterScopeRequest(connection: ActiveConnection) {
        if (activeConnection?.service !== connection.service) return
        pendingServices[connection.service] = Unit
        activeConnection = null
        reconcileServices()
    }

    private fun finishScopeRequestWithError(connection: ActiveConnection) {
        if (activeConnection?.service !== connection.service) return
        val failed =
            connection.confirmedState.copy(
                isRequestingScope = false,
                issue = FrameworkConnectionIssue.ScopeRequestFailed,
            )
        activeConnection = connection.copy(confirmedState = failed)
        mutableState.value = if (liveServices.size > 1) multipleServicesState() else failed
    }

    private fun multipleServicesState() =
        FrameworkConnectionState(
            status = FrameworkConnectionStatus.Incompatible,
            issue = FrameworkConnectionIssue.MultipleServices,
        )

    private fun String.normalizedMetadata(): String? = trim().take(MAX_METADATA_LENGTH).ifEmpty { null }

    private data class ActiveConnection(
        val service: XposedService,
        val preferences: SharedPreferences,
        val confirmedState: FrameworkConnectionState,
    )

    private data class ProbeResult(val connection: ActiveConnection?, val state: FrameworkConnectionState) {
        companion object {
            fun connected(
                service: XposedService,
                preferences: SharedPreferences,
                frameworkName: String?,
                frameworkVersion: String?,
                apiVersion: Int,
                settings: ModuleSettingsSnapshot,
                scopes: Set<String>,
            ): ProbeResult {
                val state =
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Connected,
                        frameworkName = frameworkName,
                        frameworkVersion = frameworkVersion,
                        apiVersion = apiVersion,
                        settings = settings,
                        grantedScopes = scopes,
                    )
                return ProbeResult(ActiveConnection(service, preferences, state), state)
            }

            fun failure(name: String?, version: String?, api: Int?, issue: FrameworkConnectionIssue) =
                ProbeResult(
                    null,
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Incompatible,
                        frameworkName = name,
                        frameworkVersion = version,
                        apiVersion = api,
                        issue = issue,
                    ),
                )

            fun error(
                name: String?,
                version: String?,
                api: Int?,
                issue: FrameworkConnectionIssue = FrameworkConnectionIssue.ConnectionFailed,
            ) =
                ProbeResult(
                    null,
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Error,
                        frameworkName = name,
                        frameworkVersion = version,
                        apiVersion = api,
                        issue = issue,
                    ),
                )
        }
    }

    private companion object {
        const val WORKER_THREAD_NAME = "FlymeFreeform-Service"
        const val MAX_METADATA_LENGTH = 80
    }
}
