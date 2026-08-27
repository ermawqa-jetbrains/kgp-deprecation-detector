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
            val firstCallOutput = out.toString()
            RipgrepDetector.reportMissing()
            RipgrepDetector.reportMissing()
            assertEquals(firstCallOutput, out.toString(), "Should only print once across multiple calls")
        } finally {
            System.setOut(originalOut)
        }

        val output = out.toString().trim()
        assertTrue(output.contains("ripgrep") || output.contains("rg"), "Output should mention ripgrep: $output")
        assertTrue(output.contains("brew install ripgrep"), "Output should contain install command: $output")
    }
}
