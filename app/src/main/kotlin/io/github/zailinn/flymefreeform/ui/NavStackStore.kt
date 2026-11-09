package io.github.zailinn.flymefreeform.ui

import android.content.Context

/**
 * 设置页的页面栈，存在应用自己的 SharedPreferences 里。
 *
 * 页面栈本来随 Activity 的保存状态（rememberSaveable）恢复，但后台冻结 / 清理工具常以强行停止或移除任务的方式结束进程，
 * 保存状态随之丢失，再打开就回到首页。这里在每次进出页面时记下整条栈，冷启动没有保存状态时据此恢复；
 * 有保存状态时仍以保存状态为准。在首页按返回退出时栈只剩首页，下次照常从首页开始。
 */
internal object NavStackStore {
    private const val FILE = "ui_state"
    private const val KEY = "back_stack"

    fun load(context: Context): List<AppRoute> =
        decodeStack(runCatching { prefs(context).getString(KEY, null) }.getOrNull())

    fun save(context: Context, routes: List<AppRoute>) {
        val value = encodeStack(routes)
        runCatching { prefs(context).edit().putString(KEY, value).apply() }
    }

    fun encodeStack(routes: List<AppRoute>): String = routes.joinToString(",", transform = ::encode)

    /** 栈底必须是首页：没有记录、记录损坏或来自旧版本时退回首页；认不出的页面跳过。 */
    fun decodeStack(raw: String?): List<AppRoute> {
        val routes = raw?.split(',')?.mapNotNull(::decode).orEmpty()
        return if (routes.firstOrNull() == AppRoute.Settings) routes else listOf(AppRoute.Settings)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun encode(route: AppRoute): String =
        when (route) {
            AppRoute.Settings -> "settings"
            AppRoute.PinnedApps -> "pinned"
            AppRoute.Blacklist -> "blacklist"
            AppRoute.BlacklistPicker -> "blacklist_picker"
            AppRoute.ZoomOptions, AppRoute.EnvironmentScope -> "zoom"
            AppRoute.RadialSettings -> "radial"
        }

    private fun decode(value: String): AppRoute? =
        when (value) {
            "settings" -> AppRoute.Settings
            "pinned" -> AppRoute.PinnedApps
            "blacklist" -> AppRoute.Blacklist
            "blacklist_picker" -> AppRoute.BlacklistPicker
            "zoom" -> AppRoute.ZoomOptions
            "radial" -> AppRoute.RadialSettings
            else -> null
        }
}
