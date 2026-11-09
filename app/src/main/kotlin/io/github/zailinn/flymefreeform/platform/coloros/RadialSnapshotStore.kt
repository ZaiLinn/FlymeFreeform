package io.github.zailinn.flymefreeform.platform.coloros

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import io.github.zailinn.flymefreeform.config.ModuleSettingsSnapshot
import io.github.zailinn.flymefreeform.config.PinnedTarget
import io.github.zailinn.flymefreeform.config.PinnedTargetCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 扇形条目的本地副本，只在 system_server 内使用。
 *
 * 开机后第一次读目录要等解锁（解锁前凭据加密区不可读，普通应用的图标取不到），读完、塑形完之前呼出的扇形是空的。
 * 这里把每次发布的扇形（名称、塑形后的图标、角标、分圈数）写到 `/data/system/flymefreeform`（设备加密区，解锁前可读），
 * 开机后先显示副本，真实目录发布后整体替换。副本只用于显示与启动目标，启动仍按目标正常校验。
 *
 * 只有影响扇形挑选与外观的设置（[Key]）都与当前一致时才采用。补位项可能已过时，真实目录到达后即被替换。
 * 写盘合并：发布后等 [SAVE_DELAY_MS] 再写最近一份，前台切换引起的连续变化只写一次；
 * 单项图标的 PNG 按位图对象复用，只编码新出现的图标；整体内容摘要相同则不写盘。
 */
