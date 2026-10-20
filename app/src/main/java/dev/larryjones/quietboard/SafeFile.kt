package dev.larryjones.quietboard

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe small-file storage: write a temp file, sync it to disk, then atomically replace the
 * target. A power cut leaves either the old file or the new one, never an empty one; a failed write
 * leaves the old file alone.
 */
object SafeFile {
    private fun tmpOf(f: File) = File(f.parentFile, f.name + ".tmp")

    fun write(f: File, bytes: ByteArray): Boolean {
        val tmp = tmpOf(f)
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    /** The file's contents; if only a finished temp copy exists (an older version crashed mid-rename), that. */
    fun read(f: File): ByteArray? =
        runCatching { f.readBytes() }.getOrNull() ?: runCatching { tmpOf(f).readBytes() }.getOrNull()

    fun delete(f: File) {
        f.delete()
        tmpOf(f).delete()
    }
}
