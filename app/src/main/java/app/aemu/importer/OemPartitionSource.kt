/* Modified for AEmulator Plus, 2026-10-11: per-VM vendor / modem-firmware / system partition import. GPL-3.0; see LICENSE. */
package app.aemu.importer

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory

/**
 * Finds the OEM partition image in whatever the person picked in the settings: the image itself (ext2/3/4, sparse,
 * YAFFS2, Moto-signed), or a zip / tar / tar.md5 / gz / xz / bz2 / lz4 holding `oem.img`, `oem_signed(.img)` or
 * `oem.ext4.img`. Content-first like [BootPartitionSource]. The image is copied into [scratch] and returned; the
 * caller deletes it.
 *
 * Modified for AEmulator Plus: [matches] / [label] let the same reader find the system, vendor and modem/firmware
 * images too; the defaults keep the OEM behaviour.
 */
internal object OemPartitionSource {
    private const val MAX_IMAGE = 4L shl 30

    fun read(
        input: InputStream, name: String, scratch: File, depth: Int = 0,
        matches: (String) -> Boolean = PartitionNames::isOem, label: String = "oem",
    ): File {
        fun hit(base: String) = matches(PartitionNames.stripOdinMd5(base).removeSuffix(".lz4"))
        require(depth <= 4) { "Too many nested archives" }
        val src = input.buffered(65536)
        src.mark(512)
        val head = ByteArray(512)
        var count = 0
        while (count < head.size) { val n = src.read(head, count, head.size - count); if (n < 0) break; count += n }
        src.reset()
        fun magic(offset: Int, value: String) = count >= offset + value.length &&
            String(head, offset, value.length, Charsets.ISO_8859_1) == value
        val lower = name.lowercase()
        if (magic(0, "PK\u0003\u0004")) {
            ZipArchiveInputStream(src).use { zip ->
                while (true) {
                    val e = zip.nextZipEntry ?: break
                    val base = e.name.substringAfterLast('/')
                    if (!e.isDirectory && hit(base)) return read(zip, base, scratch, depth + 1, matches, label)
                }
            }
            error("No $label partition in ZIP archive")
        }
        val compressed = runCatching { CompressorStreamFactory.detect(src) }.getOrNull()
        if (compressed != null) {
            CompressorStreamFactory(true, 65536).createCompressorInputStream(compressed, src).use {
                return read(it, lower.substringBeforeLast('.', lower), scratch, depth + 1, matches, label)
            }
        }
        if (magic(257, "ustar") || lower.endsWith(".tar") || lower.endsWith(".tar.md5") || lower.endsWith(".md5")) {
            TarArchiveInputStream(src).use { tar ->
                while (true) {
                    val e = tar.nextTarEntry ?: break
                    val base = e.name.substringAfterLast('/')
                    if (e.isFile && hit(base)) return read(tar, base, scratch, depth + 1, matches, label)
                }
            }
            error("No $label partition in TAR archive")
        }
        return spill(src, scratch, label)
    }

    private fun spill(input: InputStream, scratch: File, label: String): File {
        val f = Files.createTempFile(scratch.toPath(), "part-", ".img").toFile()
        try {
            f.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    total += n
                    require(total <= MAX_IMAGE) { "The $label image exceeds 4 GiB" }
                    out.write(buf, 0, n)
                }
            }
            require(f.length() >= 2048) { "The $label image is too small" }
        } catch (t: Throwable) { f.delete(); throw t }
        return f
    }
}
