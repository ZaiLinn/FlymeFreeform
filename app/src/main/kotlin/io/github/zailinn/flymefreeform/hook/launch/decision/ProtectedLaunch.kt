package io.github.zailinn.flymefreeform.hook.launch.decision

import java.net.URI

/** 需要完成操作或回传结果的入口在所有小窗决策前放行，不更改任务栈。 */
internal object ProtectedLaunch {
    private val actions = setOf(
        "android.intent.action.EDIT",
        "android.intent.action.INSERT",
        "android.intent.action.INSERT_OR_EDIT",
        "com.android.camera.action.CROP",
        "com.google.zxing.client.android.SCAN",
        "android.app.action.CONFIRM_DEVICE_CREDENTIAL",
        "android.app.action.CONFIRM_DEVICE_CREDENTIAL_WITH_USER",
        "android.content.pm.action.REQUEST_PERMISSIONS",
        "android.settings.REQUEST_MANAGE_MEDIA",
        "android.settings.BIOMETRIC_ENROLL",
    )
    // 匹配操作入口，不按银行、钱包、地图、相机等应用整包排除。
    private val operation = Regex(
        "(?:^|[._/])(?:pay|payment|checkout|cashier|schemepay|scan|qrcode|barcode|crop|" +
            "pick_contact|pick_location|select_location|verify_identity|realname|liveness|" +
            "biometric|fingerprint|applock)(?:$|[._/])",
        RegexOption.IGNORE_CASE,
    )
    private val activity = Regex(
        "(?:pay|payment|cashier|checkout)(?:activity|entry|entrance|result|callback)|" +
            "mspcontainer|uppay|(?:qr|bar)code|(?:scan|scanner|crop|capture|camera)(?:activity|capture)|" +
            "(?:face|identity|bank|security|secure)(?:verify|verification|auth|recognition)|" +
            "verifyidentity|verificationactivity|verifyactivity|realname|realauth|realperson|" +
            "idcardverify|liveness|livingdetect|facedetect|faceid|biometric|fingerprint|" +
            "applock|appunlock|confirmlock|confirmdevicecredential|credentialselector|" +
            "grantpermissions|permissionactivity|" +
            "(?:contact|location|poi)(?:picker|select)|(?:pick|select)(?:contact|location|poi)",
        RegexOption.IGNORE_CASE,
    )

    fun reason(s: LaunchSnapshot, target: String? = s.preemptTarget): String? {
        if (CredentialLaunch.matches(s)) return "ZOOM_SKIP_CREDENTIAL"
        if (AuthorizationLaunch.matches(s)) return "ZOOM_SKIP_AUTHORIZATION"
        if (s.action in LaunchRules.PICKER_ACTIONS) return "ZOOM_SKIPPED_PICKER"
        if (s.action in LaunchRules.CAPTURE_ACTIONS) return "ZOOM_SKIPPED_CAPTURE"
        if (s.operationExtra || s.action in actions ||
            s.action?.let { operation.containsMatchIn(it) } == true ||
            listOfNotNull(s.componentClass, s.resolvedClass).any {
                activity.containsMatchIn(it.substringAfterLast('.'))
            } || isOperationUri(s.dataString) ||
            target == "com.android.permissioncontroller" || target == "com.google.android.permissioncontroller"
        ) return "ZOOM_SKIP_OPERATION"
        // 桌面卡片有自己的结果协议；应用内等结果也不需要注入小窗。
        if (s.hasResultTo && s.requestCode >= 0 &&
            s.callingPackage != LaunchRules.LAUNCHER_PACKAGE
        ) return "ZOOM_SKIP_WAITS_FOR_RESULT_" + target.orEmpty()
        return null
    }

    private fun isOperationUri(data: String?): Boolean = data != null && runCatching {
        val uri = URI(data)
        listOfNotNull(uri.scheme, uri.host, uri.path).any { operation.containsMatchIn(it) }
    }.getOrDefault(false)
}
