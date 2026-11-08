package io.github.zailinn.flymefreeform.platform.coloros

import java.util.concurrent.Executor

/** 取消前排队、执行失败与队列拒绝均有完成通知，避免准备状态永远占用。 */
internal class CancellableWork(private val executor: Executor) {
    fun submit(active: () -> Boolean, action: () -> Unit,
        failure: (Exception) -> Unit, complete: () -> Unit) {
        try {
            executor.execute {
                try { if (active()) action() }
                catch (error: Exception) { failure(error) }
                finally { complete() }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            try { failure(error) } finally { complete() }
        }
    }
}
