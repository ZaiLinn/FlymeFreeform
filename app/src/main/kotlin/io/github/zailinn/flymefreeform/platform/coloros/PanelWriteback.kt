package io.github.zailinn.flymefreeform.platform.coloros

/** 会话替换、关闭、搜索或原生列表模式切换后，旧异步回调不能覆盖当前内容。 */
internal class PanelWriteback {
    private class Session(val owner: Any, val allowed: () -> Boolean, val ready: () -> Unit)
    @Volatile private var active: Session? = null
    fun bind(owner: Any, allowed: () -> Boolean, ready: () -> Unit) { active = Session(owner, allowed, ready) }
    fun detach(owner: Any) { if (active?.owner === owner) active = null }
    fun accepts(owner: Any): Boolean = active?.let { it.owner === owner && it.allowed() } == true
    fun completion(): () -> Unit {
        val session = active
        return { if (session != null && active === session && session.allowed()) session.ready() }
    }
}
