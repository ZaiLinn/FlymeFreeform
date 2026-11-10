package io.github.zailinn.flymefreeform.hook.launch.decision

import java.net.URI
import java.net.URLDecoder

/** 只检查授权入口特征，不记录 URL、授权码或令牌，也不按登录提供方整包排除。 */
internal object AuthorizationLaunch {
    private val entry = Regex(
        "login|oauth|sign_?in(?:activity|[._/]|$)|(?:^|[._/])sso|sso(?:activity|login|auth)|" +
            "authorize|authorization|authenticate|authentication|authenticator|" +
            "sendauth|authactivity|authui|authagent|authcallback|(?:^|[._/])auth(?:$|[._/])",
        RegexOption.IGNORE_CASE,
    )

    fun matches(s: LaunchSnapshot): Boolean =
        s.authorizationExtra ||
            listOfNotNull(s.action, s.componentClass, s.resolvedClass).any { entry.containsMatchIn(it) } ||
            isAuthorizationUri(s.dataString)

    private fun isAuthorizationUri(data: String?): Boolean {
        if (data == null) return false
        return runCatching {
            val uri = URI(data)
            if (listOfNotNull(uri.host, uri.path, uri.scheme).any { entry.containsMatchIn(it) }) return true
            // OAuth 请求与回调：匹配参数名，不读取或输出参数值。
            val keys = (uri.rawQuery.orEmpty() + "&" + uri.rawFragment.orEmpty()).split('&')
                .map { URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase(java.util.Locale.ROOT) }
                .toSet()
            ("redirect_uri" in keys && ("client_id" in keys || "response_type" in keys)) ||
                ("state" in keys && ("code" in keys || "error" in keys || "access_token" in keys)) ||
                "oauth_token" in keys || "openid.mode" in keys
        }.getOrDefault(false)
    }
}
