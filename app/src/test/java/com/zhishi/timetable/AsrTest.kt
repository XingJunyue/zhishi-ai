package com.zhishi.timetable

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** 离线语音模型的下载 / 校验 / 解包状态机（假的 HTTP，不连网）。 */
class AsrTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun tmp(): File = kotlin.io.path.createTempDirectory("asr").toFile()

    /** 造一个和真模型包结构一样的 tar.bz2：顶层目录 + 两个要用的文件 + 不要的测试音频 + 一个想写到目录外面的坏条目。 */
    private fun pack(): ByteArray {
        val bo = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bo)).use { t ->
            fun put(name: String, data: ByteArray) { val e = TarArchiveEntry(name); e.size = data.size.toLong(); t.putArchiveEntry(e); t.write(data); t.closeArchiveEntry() }
            t.putArchiveEntry(TarArchiveEntry("./m/")); t.closeArchiveEntry()
            put("./m/model.int8.onnx", ByteArray(3000) { (it % 251).toByte() })
            put("./m/tokens.txt", "<blk> 0\n你 1\n".toByteArray())
            put("./m/test_wavs/0.wav", ByteArray(100))
            put("../../evil/tokens.txt.bak", ByteArray(10))
        }
        return bo.toByteArray()
    }

    private val good = pack()

    private class Fake(val map: Map<String, () -> AsrBody>) : AsrFetch {
        val asked = ArrayList<String>()
        override fun open(url: String): AsrBody { asked += url; return (map[url] ?: throw IOException("unreachable $url"))() }
    }

    private fun body(b: ByteArray, len: Long = b.size.toLong()) = { AsrBody(len, ByteArrayInputStream(b)) }

    private fun inst(root: File, f: AsrFetch, data: ByteArray = good, free: Long = Long.MAX_VALUE) =
        AsrInstaller(root, f, sha256 = sha(data), size = data.size.toLong(), unpacked = 4000, freeSpace = { free })

    private val urls = listOf("https://primary/x.tar.bz2", "https://mirror/x.tar.bz2", "https://proxy/https://mirror/x.tar.bz2")

    @Test fun primaryOk_unpacksOnlyNeededFiles() {
        val root = tmp(); val f = Fake(mapOf(urls[0] to body(good)))
        val states = ArrayList<AsrDl>()
        val r = inst(root, f).run(urls, { states += it })
        assertEquals(AsrDl.Phase.READY, r.phase)
        assertEquals(listOf(urls[0]), f.asked)
        val i = inst(root, f)
        assertTrue(i.installed())
        assertEquals(setOf("model.int8.onnx", "tokens.txt", "ok"), i.modelDir.list()!!.toSet())
        assertEquals(3000L, File(i.modelDir, "model.int8.onnx").length())
        // 坏条目没被写到目录外面，半截文件都清掉了
        assertFalse(File(root.parentFile, "evil").exists())
        assertFalse(File(root, "download.part").exists()); assertFalse(File(root, "model.tmp").exists())
        // 阶段顺序：下载（进度单调）→ 校验 → 解包 → 完成
        val phases = states.map { it.phase }.distinct()
        assertEquals(listOf(AsrDl.Phase.DOWNLOADING, AsrDl.Phase.VERIFYING, AsrDl.Phase.UNPACKING, AsrDl.Phase.READY), phases)
        val done = states.filter { it.phase == AsrDl.Phase.DOWNLOADING }.map { it.done }
        assertEquals(done, done.sorted()); assertEquals(good.size.toLong(), done.last())
    }

    @Test fun primaryUnreachable_fallsBackToMirror() {
        val root = tmp(); val f = Fake(mapOf(urls[1] to body(good)))
        val states = ArrayList<AsrDl>()
        val r = inst(root, f).run(urls, { states += it })
        assertEquals(AsrDl.Phase.READY, r.phase); assertEquals(1, r.source)
        assertEquals(urls.take(2), f.asked)
        assertTrue(states.any { it.phase == AsrDl.Phase.DOWNLOADING && it.source == 1 })
    }

    @Test fun badChecksumOnPrimary_triesMirror() {
        val bad = good.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val root = tmp(); val f = Fake(mapOf(urls[0] to body(bad), urls[1] to body(good)))
        val r = inst(root, f).run(urls)
        assertEquals(AsrDl.Phase.READY, r.phase); assertEquals(1, r.source)
    }

    @Test fun wrongLength_skipsSource() {
        // 加速镜像给了一张网页（长度不对）：不下载，换下一个
        val root = tmp(); val f = Fake(mapOf(urls[0] to body("<html>".toByteArray()), urls[1] to body(good)))
        assertEquals(AsrDl.Phase.READY, inst(root, f).run(urls).phase)
        // 没给长度、内容又比应有的长：也当坏的
        val root2 = tmp(); val f2 = Fake(mapOf(urls[0] to body(good + ByteArray(10), -1), urls[1] to body(good, -1)))
        val r2 = inst(root2, f2).run(urls)
        assertEquals(AsrDl.Phase.READY, r2.phase); assertEquals(1, r2.source)
    }

    @Test fun truncatedDownload_triesNext() {
        val root = tmp()
        val f = Fake(mapOf(urls[0] to body(good.copyOf(good.size / 2), -1), urls[2] to body(good)))
        val r = inst(root, f).run(urls)
        assertEquals(AsrDl.Phase.READY, r.phase); assertEquals(2, r.source)
    }

    @Test fun allUnreachable_networkError() {
        val root = tmp(); val f = Fake(emptyMap())
        val r = inst(root, f).run(urls)
        assertEquals(AsrDl.Phase.FAILED, r.phase); assertEquals(AsrDl.Err.NETWORK, r.error)
        assertEquals(urls, f.asked)
        assertFalse(inst(root, f).installed())
        assertFalse(File(root, "download.part").exists())
    }

    @Test fun allBadChecksum_checksumError() {
        val bad = good.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val root = tmp(); val f = Fake(urls.associateWith { body(bad) })
        val r = inst(root, f).run(urls)
        assertEquals(AsrDl.Err.CHECKSUM, r.error)
        assertFalse(File(root, "download.part").exists())
    }

    @Test fun corruptArchive_unpackError_noHalfModel() {
        val junk = ByteArray(5000) { 7 }
        val root = tmp(); val f = Fake(mapOf(urls[0] to body(junk)))
        val r = inst(root, f, data = junk).run(urls)
        assertEquals(AsrDl.Err.UNPACK, r.error)
        assertFalse(File(root, "model").exists()); assertFalse(File(root, "model.tmp").exists())
    }

    @Test fun noSpace() {
        val root = tmp(); val f = Fake(mapOf(urls[0] to body(good)))
        val r = inst(root, f, free = 1000).run(urls)
        assertEquals(AsrDl.Err.SPACE, r.error); assertTrue(f.asked.isEmpty())
    }

    @Test fun cancelMidway_cleansUp() {
        val root = tmp(); val f = Fake(mapOf(urls[0] to body(good)))
        var n = 0
        val r = inst(root, f).run(urls, {}, cancelled = { ++n > 2 })
        assertEquals(AsrDl.Err.CANCELLED, r.error)
        assertFalse(File(root, "download.part").exists()); assertFalse(inst(root, f).installed())
    }

    @Test fun alreadyInstalled_noDownload_thenDelete() {
        val root = tmp()
        assertEquals(AsrDl.Phase.READY, inst(root, Fake(mapOf(urls[0] to body(good)))).run(urls).phase)
        val f = Fake(emptyMap())
        val i = inst(root, f)
        assertEquals(AsrDl.Phase.READY, i.run(urls).phase); assertTrue(f.asked.isEmpty())
        assertTrue(i.bytes() > 3000)
        i.delete()
        assertFalse(i.installed()); assertEquals(0L, i.bytes())
    }

    @Test fun markerWithOtherSha_notInstalled() {
        val root = tmp(); inst(root, Fake(mapOf(urls[0] to body(good)))).run(urls)
        File(root, "model/ok").writeText("deadbeef")
        assertFalse(inst(root, Fake(emptyMap())).installed())
    }

    @Test fun specUrls_primaryThenMirror() {
        val u = AsrSpec.urls()
        assertTrue(u[0].startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/"))
        assertEquals(AsrSpec.MIRROR, u[1])
        assertTrue(u[1].startsWith("https://github.com/XingJunyue/zhishi-ai/releases/download/asr-model/"))
        assertTrue(u.drop(2).all { it.endsWith(AsrSpec.MIRROR) })
        assertTrue(u.all { it.endsWith(AsrSpec.FILE) })
        assertFalse(u.any { it.contains("XingJunyue/zhishi/") })
        assertEquals(64, AsrSpec.SHA256.length)
        assertEquals("21 MB", AsrSpec.mb(AsrSpec.SIZE))
    }
}
