package io.github.zailinn.flymefreeform.apps

/** 缓存失效与异步写回使用同一把短锁，防止检查后失效再写入的竞态。锁内只做内存操作。 */
internal class CacheRevision {
    private var revision = 0L
    @Synchronized fun current(): Long = revision
    @Synchronized fun isCurrent(expected: Long): Boolean = expected == revision
    @Synchronized fun commit(expected: Long, write: () -> Unit): Boolean {
        if (expected != revision) return false
        write()
        return true
    }
    @Synchronized fun invalidate(clear: () -> Unit) { revision++; clear() }
}
