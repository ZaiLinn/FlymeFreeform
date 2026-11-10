package io.github.zailinn.flymefreeform.hook.launch.decision

import org.junit.Assert.*
import org.junit.Test

/** 序列及 flags 来自计划 B4 的 2026-11-08 设备日志；时间用同一单调时基。 */
class LaunchDeciderTest {
    private val telegram = "org.telegram.messenger.web"
    private val x = "com.twitter.android"
    private val chrome = "com.android.chrome"
    private val launcher = "com.android.launcher"
    private val systemUi = "com.android.systemui"

    private class Environment : LaunchEnvironment {
        override var enabled = true
        override var zoomLaunch = true
        override var zoomLaunchCard = true
        override val systemUiUid = 10010
        override val launcherUid = 10020
        var unlocked = true
        var shown: String? = null
        var owner: String? = null
        var implicit: String? = null
        var resolveCalls = 0
        val excluded = mutableSetOf<String>()
        val thirdParty = mutableSetOf("org.telegram.messenger.web", "com.twitter.android", "com.android.chrome")
        val controlCenter = mutableSetOf<String>()
        override fun deviceUnlocked(refresh: Boolean) = unlocked
        override fun isExcluded(pkg: String) = pkg in excluded
        override fun isThirdParty(pkg: String) = pkg in thirdParty
        override fun dispatchOwner(callingPackage: String?, realCallingUid: Int, requestRealUid: Int) = owner
        override fun zoomShownPackage() = shown
        override fun fromControlCenter(pkg: String) = pkg in controlCenter
        override fun resolveImplicitTarget(): String? { resolveCalls++; return implicit }
    }
    private fun link(target: String = x) = LaunchSnapshot(
        action = LaunchRules.ACTION_VIEW, dataString = "https://example.test/post/42", flags = 0x10000400,
        componentPackage = target, callingPackage = telegram, callingUid = 10200,
        hasResultTo = true, requestCode = -1,
    )
    private fun pre(s: LaunchSnapshot, e: Environment, m: LaunchMemory, time: Long) = LaunchDecider.decidePreempt(s, e, m, time)
    private fun inject(s: LaunchSnapshot, e: Environment, m: LaunchMemory, time: Long) = LaunchDecider.decideInject(s, e, m, time)
    private fun applyEvents(d: LaunchDecision, m: LaunchMemory, time: Long = 1000) {
        d.events.filterIsInstance<LaunchEvent.ReturnUpdate>().forEach { m.apply(it.check) }
        d.events.filterIsInstance<LaunchEvent.ProtectFlow>().forEach { m.protectFlow(it.target, it.caller, time) }
    }
    /** 模拟执行层成功之后的更新；失败路径不更新启动/重复记忆。 */
    private fun successful(d: LaunchDecision, m: LaunchMemory, time: Long) {
        applyEvents(d, m, time)
        when (d) {
            is LaunchDecision.Preempt -> {
                m.markZoomed(d.target, d.selfRelaunch, time)
                if (d.linkKey != null) { m.markLink(d.linkKey, time); m.record(d.callingPackage, d.target, time) }
            }
            is LaunchDecision.ReopenInZoom -> { m.markLink(d.linkKey, time); m.record(d.callingPackage, d.target, time) }
            is LaunchDecision.Inject -> {
                if (d.markSelfRelaunch) m.markZoomed(d.selfTarget!!, true, time)
                d.recordInterApp?.let { (caller, target) -> m.record(caller, target, time) }
            }
            else -> Unit
        }
    }
    private fun logs(d: LaunchDecision) = d.events.mapNotNull { when (it) {
        is LaunchEvent.Log -> it.code
        is LaunchEvent.LazyLog -> it.code()
        else -> null
    } }
    private fun reason(d: LaunchDecision) = (d as? LaunchDecision.Inject)?.reason
    private fun self(target: String, data: String? = null, flags: Int = 0x1801c000) = link(target).copy(
        callingPackage = target, action = LaunchRules.ACTION_MAIN, dataString = data, flags = flags,
    )

