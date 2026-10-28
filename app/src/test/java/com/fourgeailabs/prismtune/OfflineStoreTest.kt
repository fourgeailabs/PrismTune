package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.OfflineStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineStoreTest {
    private fun store(): Pair<OfflineStore, File> {
        val dir = File(System.getProperty("java.io.tmpdir"), "prism-offline-${System.nanoTime()}")
        return OfflineStore(dir) to dir
    }

    @Test fun digitKeyStemIsTheKeyItself() {
        val (store, _) = store()
        assertEquals("12345", store.stemFor("12345"))
    }

    @Test fun nonDigitKeyStemIsStableAcrossCalls() {
        val (store, _) = store()
        assertEquals(store.stemFor("weird-key"), store.stemFor("weird-key"))
        assertTrue(store.stemFor("weird-key").startsWith("k"))
    }

    @Test fun isDownloadedNeedsRealNonEmptyFile() {
        val (store, dir) = store()
        assertFalse(store.isDownloaded("999"))
        File(dir, "999.bin").writeText("")
        assertFalse("empty file is not a download", store.isDownloaded("999"))
        File(dir, "999.bin").writeText("audio")
        assertTrue(store.isDownloaded("999"))
    }

    @Test fun downloadedFilesListsOnlyNonEmptyAndDeleteStemRemoves() {
        val (store, dir) = store()
        File(dir, "111.bin").writeText("audio")
        File(dir, "222.bin").writeText("")
        File(dir, "333.bin").writeText("audio")
        assertEquals(listOf("111", "333"), store.downloadedFiles().map { it.nameWithoutExtension })
        assertTrue(store.deleteStem("111"))
        assertFalse(store.isDownloaded("111"))
        assertTrue(store.isDownloaded("333"))
    }

    @Test fun clearAllKeepsPlayingStem() {
        val (store, dir) = store()
        File(dir, "111.bin").writeText("audio")
        File(dir, "222.bin").writeText("audio")
        assertEquals(1, store.clearAll("222"))
        assertFalse(store.isDownloaded("111"))
        assertTrue(store.isDownloaded("222"))
    }
}
