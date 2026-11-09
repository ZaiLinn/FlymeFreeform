package io.github.zailinn.flymefreeform.apps

import android.icu.text.Transliterator
import java.util.concurrent.ConcurrentHashMap

/**
 * 设置页搜索：名称、包名直接包含查询（忽略大小写、去掉首尾空格），
 * 名称含汉字时另按全拼与首字母匹配（「微信」可用 weixin、wx 搜到）。
 *
 * 拼音由系统自带的 ICU 转写得到，按名称缓存，只在查询为拼音字母且名称含汉字时才计算；
 * 首次计算全部名称约需几百毫秒，调用方须在后台线程执行 [filter]。
 */
internal object AppSearch {
    private val keys = ConcurrentHashMap<String, PinyinKeys>()
    private val transliterator: Transliterator? by lazy {
        runCatching { Transliterator.getInstance("Han-Latin; Latin-ASCII; Any-Lower") }.getOrNull()
    }

    fun normalize(query: String): String = query.trim()

    fun filter(entries: List<SelectableEntry>, query: String): List<SelectableEntry> {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return entries
        val pinyinQuery = normalized.lowercase().takeIf { q -> q.all { it in 'a'..'z' } }
        return entries.filter { matches(it, normalized, pinyinQuery) }
    }

    private fun matches(entry: SelectableEntry, query: String, pinyinQuery: String?): Boolean {
        if (entry.label.contains(query, ignoreCase = true) || entry.packageName.contains(query, ignoreCase = true)) return true
        if (pinyinQuery == null || entry.label.none(::isHan)) return false
        val pinyin = keys.getOrPut(entry.label) { pinyinOf(entry.label) }
        return pinyin.full.contains(pinyinQuery) || pinyin.initials.contains(pinyinQuery)
    }

    private fun pinyinOf(label: String): PinyinKeys {
        val latin = transliterator?.let { synchronized(it) { it.transliterate(label) } } ?: return PinyinKeys("", "")
        // 转写结果按字以空格分开；非字母的符号当作分隔。
        val syllables = latin.split(' ', '-', '·', '.', '_').map { part -> part.filter { it in 'a'..'z' || it in '0'..'9' } }
            .filter(String::isNotEmpty)
        return PinyinKeys(full = syllables.joinToString(""), initials = syllables.joinToString("") { it.take(1) })
    }

    private fun isHan(char: Char): Boolean = Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN

    private class PinyinKeys(val full: String, val initials: String)
}
