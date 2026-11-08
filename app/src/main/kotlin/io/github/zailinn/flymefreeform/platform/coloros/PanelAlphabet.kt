package io.github.zailinn.flymefreeform.platform.coloros

/**
 * 「更多」面板原有列表的字母索引：按名称首字母分桶、求每个字母的跳转位置。原厂列表已按首字母排序，这里不重排。
 *
 * 中文名取首字的拼音首字母，拉丁字母（含全角）取本身，其余归入 [OTHER]，排在最后。
 * 汉字转拼音由调用方注入（设备上用 ICU），这里只做纯计算，便于脱离 Android 单测。
 */
internal object PanelAlphabet {
    const val OTHER = '#'

    /** 索引条上自上而下的全部字母。 */
    val LETTERS: List<Char> = ('A'..'Z').toList() + OTHER

    fun bucketOf(label: String?, hanInitial: (Char) -> Char?): Char {
        val first = label?.trimStart()?.firstOrNull() ?: return OTHER
        val letter =
            when {
                first in 'a'..'z' || first in 'A'..'Z' -> first
                first in 'Ａ'..'Ｚ' || first in 'ａ'..'ｚ' -> (first.code - 0xFEE0).toChar()
                Character.UnicodeScript.of(first.code) == Character.UnicodeScript.HAN -> hanInitial(first)
                else -> null
            }?.uppercaseChar()
        return if (letter != null && letter in 'A'..'Z') letter else OTHER
    }

    fun rank(bucket: Char): Int = if (bucket in 'A'..'Z') bucket - 'A' else 26

    /** 每个字母在列表中的首个下标（加上 [offset]）。 */
    fun firstIndices(buckets: List<Char>, offset: Int = 0): Map<Char, Int> {
        val result = LinkedHashMap<Char, Int>()
        buckets.forEachIndexed { index, bucket -> result.putIfAbsent(bucket, index + offset) }
        return result
    }

    /** 触到 [letter] 时跳转的位置：该字母首项；没有则取其后最近的字母，再没有取其前最近的字母。 */
    fun targetFor(letter: Char, firsts: Map<Char, Int>): Int? {
        if (firsts.isEmpty()) return null
        firsts[letter]?.let { return it }
        val rank = rank(letter)
        return LETTERS.filter { rank(it) > rank }.firstNotNullOfOrNull { firsts[it] }
            ?: LETTERS.filter { rank(it) < rank }.reversed().firstNotNullOfOrNull { firsts[it] }
    }
}
