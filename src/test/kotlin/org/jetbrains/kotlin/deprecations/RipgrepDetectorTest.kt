package org.jetbrains.kotlin.deprecations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class RipgrepDetectorTest {

    @Test
    fun reportsMissingRgOnlyOnce() {
        RipgrepDetector.reset()
        val out = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(out))
        try {
            RipgrepDetector.reportMissing()
            RipgrepDetector.reportMissing()
            RipgrepDetector.reportMissing()
        } finally {
            System.setOut(originalOut)
        }

        val output = out.toString().trim()
        val lines = output.lines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        // The message must explain itself: it is the one line a first-time user is most likely
        // to see, and it must not suggest the results differ without rg.
        assertTrue(lines.single().contains("ripgrep"), lines.single())
        assertTrue(lines.single().contains("same"), lines.single())
    }
}
