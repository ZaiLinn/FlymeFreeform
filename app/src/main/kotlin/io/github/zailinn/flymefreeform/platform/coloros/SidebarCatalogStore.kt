package io.github.zailinn.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import io.github.zailinn.flymefreeform.apps.SelectableEntry
import io.github.zailinn.flymefreeform.config.PinnedTarget
import io.github.zailinn.flymefreeform.config.PinnedTargetCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 侧边栏目录（工具、分身应用、快捷方式）的本地副本，只在 system_server 内使用。
 *
 * 目录要从侧边栏进程读取，重启后需等侧边栏冷启动、面板初始化，固定项里的这些条目会晚几秒出现。
 * 这里在每次读到完整目录后写一份到 `/data/system/flymefreeform`（按用户分文件，设备加密区，
 * 解锁前可读），重启后先显示上次的目录，后台照常重新读取，读到后整体替换。
 * 内容与上次写入或读出的相同则不写盘：先逐项比对（名称、包名与图标像素），相同就连 PNG 编码也省掉，
 * 不同才编码并比对摘要。启动仍经侧边栏执行，副本只用于显示。
 *
 * 读写都在专用线程；回调也在该线程，调用方自行投递。
 */
internal class SidebarCatalogStore(
    private val log: (Int, String, Throwable?) -> Unit,
    private val directory: File = File(DIRECTORY),
) {
    private val worker = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "FlymeFreeform-CatalogStore") }, ThreadPoolExecutor.DiscardOldestPolicy())
        .apply { allowCoreThreadTimeOut(true) }

    /** 每个用户最近一次写入或读出的内容摘要。 */
    private val digests = ConcurrentHashMap<Int, String>()

    /**
     * 每个用户最近一次写入或读出的条目，用于跳过编码。保存时换成新传入的列表（内容相同），
     * 它与扇形正在显示的是同一批位图，不额外占内存。
     */
    private val lastEntries = ConcurrentHashMap<Int, List<SelectableEntry>>()

    /** 读取当前前台用户的副本；没有副本、校验失败时不回调。 */
    fun load(onLoaded: (List<SelectableEntry>) -> Unit) = submit {
        val startedAt = SystemClock.uptimeMillis()
        val userId = currentUser() ?: return@submit
        val file = fileFor(userId)
        if (!file.exists()) return@submit
        val bytes = runCatching { AtomicFile(file).readFully() }
            .onFailure { log(Log.WARN, "SIDEBAR_CATALOG_CACHE_READ_FAILED", it) }.getOrNull() ?: return@submit
        if (bytes.size > MAX_FILE_BYTES) { log(Log.WARN, "SIDEBAR_CATALOG_CACHE_TOO_LARGE", null); return@submit }
        val entries = runCatching { decode(bytes, userId) }
            .onFailure { log(Log.WARN, "SIDEBAR_CATALOG_CACHE_INVALID", it) }.getOrNull() ?: return@submit
        digests[userId] = digest(bytes)
        lastEntries[userId] = entries
        log(Log.INFO, "SIDEBAR_CATALOG_CACHE_READY_" + entries.size + "_" +
            (SystemClock.uptimeMillis() - startedAt) + "MS", null)
        onLoaded(entries)
    }

    /** 保存一次完整目录；条目须属于同一用户，空目录也保存（清掉已移除的条目）。 */
    fun save(entries: List<SelectableEntry>) {
        val snapshot = entries.toList()
        submit {
            val userId = snapshot.firstNotNullOfOrNull { (it.target as? PinnedTarget.Sidebar)?.userId }
                ?: currentUser() ?: return@submit
            if (snapshot.any { (it.target as? PinnedTarget.Sidebar)?.userId != userId }) return@submit
            if (sameContent(lastEntries[userId], snapshot)) {
                lastEntries[userId] = snapshot
                return@submit
            }
            val bytes = runCatching { encode(snapshot, userId) }
                .onFailure { log(Log.WARN, "SIDEBAR_CATALOG_CACHE_ENCODE_FAILED", it) }.getOrNull() ?: return@submit
            val hash = digest(bytes)
            if (digests[userId] == hash) {
                lastEntries[userId] = snapshot
                return@submit
            }
            val file = AtomicFile(fileFor(userId))
            var stream: java.io.FileOutputStream? = null
            try {
                if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("mkdirs " + directory)
                stream = file.startWrite()
                stream.write(bytes)
                file.finishWrite(stream)
                digests[userId] = hash
                lastEntries[userId] = snapshot
            } catch (exception: Exception) {
                stream?.let(file::failWrite)
                log(Log.WARN, "SIDEBAR_CATALOG_CACHE_WRITE_FAILED", exception)
            }
        }
    }

    private fun submit(action: () -> Unit) {
        try {
            worker.execute {
                try { action() } catch (exception: Exception) { log(Log.WARN, "SIDEBAR_CATALOG_CACHE_FAILED", exception) }
            }
        } catch (exception: java.util.concurrent.RejectedExecutionException) {
            log(Log.WARN, "SIDEBAR_CATALOG_CACHE_REJECTED", exception)
        }
    }

    /** 与编码结果等价的逐项比对；名称与包名按写盘时的截断长度比较。 */
    private fun sameContent(previous: List<SelectableEntry>?, current: List<SelectableEntry>): Boolean {
        if (previous == null || previous.size != current.size) return false
        return previous.indices.all { index ->
            val old = previous[index]
            val new = current[index]
            old.target == new.target &&
                old.label.take(MAX_LABEL) == new.label.take(MAX_LABEL) &&
                old.packageName.take(MAX_PACKAGE) == new.packageName.take(MAX_PACKAGE) &&
                sameBitmap(old.icon, new.icon) &&
                (old.badge == null) == (new.badge == null) &&
                (old.badge == null || sameBitmap(old.badge, new.badge!!))
        }
    }

    private fun sameBitmap(first: Bitmap, second: Bitmap): Boolean =
        first === second || (!first.isRecycled && !second.isRecycled && first.sameAs(second))

    private fun fileFor(userId: Int) = File(directory, "sidebar_catalog_u$userId.bin")

    private fun encode(entries: List<SelectableEntry>, userId: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeInt(userId)
            out.writeInt(entries.size)
            entries.forEach { entry ->
                out.writeUTF(PinnedTargetCodec.encode(entry.target))
                out.writeUTF(entry.label.take(MAX_LABEL))
                out.writeUTF(entry.packageName.take(MAX_PACKAGE))
                writeBitmap(out, entry.icon)
                out.writeBoolean(entry.badge != null)
                entry.badge?.let { writeBitmap(out, it) }
            }
        }
        return buffer.toByteArray()
    }

    private fun decode(bytes: ByteArray, userId: Int): List<SelectableEntry> {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == FORMAT_VERSION && input.readInt() == userId)
            val count = input.readInt()
            require(count in 0..SidebarEntryProtocol.MAX_ENTRIES)
            return List(count) {
                val target = PinnedTargetCodec.decode(input.readUTF()) as? PinnedTarget.Sidebar
                requireNotNull(target)
                require(target.userId == userId)
                val label = input.readUTF()
                require(label.isNotBlank() && label.length <= MAX_LABEL)
                val pkg = input.readUTF()
                require(pkg.length <= MAX_PACKAGE)
                val icon = readBitmap(input)
                val badge = if (input.readBoolean()) {
                    readBitmap(input).also { require(it.width == icon.width && it.height == icon.height) }
                } else null
                SelectableEntry(target, label, pkg, icon, badge)
            }
        }
    }

    private fun writeBitmap(out: DataOutputStream, bitmap: Bitmap) {
        val png = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png))
        out.writeInt(png.size())
        png.writeTo(out)
    }

    private fun readBitmap(input: DataInputStream): Bitmap {
        val size = input.readInt()
        require(size in 1..MAX_BITMAP_BYTES)
        val data = ByteArray(size)
        input.readFully(data)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(data, 0, size))
        val limit = SidebarEntryProtocol.ICON_SIZE
        require(bitmap.width in 1..limit && bitmap.height in 1..limit)
        return bitmap
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun currentUser(): Int? = runCatching {
        ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
    }.getOrNull()

    private companion object {
        const val DIRECTORY = "/data/system/flymefreeform"
        const val MAGIC = 0x46465343 // "FFSC"
        const val FORMAT_VERSION = 1
        const val MAX_LABEL = 160
        const val MAX_PACKAGE = 256
        const val MAX_BITMAP_BYTES = 256 * 1024
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
    }
}
