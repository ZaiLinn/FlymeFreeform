package io.github.zailinn.flymefreeform.platform.coloros

/** 仅复用当前用户的对象；切换用户重新创建，不能把主用户上下文带进其他用户的查询。 */
internal class UserScopedCache<T>(private val create: (Int) -> T) {
    private var cached: Pair<Int, T>? = null

    fun get(userId: Int): T {
        cached?.takeIf { it.first == userId }?.let { return it.second }
        return create(userId).also { cached = userId to it }
    }
}