    @Test fun telegramXFirstLinkDuplicateAndMainRelaunch() {
        val e = Environment(); val m = LaunchMemory(); val first = pre(link(), e, m, 1000)
        assertTrue(first is LaunchDecision.Preempt)
        assertTrue(logs(first).contains("ZOOM_DECISION_execute_oktrue_rt1_self0_link1_pi0_su0_cp${telegram}_tg$x"))
        successful(first, m, 1000)
        assertTrue(pre(link().copy(flags = 0x10000000), e, m, 1060) is LaunchDecision.SwallowDuplicate)
        val restart = self(x)
        assertTrue(pre(restart, e, m, 1250) is LaunchDecision.PassThrough)
        val d = inject(restart, e, m, 1250) as LaunchDecision.Inject
        assertEquals("self_relaunch", d.reason); assertTrue(d.markSelfRelaunch)
    }
    @Test fun alreadyZoomedXReopensAndSwallowsDuplicate() {
        val e = Environment().apply { shown = x }; val m = LaunchMemory()
        val first = pre(link(), e, m, 1000)
        assertTrue(first is LaunchDecision.ReopenInZoom)
        assertEquals("ZOOM_LINK_TARGET_IN_ZOOM_execute_$x", logs(first).first())
        successful(first, m, 1000)
        assertTrue(pre(link().copy(flags = 0x10000000), e, m, 1060) is LaunchDecision.SwallowDuplicate)
    }
    @Test fun chromeDispatcherRelaunchRetainsZoom() {
        val e = Environment(); val m = LaunchMemory()
        successful(pre(link(chrome), e, m, 1000), m, 1000)
        val d = pre(self(chrome, "https://example.test/post/42", 0x1a080000), e, m, 1250) as LaunchDecision.Preempt
        assertTrue(d.selfRelaunch)
    }
    @Test fun relayHopLimitIsTwo() {
        val e = Environment(); val m = LaunchMemory()
        successful(pre(link(chrome), e, m, 1000), m, 1000)
        val relay = self(chrome, "https://example.test/post/42", 0x1a080000)
        for (time in listOf(1250L, 1500L)) {
            val d = pre(relay, e, m, time) as LaunchDecision.Preempt
            assertTrue(d.selfRelaunch); successful(d, m, time)
        }
        assertTrue(pre(relay, e, m, 1750) is LaunchDecision.PassThrough)
    }
    @Test fun relayExpiresAfterThreeSeconds() {
        val e = Environment(); val m = LaunchMemory()
        successful(pre(link(), e, m, 1000), m, 1000)
        assertEquals("self_relaunch", reason(inject(self(x), e, m, 3999)))
        assertNull(reason(inject(self(x), e, m, 4000)))
        assertNull(reason(inject(self(x), e, m, 4001)))
    }
    @Test fun activityForResultDoesNotPreemptOriginalChain() {
        val e = Environment(); val m = LaunchMemory()
        assertTrue(pre(link().copy(requestCode = 100), e, m, 1000) is LaunchDecision.PassThrough)
        // 2026111002 起注入路径同样放行：应用间等结果的启动保持全屏。
        assertNull(reason(inject(link().copy(requestCode = 100), e, m, 1000)))
    }
    @Test fun inAppNavigationWithoutNewTaskPassesThrough() {
        val e = Environment(); val m = LaunchMemory()
        successful(pre(link(), e, m, 1000), m, 1000)
        val page = self(x).copy(flags = 0, action = LaunchRules.ACTION_VIEW)
        assertTrue(pre(page, e, m, 1250) is LaunchDecision.PassThrough)
        assertTrue(inject(page, e, m, 1250) is LaunchDecision.PassThrough)
    }
    @Test fun launcherGlobalSearchNeverInjects() {
        val e = Environment(); val m = LaunchMemory()
        val d = inject(link().copy(callingPackage = launcher, action = LaunchRules.ACTION_GLOBAL_SEARCH), e, m, 1000)
        assertTrue(d is LaunchDecision.PassThrough); assertEquals(listOf("ZOOM_SKIP_GLOBAL_SEARCH"), logs(d))
    }
    @Test fun lockedDeviceDoesNotPreemptOrReopenOrSwallow() {
        val e = Environment().apply { unlocked = false }; val m = LaunchMemory()
        m.markLink("$telegram|$x|https://example.test/post/42", 900)
        assertTrue(pre(link(), e, m, 1000) is LaunchDecision.PassThrough)
        e.shown = x
        assertTrue(pre(link(), e, m, 1000) is LaunchDecision.PassThrough)
    }
    @Test fun zoomLaunchOffDisablesLinkAndRelayDecisions() {
        val e = Environment().apply { zoomLaunch = false }; val m = LaunchMemory()
        m.markZoomed(x, false, 900)
        for (s in listOf(link(), self(x))) {
            assertTrue(pre(s, e, m, 1000) is LaunchDecision.PassThrough)
            assertTrue(inject(s, e, m, 1000) is LaunchDecision.PassThrough)
        }
    }
    @Test fun returningToCallerIsRememberedAcrossBothStages() {
        val e = Environment(); val m = LaunchMemory()
        successful(pre(link(), e, m, 1000), m, 1000)
        val back = link(telegram).copy(callingPackage = x)
        val d = pre(back, e, m, 1500)
        assertTrue(d is LaunchDecision.PassThrough)
        assertTrue(m.checkReturning(x, telegram, 1500).clearPending)
        applyEvents(d, m)
        val injected = inject(back.copy(stage = "executeRequest"), e, m, 1500)
        assertTrue(injected is LaunchDecision.PassThrough)
        assertEquals(listOf("ZOOM_INTER_APP_RETURN"), logs(injected))
    }
    @Test fun excludedTargetIsNotInterceptedOrInjected() {
        val e = Environment().apply { excluded += x }; val m = LaunchMemory()
        assertTrue(pre(link(), e, m, 1000) is LaunchDecision.PassThrough)
        val d = inject(link(), e, m, 1000)
        assertTrue(d is LaunchDecision.PassThrough); assertEquals(listOf("ZOOM_SKIPPED_EXCLUDED_$x"), logs(d))
    }
    @Test fun installerTargetsBypassEveryZoomEntrance() {
        val targets = listOf(
            "com.rosan.installer.x.revived", "com.rosan.installer.x.revived.debug",
        )
        for (target in targets) {
            val e = Environment().apply { shown = target; owner = telegram; controlCenter += target }
            val m = LaunchMemory().apply {
                markZoomed(target, false, 900)
                markLink("$telegram|$target|https://example.test/post/42", 900)
            }
            val base = link(target)
            val sources = listOf(
                base, base.copy(requestCode = 7),
                base.copy(action = "com.google.android.gms.auth.GOOGLE_SIGN_IN", dataString = null),
                base.copy(componentPackage = null, resolvedTarget = target),
                self(target, "https://example.test/post/42"), self(target),
                base.copy(callingPackage = launcher),
                base.copy(callingPackage = systemUi, hasPendingIntent = true),
                base.copy(zoomSourceExtra = 1, realCallingUid = e.systemUiUid),
                base.copy(quickSettingsExtra = true, realCallingUid = e.systemUiUid),
                base.copy(callingPackage = "android.ext.services", hasResultTo = false),
            )
            for (source in sources) {
                assertTrue("preempt: $source", pre(source, e, m, 1000) is LaunchDecision.PassThrough)
                assertTrue("inject: $source", inject(source, e, m, 1000) is LaunchDecision.PassThrough)
            }
            e.implicit = target
            assertTrue(inject(base.copy(componentPackage = null), e, m, 1000) is LaunchDecision.PassThrough)
        }
    }
    @Test fun googleNormalLinksAndNotificationsRemainEligibleForZoom() {
        for (target in listOf(
            "com.google.android.googlequicksearchbox", "com.google.android.youtube",
            "com.google.android.apps.maps", "com.google.android.gm",
            "com.google.android.apps.photos", "com.android.vending", "com.google.android.gms",
        )) {
            val e = Environment(); val m = LaunchMemory()
            assertFalse(LaunchRules.isZoomLaunchBlocked(target))
            assertTrue(pre(link(target), e, m, 1000) is LaunchDecision.Preempt)
            assertEquals("inter_app", reason(inject(link(target), e, m, 1000)))
            assertEquals("notification", reason(inject(link(target).copy(
                callingPackage = systemUi, hasPendingIntent = true,
            ), e, m, 1000)))
        }
    }
    @Test fun chromeVariantsRemainEligibleForZoom() {
        for (target in listOf(chrome, "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")) {
            val e = Environment(); val m = LaunchMemory()
            assertFalse(LaunchRules.isZoomLaunchBlocked(target))
            assertTrue(pre(link(target), e, m, 1000) is LaunchDecision.Preempt)
            assertEquals("inter_app", reason(inject(link(target), e, m, 1000)))
        }
        assertFalse(LaunchRules.isZoomLaunchBlocked("com.googleexample.app"))
        assertFalse(LaunchRules.isZoomLaunchBlocked("com.rosan.installer.x.revivedother"))
    }
    @Test fun authorizationSkipsPreemptDuplicateReopenAndInjection() {
        val e = Environment().apply { shown = x; owner = telegram }
        val entries = listOf(
            link("com.google.android.gms").copy(
                action = "com.google.android.gms.auth.GOOGLE_SIGN_IN", dataString = null,
            ),
            link("com.google.android.gms").copy(
                componentClass = "com.google.android.gms.auth.api.signin.ui.SignInActivity",
            ),
            link().copy(action = "com.example.LOGIN", dataString = null),
            link().copy(componentClass = "com.example.OAuthActivity"),
            link().copy(componentClass = "com.example.SSOActivity"),
            link().copy(componentClass = "com.example.SignInActivity"),
            link().copy(componentPackage = null, resolvedTarget = x, resolvedClass = "com.example.AuthActivity"),
            link().copy(authorizationExtra = true),
            link(chrome).copy(dataString = "https://id.example.test/oauth2/authorize"),
            link(chrome).copy(dataString = "https://id.example.test/start?client_id=app&redirect_uri=app%3A%2F%2Fdone"),
            link().copy(dataString = "myapp://callback?code=example&state=example"),
            link().copy(dataString = "myapp://callback#access_token=example&state=example"),
        )
        for (entry in entries) {
            val m = LaunchMemory().apply {
                markZoomed(x, false, 900)
                markLink("$telegram|${entry.preemptTarget}|${entry.dataString}", 900)
            }
            val variants = listOf(entry, entry.copy(hasResultTo = false),
                entry.copy(requestCode = 7), entry.copy(zoomSourceExtra = 1, realCallingUid = e.systemUiUid),
                entry.copy(callingPackage = x, flags = LaunchRules.FLAG_ACTIVITY_NEW_TASK))
            for (source in variants) {
                val preempt = pre(source, e, m, 1000)
                val injection = inject(source, e, m, 1000)
                assertTrue("preempt: $source", preempt is LaunchDecision.PassThrough)
                assertTrue("inject: $source", injection is LaunchDecision.PassThrough)
                assertEquals(listOf("ZOOM_SKIP_AUTHORIZATION"), logs(preempt))
                assertEquals(listOf("ZOOM_SKIP_AUTHORIZATION"), logs(injection))
            }
        }
    }
    @Test fun normalLinksAndSharingAreNotAuthorization() {
        for (source in listOf(
            link().copy(componentClass = "com.example.ProcessorActivity"),
            link().copy(action = "android.intent.action.SEND"),
            link(chrome).copy(dataString = "https://example.test/article?state=published"),
            link(chrome).copy(dataString = "https://example.test/article?code=42"),
            link(chrome).copy(dataString = "https://example.test/article?next=%2Flogin"),
        )) {
            assertFalse(AuthorizationLaunch.matches(source))
            assertEquals("inter_app", reason(inject(source, Environment(), LaunchMemory(), 1000)))
        }
    }
    @Test fun operationFlowsBypassEveryZoomDecisionBeforeAnySideEffects() {
        val e = Environment().apply { shown = x; owner = telegram }
        val base = link()
        val operations = LaunchRules.PICKER_ACTIONS.map { base.copy(action = it) } +
            LaunchRules.CAPTURE_ACTIONS.map { base.copy(action = it) } + listOf(
                base.copy(action = "android.intent.action.EDIT"),
                base.copy(action = "android.intent.action.INSERT"),
                base.copy(action = "android.intent.action.INSERT_OR_EDIT"),
                base.copy(action = "com.android.camera.action.CROP"),
                base.copy(action = "com.google.zxing.client.android.SCAN"),
                base.copy(action = "android.app.action.CONFIRM_DEVICE_CREDENTIAL"),
                base.copy(action = "android.content.pm.action.REQUEST_PERMISSIONS"),
                base.copy(operationExtra = true),
                base.copy(componentPackage = "com.google.android.permissioncontroller"),
                base.copy(dataString = "alipays://platformapi/schemepay?order=example"),
                base.copy(dataString = "https://bank.example.test/checkout?order=example"),
            ) + listOf(
                "H5PayActivity", "PayEntryActivity", "CashierActivity", "MspContainerActivity",
                "BankVerifyActivity", "RealNameActivity", "IdentityVerificationActivity",
                "ScanActivity", "QRCodeActivity", "CaptureActivity", "CropActivity",
                "FaceVerifyActivity", "LivenessActivity", "BiometricActivity",
                "FingerprintActivity", "AppLockActivity", "AppUnlockPasswordActivity",
                "ConfirmDeviceCredentialActivity", "GrantPermissionsActivity",
                "ContactPickerActivity", "LocationPickerActivity", "SelectLocationActivity",
            ).map { base.copy(componentClass = "com.example.$it") }
        for (operation in operations) {
            // 显式、隐式与解析后的组件都必须使用相同规则。
            val resolved = operation.copy(componentClass = null, resolvedClass = operation.componentClass)
            val variants = listOf(operation, resolved,
                operation.copy(hasResultTo = false, hasPendingIntent = true),
                operation.copy(zoomSourceExtra = 1, realCallingUid = e.systemUiUid),
                operation.copy(quickSettingsExtra = true, realCallingUid = e.systemUiUid),
                operation.copy(callingPackage = x, flags = LaunchRules.FLAG_ACTIVITY_NEW_TASK))
            for (source in variants) {
                val m = LaunchMemory().apply {
                    markZoomed(x, false, 900)
                    markLink("${source.callingPackage}|${source.preemptTarget}|${source.dataString}", 900)
                }
                val preempt = pre(source, e, m, 1000)
                val injection = inject(source, e, m, 1000)
                assertTrue("preempt: $source", preempt is LaunchDecision.PassThrough)
                assertTrue("inject: $source", injection is LaunchDecision.PassThrough)
                assertEquals(2, preempt.events.size)
                assertEquals(logs(preempt), logs(injection))
            }
        }
    }
    @Test fun resultRequestsCannotBypassProtectionWithNotificationMarkersOrRelayMemory() {
        val e = Environment().apply { shown = x; owner = telegram }
        val m = LaunchMemory().apply { markZoomed(x, false, 900) }
        for (source in listOf(
            link().copy(requestCode = 7, zoomSourceExtra = 1, realCallingUid = e.systemUiUid),
            self(x).copy(requestCode = 7),
            link().copy(requestCode = 7, hasPendingIntent = true),
        )) {
            assertTrue(pre(source, e, m, 1000) is LaunchDecision.PassThrough)
            val d = inject(source, e, m, 1000)
            assertTrue(d is LaunchDecision.PassThrough)
            assertEquals(listOf("ZOOM_SKIP_WAITS_FOR_RESULT_$x"), logs(d))
        }
    }
    @Test fun credentialRequestsAndSelectorsBypassZoomEvenWithoutResultProtocol() {
        val e = Environment().apply { shown = x; owner = telegram }
        val base = link()
        val entries = listOf(
            base.copy(credentialExtra = true, componentClass = "com.example.MainActivity"),
            base.copy(action = "android.settings.REQUEST_SET_AUTOFILL_SERVICE"),
            base.copy(action = "android.settings.CREDENTIAL_PROVIDER"),
            base.copy(action = "android.settings.ADD_ACCOUNT_SETTINGS"),
            base.copy(action = "android.credentials.ui.action.SELECT"),
            base.copy(action = "androidx.credentials.GET_CREDENTIAL"),
            base.copy(action = "com.example.SELECT_ACCOUNT"),
            base.copy(action = "com.example.FIDO2.SIGN_REQUEST"),
        ) + listOf(
            "AutofillActivity", "AutofillUnlockActivity", "PasskeyActivity", "WebAuthnActivity",
            "Fido2Activity", "CredentialSelectorActivity", "CredentialManagerUi",
            "GetCredentialActivity", "CreateCredentialActivity", "PasswordPickerActivity",
            "PasswordSelectionActivity", "ChooseAccountActivity", "ChooseTypeAndAccountActivity",
            "AccountPickerActivity", "AccountChooserActivity", "GrantCredentialsPermissionActivity",
        ).map { base.copy(componentClass = "com.example.$it") }
        for (entry in entries) {
            for (source in listOf(entry,
                entry.copy(hasResultTo = false, hasPendingIntent = true),
                entry.copy(requestCode = 7),
                entry.copy(zoomSourceExtra = 1, realCallingUid = e.systemUiUid),
                entry.copy(componentClass = null, resolvedClass = entry.componentClass),
                entry.copy(callingPackage = x, flags = LaunchRules.FLAG_ACTIVITY_NEW_TASK),
            )) {
                val m = LaunchMemory().apply {
                    markZoomed(x, false, 900)
                    markLink("${source.callingPackage}|$x|${source.dataString}", 900)
                }
                val first = pre(source, e, m, 1000)
                assertTrue("preempt: $source", first is LaunchDecision.PassThrough)
                assertTrue("inject: $source", inject(source, e, m, 1000) is LaunchDecision.PassThrough)
                assertEquals(listOf("ZOOM_SKIP_CREDENTIAL"), logs(first))
                assertTrue(first.events.any { it is LaunchEvent.ProtectFlow })
                applyEvents(first, m, 1000)
                assertFalse(m.selfRelaunch(x, 1100))
            }
        }
    }
    @Test fun passwordManagerHomepageAndCredentialDocumentationRemainNormalContent() {
        val e = Environment(); val m = LaunchMemory()
        for (target in listOf("com.example.passwordmanager", "com.example.autofill", chrome)) {
            val source = link(target).copy(componentClass = "$target.MainActivity",
                dataString = "https://example.test/docs/passkeys")
            assertNull(ProtectedLaunch.reason(source))
            assertTrue(pre(source, e, m, 1000) is LaunchDecision.Preempt)
            assertEquals("inter_app", reason(inject(source, e, m, 1000)))
        }
    }
    @Test fun protectedOperationClearsOldZoomRelayAndPreservesUnmarkedReturnToCaller() {
        val e = Environment(); val m = LaunchMemory()
        successful(pre(link(), e, m, 1000), m, 1000)
        assertTrue(m.selfRelaunch(x, 1100))
        val operation = link().copy(componentClass = "com.example.CashierActivity")
        val d = pre(operation, e, m, 1200)
        applyEvents(d, m, 1200)
        assertFalse(m.selfRelaunch(x, 1250))
        assertFalse(m.duplicate("$telegram|$x|https://example.test/post/42", 1250))
        // SDK 中转为普通 MAIN 页面也不能再继承之前的小窗自重启记录。
        assertTrue(inject(self(x), e, m, 1250) is LaunchDecision.PassThrough)
        val back = link(telegram).copy(callingPackage = x)
        val returning = pre(back, e, m, 1400)
        assertTrue(returning is LaunchDecision.PassThrough)
        applyEvents(returning, m, 1400)
        assertTrue(inject(back, e, m, 1400) is LaunchDecision.PassThrough)
    }
    @Test fun ordinaryContentInWalletBankCameraAndMapPackagesStillUsesZoom() {
        val e = Environment(); val m = LaunchMemory()
        for (target in listOf("com.example.wallet", "com.example.bank", "com.example.camera", "com.google.android.apps.maps")) {
            val source = link(target).copy(componentClass = "$target.MainActivity")
            assertNull(ProtectedLaunch.reason(source))
            assertTrue(pre(source, e, m, 1000) is LaunchDecision.Preempt)
            assertEquals("inter_app", reason(inject(source, e, m, 1000)))
        }
        for (source in listOf(
            link().copy(componentClass = "com.example.DisplayActivity"),
            link().copy(componentClass = "com.example.ContactListActivity"),
            link().copy(dataString = "https://example.test/display?next=/payment"),
            link().copy(dataString = "geo:0,0?q=example"),
        )) assertNull(ProtectedLaunch.reason(source))
    }
    @Test fun pickerActionsStayBeforeInterAppInjection() {
        val e = Environment(); val m = LaunchMemory()
        LaunchRules.PICKER_ACTIONS.forEach { action ->
            val d = inject(link().copy(action = action), e, m, 1000)
            assertTrue(d is LaunchDecision.PassThrough); assertEquals(listOf("ZOOM_SKIPPED_PICKER"), logs(d))
        }
    }
    @Test fun launcherAndSystemUiAreNotPlainInterAppLinks() {
        val e = Environment(); val m = LaunchMemory()
        for (caller in listOf(launcher, systemUi)) {
            assertTrue(pre(link().copy(callingPackage = caller), e, m, 1000) is LaunchDecision.PassThrough)
        }
    }
    @Test fun injectionReasonsPreserveOldPriority() {
        val e = Environment(); val m = LaunchMemory()
        val base = link().copy(hasResultTo = false, callingPackage = "system.source")
        val cases = listOf(
            base.copy(zoomSourceExtra = 1, realCallingUid = e.systemUiUid) to "notification",
            base.copy(quickSettingsExtra = true, realCallingUid = e.systemUiUid) to "control_center",
            base.copy(callingPackage = systemUi, hasPendingIntent = true) to "notification",
            base.copy(callingPackage = launcher, hasResultTo = true) to "launcher_card",
            base.copy(callingPackage = launcher, componentPackage = "com.android.settings") to "launcher_settings",
            base.copy(callingPackage = x, componentPackage = x, action = LaunchRules.ACTION_MAIN,
                realCallingUid = e.launcherUid, flags = 0x14000000) to "launcher_shortcut",
            base.copy(callingPackage = "android", componentPackage = "org.lsposed.manager", action = LaunchRules.ACTION_MAIN) to "system_launch",
            base.copy(callingPackage = systemUi, callingUid = e.systemUiUid, action = "android.service.quicksettings.action.QS_TILE") to "control_center",
            base.copy(callingPackage = launcher, action = LaunchRules.ACTION_MAIN) to "control_center_launcher",
        )
        e.controlCenter += x
        cases.forEach { (s, expected) -> assertEquals("$s", expected, reason(inject(s, e, m, 1000))) }
    }
    @Test fun launcherCardOffKeepsCardsFullscreenOnly() {
        val e = Environment().apply { zoomLaunchCard = false }; val m = LaunchMemory()
        val base = link().copy(hasResultTo = false, callingPackage = "system.source")
        val card = inject(base.copy(callingPackage = launcher, hasResultTo = true), e, m, 1000)
        assertNull(reason(card))
        assertTrue(card.events.any { it is LaunchEvent.Log && it.code == "ZOOM_SKIP_LAUNCHER_CARD_$x" })
        // 控制中心经桌面代发的记录不能把卡片再接回小窗。
        e.controlCenter += x
        assertNull(reason(inject(base.copy(callingPackage = launcher, hasResultTo = true), e, m, 1000)))
        // 其余桌面入口不变。
        assertEquals("launcher_settings",
            reason(inject(base.copy(callingPackage = launcher, componentPackage = "com.android.settings"), e, m, 1000)))
        assertEquals("launcher_shortcut", reason(inject(base.copy(callingPackage = x, componentPackage = x,
            action = LaunchRules.ACTION_MAIN, realCallingUid = e.launcherUid, flags = 0x14000000), e, m, 1000)))
    }
    @Test fun interAppLaunchWaitingForResultStaysFullscreen() {
        val e = Environment(); val m = LaunchMemory()
        val jump = link().copy(action = "com.example.OPEN", dataString = null)
        assertEquals("inter_app", reason(inject(jump, e, m, 1000)))
        val waiting = inject(jump.copy(requestCode = 7), e, m, 1000)
        assertNull(reason(waiting))
        assertTrue(waiting.events.any { it is LaunchEvent.Log && it.code == "ZOOM_SKIP_WAITS_FOR_RESULT_$x" })
        // 桌面卡片不按应用间处理，等结果也照常小窗。
        assertEquals("launcher_card", reason(inject(jump.copy(callingPackage = launcher, requestCode = 7), e, m, 1000)))
    }
    @Test fun captureActionsStayFullscreen() {
        val e = Environment(); val m = LaunchMemory()
        for (action in LaunchRules.CAPTURE_ACTIONS) {
            assertNull(reason(inject(link().copy(action = action, dataString = null), e, m, 1000)))
            assertNull(reason(inject(link().copy(action = action, dataString = null, hasResultTo = false), e, m, 1000)))
        }
    }
    @Test fun secondaryUserSourcesMatchByAppId() {
        val e = Environment(); val m = LaunchMemory()
        val base = link().copy(hasResultTo = false, callingPackage = "system.source")
        val user10 = 10 * LaunchRules.PER_USER_RANGE
        assertEquals("notification", reason(inject(base.copy(zoomSourceExtra = 1, realCallingUid = user10 + e.systemUiUid), e, m, 1000)))
        assertEquals("launcher_shortcut", reason(inject(base.copy(callingPackage = x, componentPackage = x,
            action = LaunchRules.ACTION_MAIN, realCallingUid = user10 + e.launcherUid, flags = 0x14000000), e, m, 1000)))
        // 别的应用（appId 不同）在任何用户下都不算 SystemUI。
        assertNull(reason(inject(base.copy(zoomSourceExtra = 1, realCallingUid = user10 + e.systemUiUid + 1), e, m, 1000)))
    }
    @Test fun secondarySystemUiMatchesEachCallingIdentityAndPreservesFullscreen() {
        val e = Environment()
        val uid = 10 * LaunchRules.PER_USER_RANGE + e.systemUiUid
        val base = link().copy(hasResultTo = false, callingPackage = "system.source", zoomSourceExtra = 1)
        val sources = listOf(base.copy(callingUid = uid), base.copy(realCallingUid = uid), base.copy(requestRealCallingUid = uid))
        sources.forEach { source ->
            assertEquals("notification", reason(inject(source, e, LaunchMemory(), 1000)))
            assertNull(reason(inject(source.copy(optionsFullscreen = true), e, LaunchMemory(), 1000)))
        }
    }

