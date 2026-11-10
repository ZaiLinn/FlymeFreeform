package io.github.zailinn.flymefreeform.hook.launch

import android.content.Intent
import io.github.zailinn.flymefreeform.hook.launch.decision.LaunchSnapshot
import io.github.zailinn.flymefreeform.hook.launch.decision.ProtectedLaunch
import io.github.zailinn.flymefreeform.hook.launch.decision.CredentialLaunch

/** 仅读取 Intent，自身不查询系统服务；供 SystemUI 与 ActivityStarter 共用。 */
internal object LaunchIntentSnapshot {
    fun read(intent: Intent, resolvedClass: String? = null): LaunchSnapshot = read(intent, resolvedClass, 0)

    private fun read(intent: Intent, resolvedClass: String?, depth: Int): LaunchSnapshot = LaunchSnapshot(
        action = intent.action, dataString = intent.dataString, flags = intent.flags,
        componentPackage = intent.component?.packageName, componentClass = intent.component?.className,
        explicitPackage = intent.getPackage(), resolvedClass = resolvedClass,
        credentialExtra = runCatching {
            CredentialLaunch.EXTRA_KEYS.any { intent.hasExtra(it) }
        }.getOrDefault(true),
        authorizationExtra = runCatching {
            intent.hasExtra("_wxapi_sendauth_req_scope") ||
                intent.hasExtra("_wxapi_sendauth_resp_token") ||
                intent.hasExtra("_wxapi_sendauth_resp_code") ||
                intent.getStringExtra("key_action") == "action_login"
        }.getOrDefault(false),
        operationExtra = runCatching {
            intent.hasExtra("output") || intent.hasExtra("crop") ||
                intent.getIntExtra("_wxapi_command_type", 0) == 5 ||
                (depth < 3 && intent.action == Intent.ACTION_CHOOSER &&
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.let {
                        ProtectedLaunch.reason(read(it, null, depth + 1)) != null
                    } == true)
        }.getOrDefault(true),
    )
}
