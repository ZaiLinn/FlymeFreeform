package io.github.zailinn.flymefreeform.config

/** 未知 UID 不参与身份匹配，避免两次查询失败被视为同一发送者。 */
internal object TrustedSender {
    fun matches(actual: Int, expected: Int): Boolean = actual >= 0 && expected >= 0 && actual == expected
    fun matches(actual: Int, expected: () -> Int): Boolean {
        if (actual < 0) return false
        val uid = try { expected() } catch (_: Exception) { return false }
        return matches(actual, uid)
    }
}