    @Test fun secondaryLauncherShortcutAcceptsRealAndRequestIdentitiesOnly() {
        val e = Environment()
        val uid = 10 * LaunchRules.PER_USER_RANGE + e.launcherUid
        val base = self(x).copy(hasResultTo = false, realCallingUid = -1, requestRealCallingUid = -1)
        assertEquals("launcher_shortcut", reason(inject(base.copy(realCallingUid = uid), e, LaunchMemory(), 1000)))
        assertEquals("launcher_shortcut", reason(inject(base.copy(requestRealCallingUid = uid), e, LaunchMemory(), 1000)))
        assertNull(reason(inject(base.copy(callingUid = uid), e, LaunchMemory(), 1000)))
    }

    @Test fun invalidOrDifferentAppIdsCannotImpersonateSystemUiInAnyUser() {
        val e = Environment()
        val base = link().copy(hasResultTo = false, callingPackage = "system.source", zoomSourceExtra = 1)
        listOf(-1, 0, 100_000, 10 * LaunchRules.PER_USER_RANGE + e.systemUiUid + 1).forEach { uid ->
            val source = base.copy(callingUid = uid, realCallingUid = uid, requestRealCallingUid = uid)
            assertNull(reason(inject(source, e, LaunchMemory(), 1000)))
            assertTrue(pre(source, e, LaunchMemory(), 1000) is LaunchDecision.PassThrough)
        }
    }
    @Test fun systemDispatcherCanPreempt() {
        val e = Environment().apply { owner = telegram }; val m = LaunchMemory()
        val d = pre(link().copy(callingPackage = "android.ext.services", hasResultTo = false), e, m, 1000)
        assertTrue(d is LaunchDecision.Preempt)
    }
    @Test fun appliedMarkerPreventsSecondInjection() {
        val e = Environment(); val m = LaunchMemory()
        assertTrue(inject(link().copy(zoomAppliedExtra = true), e, m, 1000) is LaunchDecision.PassThrough)
    }
    @Test fun failedPreemptDoesNotCreateDuplicateOrRelayMemory() {
        val e = Environment(); val m = LaunchMemory()
        val first = pre(link(), e, m, 1000)
        applyEvents(first, m)
        assertTrue(pre(link(), e, m, 1060) is LaunchDecision.Preempt)
        assertTrue(inject(self(x), e, m, 1250) is LaunchDecision.PassThrough)
    }
    @Test fun implicitResolutionIsLazyAndExplicitTargetWins() {
        val e = Environment().apply { implicit = chrome }; val m = LaunchMemory()
        inject(link(), e, m, 1000)
        assertEquals(0, e.resolveCalls)
        assertEquals("inter_app", reason(inject(link().copy(componentPackage = null), e, m, 1000)))
        assertEquals(1, e.resolveCalls)
    }
    @Test fun sourceMarkersCannotBypassBlacklist() {
        val e = Environment().apply { excluded += x }; val m = LaunchMemory()
        assertNull(reason(inject(link().copy(zoomSourceExtra = 1, callingUid = e.systemUiUid, action = LaunchRules.PICKER_ACTIONS.first()), e, m, 1000)))
    }

