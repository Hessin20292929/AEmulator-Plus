/* Added for AEmulator Plus. GPL-3.0; see LICENSE. */
package app.aemu.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.aemu.importer.Analyzer
import app.aemu.importer.BootImage
import app.aemu.importer.Importer
import app.aemu.importer.OemPartitionSource
import app.aemu.importer.PartitionNames
import java.io.File
import java.nio.file.Files

/**
 * Replaces /system of an existing VM with another system image (system.img, system.ext4.img, a tar.md5 or zip
 * holding one). EXPERIMENTAL: boot, recovery, vendor, /data and the VM settings are kept, so the new system must
 * match the old one's Android version and device family; if the VM then boot-loops, reset its data.
 *
 * The new system is unpacked into a staging folder first, then swapped in with renames; the old one is kept as a
 * backup until the VM has been re-analysed, and put back if anything fails. dalvik-cache is cleared because it
 * belongs to the old system.
 */
internal object SystemPartitionImport {
    fun install(ctx: Context, id: String, uri: Uri) {
        VmStorageLease(ctx.filesDir).use { lease ->
            lease.acquire()
            GuestStorageWriters.requireIdle(ctx)
            val paths = VmPaths(ctx, id)
            val old = ImageStore.get(ctx, id) ?: error("VM no longer exists")
            for (dir in listOf(paths.dir, paths.root)) require(dir.isDirectory && !Files.isSymbolicLink(dir.toPath()))
            require(!Files.isSymbolicLink(paths.propsTemplate.toPath())) { "Unsafe property template" }
            val live = File(paths.root, "system")
            val backup = File(paths.dir, ".system-backup")
            // a crash during an earlier swap: the backup is the only copy of the old system if /system is gone
            if (backup.exists()) { if (!live.exists()) check(backup.renameTo(live)) else backup.deleteRecursively() }
            require(live.isDirectory && !Files.isSymbolicLink(live.toPath())) { "This VM has no system folder to replace" }
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "system.img"
            val scratch = File(ctx.cacheDir, "system-import-${System.nanoTime()}").apply { mkdirs() }
            val stage = File(paths.dir, ".system-stage").apply { deleteRecursively(); mkdirs() }
            // records written next to the system tree by the importer (block / delta OTA support); put back on failure
            val aux = listOf(SystemLayout.file(paths.dir), StockImage.file(paths.dir))
            val auxBackup = aux.map { File(it.path + ".bak") }
            val props = paths.propsTemplate.takeIf { it.isFile }?.readBytes()
            var swapped = false
            var auxMoved = false
            try {
                val image = (ctx.contentResolver.openInputStream(uri) ?: error("Cannot open the system image")).use {
                    OemPartitionSource.read(it, name, scratch, matches = PartitionNames::isSystem, label = "system")
                }
                auxMoved = true
                for ((f, b) in aux.zip(auxBackup)) { b.delete(); if (f.exists()) check(f.renameTo(b)) { "Cannot set ${f.name} aside" } }
                Importer(ctx, { _, _ -> }, { }).importSystemInto(image, stage, paths)
                val staged = File(stage, "system")
                require(File(staged, "build.prop").isFile && File(staged, "framework").isDirectory) { "The image holds no Android system" }
                check(live.renameTo(backup)) { "Cannot move the old system aside" }
                swapped = true
                check(staged.renameTo(live)) { "Cannot place the new system" }
                File(paths.root, "data/dalvik-cache").deleteRecursively()
                val ramdisk = File(paths.dir, "boot.img").takeIf { it.isFile }?.let { BootImage.ramdisk(it.readBytes()) }
                val analyzed = Analyzer(ctx, paths, ramdisk).analyze(id, old.name, persistBuildProp = false)
                val refreshed = analyzed.copy(name = old.name, settings = old.settings, baseId = old.baseId,
                    oneTimeNote = old.oneTimeNote, createdAt = old.createdAt, lastBootMs = old.lastBootMs,
                    bootCount = old.bootCount, sizeBytes = ImageStore.du(paths.dir))
                TreeFixer(ctx, paths, refreshed) { }.sanitize()
                ImageStore.save(ctx, refreshed.copy(sizeBytes = ImageStore.du(paths.dir)))
                backup.deleteRecursively()
                auxBackup.forEach { it.delete() }
            } catch (t: Throwable) {
                if (swapped) {
                    File(paths.root, "system").deleteRecursively()
                    backup.renameTo(live)
                }
                if (auxMoved) for ((f, b) in aux.zip(auxBackup)) { if (b.exists()) { f.delete(); b.renameTo(f) } else f.delete() }
                if (props == null) Files.deleteIfExists(paths.propsTemplate.toPath()) else paths.propsTemplate.writeBytes(props)
                throw t
            } finally {
                scratch.deleteRecursively()
                stage.deleteRecursively()
            }
        }
    }
}
