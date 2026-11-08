package io.github.zailinn.flymefreeform.config

import android.content.SharedPreferences
import android.os.Bundle

/**
 * 把完整设置快照装进设置广播，让各 Hook 进程不依赖 LSPosed 远程配置回调也能热生效。
 *
 * 模块重装后，Hook 进程里的远程配置变更回调不再触发，此前只有固定项走广播，
 * 图标形状、圆弧数、角落开关等其余设置要重启 system_server 才生效。
 *
 * 编码直接复用 [ModuleSettingsSnapshot.writeTo] / [ModuleSettingsSnapshot.readFrom]，
 * 与远程配置用同一套键和默认值，新增设置项无需在这里另行登记。
 */
internal object SettingsTransfer {
    /** 广播里承载完整快照的 Bundle；旧版接收端不认识它，仍读原来的固定项字段。 */
    const val EXTRA_SETTINGS = "settings_v1"

    fun encode(settings: ModuleSettingsSnapshot): Map<String, Any> {
        val preferences = MapPreferences()
        settings.writeTo(preferences.edit()).commit()
        return preferences.all
    }

    fun decode(values: Map<String, Any>): ModuleSettingsSnapshot =
        ModuleSettingsSnapshot.readFrom(MapPreferences(values))

    fun toBundle(settings: ModuleSettingsSnapshot): Bundle =
        Bundle().apply {
            encode(settings).forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringArrayList(key, ArrayList(value.map { it.toString() }))
                }
            }
        }

    /** 解出广播里的快照；缺少该字段或类型不符时返回 null，由调用方回退到旧字段。 */
    fun fromBundle(bundle: Bundle?): ModuleSettingsSnapshot? {
        if (bundle == null || bundle.isEmpty) return null
        val values = LinkedHashMap<String, Any>()
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION")
            val value = bundle.get(key) ?: continue
            values[key] = if (value is ArrayList<*>) value.map { it.toString() }.toSet() else value
        }
        return try {
            decode(values)
        } catch (_: ClassCastException) {
            null
        }
    }

    /** 仅供编解码使用的内存配置，不支持监听。 */
    private class MapPreferences(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
        private val values = LinkedHashMap(initial)

        override fun getAll(): Map<String, Any> = LinkedHashMap(values)

        override fun getString(key: String, defValue: String?): String? =
            values[key]?.let { it as String } ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            values[key]?.let { it as Set<String> } ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key]?.let { it as Int } ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key]?.let { it as Long } ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key]?.let { it as Float } ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key]?.let { it as Boolean } ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private fun set(key: String, value: Any?): SharedPreferences.Editor = apply {
                if (value == null) values.remove(key) else values[key] = value
            }

            override fun putString(key: String, value: String?) = set(key, value)

            override fun putStringSet(key: String, values: Set<String>?) = set(key, values?.toSet())

            override fun putInt(key: String, value: Int) = set(key, value)

            override fun putLong(key: String, value: Long) = set(key, value)

            override fun putFloat(key: String, value: Float) = set(key, value)

            override fun putBoolean(key: String, value: Boolean) = set(key, value)

            override fun remove(key: String): SharedPreferences.Editor = apply { values.remove(key) }

            override fun clear(): SharedPreferences.Editor = apply { values.clear() }

            override fun commit(): Boolean = true

            override fun apply() = Unit
        }
    }
}
