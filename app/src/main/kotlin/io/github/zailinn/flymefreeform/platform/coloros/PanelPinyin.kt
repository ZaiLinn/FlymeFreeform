package io.github.zailinn.flymefreeform.platform.coloros

import java.util.concurrent.ConcurrentHashMap

/**
 * 设备上的拼音首字母，供 [PanelAlphabet] 使用。
 *
 * 汉字转拼音用系统 ICU 的 `Han-Latin; Latin-ASCII`（去声调，「安」→ an）；多音字按 ICU 的默认读音。
 * 结果按名称缓存，转换器首次创建较慢，[warm] 可在后台线程预先创建。
 */
internal object PanelPinyin {
    private val buckets = ConcurrentHashMap<String, Char>()

    private val transliterator by lazy {
        runCatching { android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII") }.getOrNull()
    }

    fun warm() {
        transliterator
    }

    /** 转拼音用的后台线程：首次打开时约 540 个名称不在主线程上逐个转换。 */
    val executor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "FlymeFreeform-Pinyin").apply { isDaemon = true }
        }

    /** [labels] 是否全部已有缓存；全部命中时调用方可直接在主线程取结果。 */
    fun allCached(labels: List<String>): Boolean = labels.all(buckets::containsKey)

    fun bucketOf(label: String): Char =
        buckets.getOrPut(label) { PanelAlphabet.bucketOf(label, ::hanInitial) }

    private fun hanInitial(han: Char): Char? =
        transliterator?.let { runCatching { it.transliterate(han.toString()) }.getOrNull() }
            ?.firstOrNull { it.isLetter() }
}