    @Test fun allEntrancesRespectExclusionAndLock() {
        val e = Environment(); val m = LaunchMemory()
        val sources = listOf(
            link().copy(hasResultTo = false, callingUid = e.systemUiUid, callingPackage = systemUi),
            link().copy(zoomSourceExtra = 1, realCallingUid = e.systemUiUid),
            link().copy(quickSettingsExtra = true, realCallingUid = e.systemUiUid),
            link().copy(hasResultTo = false, callingPackage = "android.ext.services"))
        e.owner = telegram
        e.excluded += x
        sources.forEach {
            assertTrue(pre(it, e, m, 1000) is LaunchDecision.PassThrough)
            assertTrue(inject(it, e, m, 1000) is LaunchDecision.PassThrough)
        }
        e.excluded.clear(); e.unlocked = false
        sources.forEach { assertTrue(inject(it, e, m, 1000) is LaunchDecision.PassThrough) }
    }

    @Test fun systemUiExplicitFullscreenKeepsNotificationFullscreen() {
        val e = Environment(); val m = LaunchMemory()
        val base = link().copy(hasResultTo = false, callingPackage = systemUi, hasPendingIntent = true,
            realCallingUid = e.systemUiUid)
        assertEquals("notification", reason(inject(base, e, m, 1000)))
        assertTrue(inject(base.copy(optionsFullscreen = true), e, m, 1000) is LaunchDecision.PassThrough)
        val tile = link().copy(hasResultTo = false, callingPackage = systemUi, callingUid = e.systemUiUid)
        assertTrue(pre(tile, e, m, 1000) is LaunchDecision.Preempt)
        assertTrue(pre(tile.copy(optionsFullscreen = true), e, m, 1000) is LaunchDecision.PassThrough)
    }

    @Test fun untrustedFullscreenOptionsDoNotBlockInterApp() {
        val e = Environment(); val m = LaunchMemory()
        assertEquals("inter_app", reason(inject(link().copy(optionsFullscreen = true), e, m, 1000)))
    }

    @Test fun untrustedMarkerDoesNotCreateNotificationReason() {
        val e = Environment(); val m = LaunchMemory()
        assertNotEquals("notification", reason(inject(link().copy(zoomSourceExtra = 1), e, m, 1000)))
        assertNotEquals("control_center", reason(inject(link().copy(quickSettingsExtra = true), e, m, 1000)))
    }
}
