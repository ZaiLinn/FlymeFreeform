package io.github.zailinn.flymefreeform.hook.launch.decision

/** 按凭据请求、选择器和填充入口保护，不整包排除密码管理器或浏览器。 */
internal object CredentialLaunch {
    // 只检查键是否存在，不读取密码、通行密钥、AssistStructure 或凭据请求内容。
    val EXTRA_KEYS = setOf(
        "android.view.autofill.extra.ASSIST_STRUCTURE",
        "android.view.autofill.extra.AUTHENTICATION_RESULT",
        "android.service.credentials.extra.GET_CREDENTIAL_REQUEST",
        "android.service.credentials.extra.CREATE_CREDENTIAL_REQUEST",
        "android.service.credentials.extra.BEGIN_GET_CREDENTIAL_REQUEST",
        "android.service.credentials.extra.GET_CREDENTIAL_RESPONSE",
        "android.service.credentials.extra.CREATE_CREDENTIAL_RESPONSE",
        "android.service.credentials.extra.BEGIN_GET_CREDENTIAL_RESPONSE",
    )

    private val actions = setOf(
        "android.settings.REQUEST_SET_AUTOFILL_SERVICE",
        "android.settings.CREDENTIAL_PROVIDER",
        "android.settings.ADD_ACCOUNT_SETTINGS",
    )
    private val actionEntry = Regex(
        "(?:^|[._])(?:autofill|passkey|webauthn|fido2?|choose_account|select_account|account_picker|" +
            "(?:get|create|save)_credential|(?:pick|choose|select)_password)(?:$|[._])",
        RegexOption.IGNORE_CASE,
    )
    private val activityEntry = Regex(
        "autofill|passkey|webauthn|fido(?:2|activity)|credentialmanager(?:ui|activity)|" +
            "(?:credential|password)(?:selector|picker|selection|fill|save)|" +
            "(?:get|create|save|pick|choose|select)(?:credential|password|account)|" +
            "choosetypeandaccount|account(?:picker|chooser|selection)|grantcredentialspermission",
        RegexOption.IGNORE_CASE,
    )

    fun matches(s: LaunchSnapshot): Boolean = s.credentialExtra ||
        s.action in actions || s.action?.let {
            it.startsWith("android.credentials.") || it.startsWith("androidx.credentials.") ||
                it.startsWith("android.service.credentials.") ||
                actionEntry.containsMatchIn(it)
        } == true || listOfNotNull(s.componentClass, s.resolvedClass).any {
            activityEntry.containsMatchIn(it.substringAfterLast('.'))
        }
}
