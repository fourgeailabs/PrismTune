package com.fourgeailabs.prismtune.data

import java.io.File

/**
 * 1.1.0 downloaded-track storage for Offline Top Picks.
 * Files live under the app's private files dir (never external,
 * never alongside a token or password — files are audio only).
 * Filenames are the Plex ratingKey (digits) plus ".mp3" fallback
 * extension; unknown keys fall back to a hash so a weird key can
 * never escape the directory.
 */
class OfflineStore(private val dir: File) {
    init {
        if (!dir.exists()) dir.mkdirs()
    }

    fun fileFor(trackKey: String): File {
        val safe = if (trackKey.all { it.isDigit() } && trackKey.isNotBlank()) trackKey
        else "k${kotlin.math.abs(trackKey.hashCode())}"
        return File(dir, "$safe.bin")
    }

    fun isDownloaded(trackKey: String): Boolean = fileFor(trackKey).let { it.exists() && it.length() > 0 }

    /** 1.5.8: the filename stem for a key — for Plex
     *  ratingKeys (digits) this IS the key; for anything
     *  else it is the hash fallback. User-requested
     *  downloads are tracked by stem (see TokenStore),
     *  because the disk only ever knows stems, and a
     *  delete-by-original-key would miss hashed files. */
    fun stemFor(trackKey: String): String = fileFor(trackKey).nameWithoutExtension

    /** 1.5.8: every non-empty downloaded file on disk,
     *  for the Settings Downloads menu. */
    fun downloadedFiles(): List<File> =
        dir.listFiles()?.filter { it.length() > 0 }?.sortedBy { it.name } ?: emptyList()

    /** 1.5.8: delete one file by its stem, as listed by
     *  downloadedFiles. Returns true if a file was gone. */
    fun deleteStem(stem: String): Boolean = File(dir, "$stem.bin").delete()

    fun downloadedKeys(): Set<String> =
        dir.listFiles()?.filter { it.length() > 0 }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()

    fun storageBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(trackKey: String) {
        fileFor(trackKey).delete()
    }

    /** Prune files not in [keepKeys], never deleting [playingKey]. */
    fun prune(keepKeys: Set<String>, playingKey: String?): Int {
        var removed = 0
        for (f in dir.listFiles() ?: emptyArray()) {
            val key = f.nameWithoutExtension
            if (key !in keepKeys && key != playingKey && f.delete()) removed++
        }
        return removed
    }

    fun clearAll(playingKey: String?): Int = prune(emptySet(), playingKey)
}
