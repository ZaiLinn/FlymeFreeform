package io.github.zailinn.flymefreeform.hook

/**
 * system_server 内关闭指定应用当前小窗的入口。
 *
 * 由 [OutsideTapCloseHookInstaller] 在拿到 FlexibleTaskController 后登记；启动钩子
 * （ActivityStarterZoomInjector）在目标已在小窗、又来新链接时用它先关旧小窗再重开。
 * 不能在 starter 钩子里同步调用，调用方须投递到别的线程执行。
 */
internal object ZoomTaskCloser {
    /** 参数为包名与 Request 用户；关掉了该包最上层的普通小窗时返回 true。 */
    @Volatile
    var closer: ((String, Int) -> Boolean)? = null
}
