/* Added for AEmulator Plus. GPL-3.0; see LICENSE. */
package app.aemu.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.aemu.importer.Importer
import app.aemu.importer.OemPartitionSource
import app.aemu.importer.PartitionNames
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/**
 * Adds an optional partition (vendor, modem/firmware) to an existing VM from the settings, the same way
 * [OemPartitionImport] does for /oem: the image is unpacked into a staging folder first and moved to <root>/<mount>
 * in one rename, so a bad image never leaves a half-filled folder behind. Nothing else in the VM is touched.
 *
 * Only filesystem images the importer can read work (ext2/3/4, sparse, YAFFS2, UBIFS, SquashFS, Moto-signed). Raw
 * modem blobs and FAT NON-HLOS images are rejected with a message. The files only become visible inside the guest;
 * the emulator has no baseband, so nothing here makes a modem run.
 */
internal object ExtraPartitionImport {
    enum class Kind(val mount: String, val label: String, val matches: (String) -> Boolean) {
        VENDOR("vendor", "vendor", PartitionNames::isVendor),
        FIRMWARE("firmware", "modem/firmware", PartitionNames::isModem),
    }

    /** The folder holds files, or (for /vendor) is a link to system/vendor that the importer already made. */
    fun present(dir: File, kind: Kind): Boolean {
        val target = File(dir, "root/${kind.mount}")
        if (Files.isSymbolicLink(target.toPath())) return true
        return target.list()?.isNotEmpty() == true
    }

    fun install(ctx: Context, id: String, uri: Uri, kind: Kind) {
        VmStorageLease(ctx.filesDir).use { lease ->
            lease.acquire()
            GuestStorageWriters.requireIdle(ctx)
            val paths = VmPaths(ctx, id)
            val old = ImageStore.get(ctx, id) ?: error("VM no longer exists")
            require(!present(paths.dir, kind)) { "VM already has a ${kind.label} partition" }
            for (dir in listOf(paths.dir, paths.root)) require(dir.isDirectory && !Files.isSymbolicLink(dir.toPath()))
            val target = File(paths.root, kind.mount)
            require(!Files.isSymbolicLink(target.toPath()) && (!target.exists() || target.isDirectory)) { "Conflicting ${kind.mount} path" }
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "${kind.mount}.img"
            val scratch = File(ctx.cacheDir, "part-import-${System.nanoTime()}").apply { mkdirs() }
            val stage = File(paths.dir, ".${kind.mount}-stage").apply { deleteRecursively(); mkdirs() }
            try {
                val image = (ctx.contentResolver.openInputStream(uri) ?: error("Cannot open the ${kind.label} image")).use {
                    OemPartitionSource.read(it, name, scratch, matches = kind.matches, label = kind.label)
                }
                Importer(ctx, { _, _ -> }, { }).importPartitionInto(image, stage, kind.mount)
                val staged = File(stage, kind.mount)
                require(staged.list()?.isNotEmpty() == true) { "The ${kind.label} image holds no files" }
                if (target.exists()) check(target.delete()) { "Cannot replace the empty ${kind.mount} folder" }
                check(staged.renameTo(target)) { "Cannot place the ${kind.label} partition" }
                ImageStore.save(ctx, old.copy(sizeBytes = ImageStore.du(paths.dir)))
            } finally {
                scratch.deleteRecursively()
                stage.deleteRecursively()
            }
        }
    }
}
