package com.sukhotski.backupprep.copy

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {

    @Test
    fun `sanitize replaces path separators and control characters`() {
        assertEquals("a_b_c.txt", FileNames.sanitize("a/b\\c.txt"))
        assertEquals("tab_name", FileNames.sanitize("tab\tname"))
        assertEquals("quote_d", FileNames.sanitize("quote\"d"))
    }

    @Test
    fun `sanitize falls back for empty or dangerous names`() {
        assertEquals("file", FileNames.sanitize(null))
        assertEquals("file", FileNames.sanitize("   "))
        assertEquals("file", FileNames.sanitize(".."))
    }

    @Test
    fun `sanitize keeps the extension when truncating`() {
        val long = "a".repeat(300) + ".pdf"
        val result = FileNames.sanitize(long)
        assertEquals(200, result.length)
        assertEquals(".pdf", result.takeLast(4))
    }

    @Test
    fun `sanitize never returns a name that ends in part because that suffix is reserved for temp files`() {
        assertEquals("foo.part_", FileNames.sanitize("foo.part"))
        assertEquals("foo.PART_", FileNames.sanitize("foo.PART"))
        assertEquals("foo.part.txt", FileNames.sanitize("foo.part.txt"))
    }

    @Test
    fun `unique adds a counter before the extension`() {
        val result = FileNames.unique(listOf("report.pdf", "report.pdf", "report.pdf", "notes"))
        assertEquals(listOf("report.pdf", "report (1).pdf", "report (2).pdf", "notes"), result)
    }

    @Test
    fun `unique ignores case and never reuses an existing name`() {
        val result = FileNames.unique(listOf("a.txt", "A.TXT", "a (1).txt"))
        assertEquals(listOf("a.txt", "A (1).TXT", "a (1) (1).txt"), result)
    }

    @Test
    fun `unique handles names without extension and dot files`() {
        assertEquals(listOf("Makefile", "Makefile (1)"), FileNames.unique(listOf("Makefile", "Makefile")))
        assertEquals(listOf(".env", ".env (1)"), FileNames.unique(listOf(".env", ".env")))
    }
}