internal class RadialSnapshotStore(
    private val log: (Int, String, Throwable?) -> Unit,
    private val directory: File = File(DIRECTORY),
) {
    private val worker = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "FlymeFreeform-RadialStore") }, ThreadPoolExecutor.DiscardOldestPolicy())
        .apply { allowCoreThreadTimeOut(true) }

    /** 以下字段仅存储线程访问。 */
    private var lastDigest: Pair<Int, String>? = null
    private var encoded = IdentityHashMap<Bitmap, ByteArray>()

    @Volatile private var pending: Pending? = null
    private val saveTask = Runnable { flush() }
    private val delayer = android.os.Handler(android.os.Looper.getMainLooper())

    /** 设置里决定扇形挑选与外观的部分；与当前设置不一致的副本不采用。 */
    data class Key(
        val pinsSaved: Boolean,
        val pinnedTargets: List<PinnedTarget>,
        val innerArcTargets: List<List<PinnedTarget>>,
        val radialMaxItems: Int,
        val arcCount: Int,
        val iconShape: Int,
        val fillSource: Int,
    ) {
        companion object {
            fun of(settings: ModuleSettingsSnapshot) = Key(
                settings.pinsSaved,
                settings.pinnedTargets,
                settings.innerArcTargets.take((settings.effectiveRadialArcCount - 1).coerceAtLeast(0)),
                settings.radialMaxItems,
                settings.effectiveRadialArcCount,
                settings.iconShape,
                settings.radialFillSource,
            )
        }
    }

    class Stored(val key: Key, val entries: List<RadialEntry>, val arcCounts: List<Int>)

    private class Pending(val userId: Int, val stored: Stored)

    /** 读取用户的副本；没有、校验失败时不回调。回调在存储线程。 */
    fun load(userId: Int, onLoaded: (Stored) -> Unit) = submit {
        val startedAt = SystemClock.uptimeMillis()
        val file = fileFor(userId)
        if (!file.exists()) return@submit
        val bytes = runCatching { AtomicFile(file).readFully() }
            .onFailure { log(Log.WARN, "RADIAL_SNAPSHOT_READ_FAILED", it) }.getOrNull() ?: return@submit
        if (bytes.size > MAX_FILE_BYTES) { log(Log.WARN, "RADIAL_SNAPSHOT_TOO_LARGE", null); return@submit }
        val stored = runCatching { decode(bytes, userId) }
            .onFailure { log(Log.WARN, "RADIAL_SNAPSHOT_INVALID", it) }.getOrNull() ?: return@submit
        lastDigest = userId to digest(bytes)
        log(Log.INFO, "RADIAL_SNAPSHOT_READY_" + stored.entries.size + "_" + (SystemClock.uptimeMillis() - startedAt) + "MS", null)
        onLoaded(stored)
    }

    /** 记下最新发布的扇形，稍后合并写盘；可在任意线程调用。 */
    fun save(userId: Int, stored: Stored) {
        if (stored.entries.isEmpty()) return
        pending = Pending(userId, stored)
        delayer.removeCallbacks(saveTask)
        delayer.postDelayed(saveTask, SAVE_DELAY_MS)
    }

    private fun flush() {
        val next = pending ?: return
        pending = null
        submit {
            val bytes = runCatching { encode(next.stored, next.userId) }
                .onFailure { log(Log.WARN, "RADIAL_SNAPSHOT_ENCODE_FAILED", it) }.getOrNull() ?: return@submit
            val hash = digest(bytes)
            if (lastDigest == next.userId to hash) return@submit
            val file = AtomicFile(fileFor(next.userId))
            var stream: java.io.FileOutputStream? = null
            try {
                if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("mkdirs " + directory)
                stream = file.startWrite()
                stream.write(bytes)
                file.finishWrite(stream)
                lastDigest = next.userId to hash
            } catch (exception: Exception) {
                stream?.let(file::failWrite)
                log(Log.WARN, "RADIAL_SNAPSHOT_WRITE_FAILED", exception)
            }
        }
    }

    private fun submit(action: () -> Unit) {
        try {
            worker.execute {
                try { action() } catch (exception: Exception) { log(Log.WARN, "RADIAL_SNAPSHOT_FAILED", exception) }
            }
        } catch (exception: java.util.concurrent.RejectedExecutionException) {
            log(Log.WARN, "RADIAL_SNAPSHOT_REJECTED", exception)
        }
    }

    private fun fileFor(userId: Int) = File(directory, "radial_snapshot_u$userId.bin")

    private fun encode(stored: Stored, userId: Int): ByteArray {
        // 只保留本次用到的图标编码：已编码过的位图对象直接复用。
        val previous = encoded
        val current = IdentityHashMap<Bitmap, ByteArray>()
        fun png(bitmap: Bitmap): ByteArray =
            current.getOrPut(bitmap) {
                previous[bitmap] ?: ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
            }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeInt(userId)
            val key = stored.key
            out.writeBoolean(key.pinsSaved)
            writeTargets(out, key.pinnedTargets)
            out.writeInt(key.innerArcTargets.size)
            key.innerArcTargets.forEach { writeTargets(out, it) }
            out.writeInt(key.radialMaxItems)
            out.writeInt(key.arcCount)
            out.writeInt(key.iconShape)
            out.writeInt(key.fillSource)
            out.writeInt(stored.arcCounts.size)
            stored.arcCounts.forEach(out::writeInt)
            out.writeInt(stored.entries.size)
            stored.entries.forEach { entry ->
                out.writeUTF(PinnedTargetCodec.encode(entry.target))
                out.writeUTF(entry.label.take(MAX_LABEL))
                writeBytes(out, png(entry.icon))
                out.writeBoolean(entry.badge != null)
                entry.badge?.let { writeBytes(out, png(it)) }
                out.writeFloat(entry.badgeLeftOffsetFraction)
            }
        }
        encoded = current
        return buffer.toByteArray()
    }

    private fun decode(bytes: ByteArray, userId: Int): Stored {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == FORMAT_VERSION && input.readInt() == userId)
            val pinsSaved = input.readBoolean()
            val pinned = readTargets(input)
            val arcs = input.readInt()
            require(arcs in 0..MAX_ARCS)
            val inner = List(arcs) { readTargets(input) }
            val key = Key(pinsSaved, pinned, inner, input.readInt(), input.readInt(), input.readInt(), input.readInt())
            val countsSize = input.readInt()
            require(countsSize in 0..MAX_ARCS + 1)
            val counts = List(countsSize) { input.readInt() }
            val count = input.readInt()
            require(count in 1..MAX_ENTRIES)
            val entries = List(count) {
                val target = requireNotNull(PinnedTargetCodec.decode(input.readUTF()))
                val label = input.readUTF()
                require(label.length <= MAX_LABEL)
                val icon = readBitmap(input)
                val badge = if (input.readBoolean()) readBitmap(input) else null
                val offset = input.readFloat()
                require(offset.isFinite())
                RadialEntry(target, label, icon, badge, offset)
            }
            // 分圈数与条目数对不上时交给扇形按总数均分（与目录在图标缺失时的处理一致）。
            return Stored(key, entries, counts.takeIf { it.sum() == entries.size }.orEmpty())
        }
    }

    private fun writeTargets(out: DataOutputStream, targets: List<PinnedTarget>) {
        out.writeInt(targets.size)
        targets.forEach { out.writeUTF(PinnedTargetCodec.encode(it)) }
    }

    private fun readTargets(input: DataInputStream): List<PinnedTarget> {
        val size = input.readInt()
        require(size in 0..MAX_ENTRIES)
        return List(size) { requireNotNull(PinnedTargetCodec.decode(input.readUTF())) }
    }

    private fun writeBytes(out: DataOutputStream, data: ByteArray) {
        out.writeInt(data.size)
        out.write(data)
    }

    private fun readBitmap(input: DataInputStream): Bitmap {
        val size = input.readInt()
        require(size in 1..MAX_BITMAP_BYTES)
        val data = ByteArray(size)
        input.readFully(data)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(data, 0, size))
        require(bitmap.width in 1..MAX_ICON_PX && bitmap.height in 1..MAX_ICON_PX)
        return bitmap
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val DIRECTORY = "/data/system/flymefreeform"
        const val MAGIC = 0x46465253 // "FFRS"
        const val FORMAT_VERSION = 1
        const val MAX_LABEL = 160
        const val MAX_ENTRIES = 64
        const val MAX_ARCS = 8
        const val MAX_ICON_PX = 512
        const val MAX_BITMAP_BYTES = 1024 * 1024
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        const val SAVE_DELAY_MS = 10_000L
    }
}
