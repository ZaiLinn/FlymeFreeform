package io.github.zailinn.flymefreeform.hook.launch.decision

/** 同包、同链接的分身 / 工作资料启动不共享重复和返回记忆。仅保留最近 32 个用户。 */
internal class LaunchMemories(private val primary: LaunchMemory = LaunchMemory()) {
    private val users = object : LinkedHashMap<Int, LaunchMemory>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, LaunchMemory>?): Boolean = size > 32
    }
    @Synchronized fun forUser(userId: Int): LaunchMemory =
        if (userId == 0) primary else users.getOrPut(userId) { LaunchMemory() }
}
