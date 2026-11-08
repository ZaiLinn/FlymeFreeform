package io.github.zailinn.flymefreeform.hook.launch.decision

/** 同目标最新链接覆盖旧链接；票据使用对象身份，避免 A→B→A 使旧 A 任务重新生效。 */
internal class PendingLaunches {
    class Ticket(val fingerprint: String)
    private val pending = HashMap<String, Ticket>()
    @Synchronized fun acquire(key: String, fingerprint: String): Ticket? {
        if (pending[key]?.fingerprint == fingerprint) return null
        return Ticket(fingerprint).also { pending[key] = it }
    }
    @Synchronized fun current(key: String, ticket: Ticket): Boolean = pending[key] === ticket
    @Synchronized fun finish(key: String, ticket: Ticket) { if (pending[key] === ticket) pending.remove(key) }
    @Synchronized fun clear() { pending.clear() }
}
