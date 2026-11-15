package org.salvami.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Backup archive = zip with `library.json` (items, tags, memos, history, profiles), `media/<files>` and `avatars/<files>`. */
object Backup {
    fun export(ctx: Context, uri: Uri): Int {
        val state = Library.state.value
        var count = 0
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                zip.putNextEntry(ZipEntry("library.json"))
                zip.write(Library.toJson(state).toString(2).toByteArray())
                zip.closeEntry()
                zip.setLevel(Deflater.NO_COMPRESSION) // media is already compressed
                for (item in state.items) {
                    val f = Library.fileOf(item)
                    if (!f.isFile) continue
                    zip.putNextEntry(ZipEntry("media/${item.file}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
                for (p in state.profiles) {
                    val f = File(Library.avatarDir, p.avatar)
                    if (p.avatar.isEmpty() || !f.isFile) continue
                    zip.putNextEntry(ZipEntry("avatars/${p.avatar}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return count
    }

    /** Merges a backup into the current library; items already present (same id) are kept as they are. */
    fun restore(ctx: Context, uri: Uri): Int {
        var backup: LibState? = null
        val staging = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val avatars = File(staging, "avatars").apply { mkdirs() }
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.isDirectory) continue
                        if (e.name == "library.json") {
                            backup = Library.fromJson(JSONObject(zip.readBytes().decodeToString()))
                        } else if (e.name.startsWith("media/")) {
                            // File(name).name drops any path component (zip-slip guard).
                            File(staging, File(e.name).name).outputStream().use { zip.copyTo(it) }
                        } else if (e.name.startsWith("avatars/")) {
                            File(avatars, File(e.name).name).outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            val b = backup ?: throw IllegalArgumentException("library.json missing: not a Salvami backup")
            val current = Library.state.value
            val knownItems = current.items.map { it.id }.toSet()
            val newItems = b.items.filter { it.id !in knownItems }.mapNotNull { item ->
                val src = File(staging, item.file)
                if (!src.isFile) return@mapNotNull null
                val dest = Library.freshFile(item.file)
                if (!src.renameTo(dest)) { src.copyTo(dest, overwrite = true); src.delete() }
                item.copy(file = dest.name)
            }
            val knownProfiles = current.profiles.map { it.id }.toSet()
            val newProfiles = b.profiles.filter { it.id !in knownProfiles }.map { p ->
                val src = File(avatars, p.avatar)
                if (p.avatar.isEmpty() || !src.isFile) p.copy(avatar = "")
                else { src.copyTo(File(Library.avatarDir, p.avatar), overwrite = true); p }
            }
            val knownTags = current.tags.map { it.id }.toSet()
            val knownHistory = current.history.map { it.id }.toSet()
            Library.update { s ->
                s.copy(
                    items = (s.items + newItems).sortedByDescending { it.savedAt },
                    tags = s.tags + b.tags.filter { it.id !in knownTags },
                    history = (s.history + b.history.filter { it.id !in knownHistory }).sortedByDescending { it.time },
                    profiles = (s.profiles + newProfiles).sortedByDescending { it.savedAt },
                )
            }
            return newItems.size
        } finally {
            staging.deleteRecursively()
        }
    }
}
