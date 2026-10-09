package org.adaway.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests of [LegacyHttpCache], which must remove the former cache files and nothing else.
 */
class LegacyHttpCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun removesOnlyTheFormerCacheFiles() {
        val cacheDirectory = folder.newFolder("cache")
        val entry = "0123456789abcdef0123456789abcdef"
        val removed = listOf(
            "journal", "journal.tmp", "journal.bkp",
            "$entry.0", "$entry.1", "$entry.0.tmp"
        )
        val kept = listOf(
            "dns_log.txt", "notes",
            // Not named as OkHttp names its entries.
            "0123456789ABCDEF0123456789ABCDEF.0", "0123456789abcdef.0", "$entry.txt", "${entry}0.0"
        )
        (removed + kept).forEach { File(cacheDirectory, it).writeText("content") }
        // Directories are left alone, the new cache of DNS over HTTPS included.
        File(cacheDirectory, "shared").mkdir()
        File(cacheDirectory, "shared/hosts.txt").writeText("content")
        File(cacheDirectory, "doh").mkdir()
        File(cacheDirectory, "doh/journal").writeText("content")
        File(cacheDirectory, "doh/$entry.0").writeText("content")

        LegacyHttpCache.clear(cacheDirectory)

        assertEquals(
            (kept + listOf("shared", "doh")).sorted(),
            cacheDirectory.list()!!.sorted()
        )
        assertEquals(listOf("hosts.txt"), File(cacheDirectory, "shared").list()!!.toList())
        assertEquals(listOf("$entry.0", "journal"), File(cacheDirectory, "doh").list()!!.sorted())
    }

    @Test
    fun missingDirectoryIsIgnored() {
        LegacyHttpCache.clear(File(folder.root, "missing"))
    }
}
