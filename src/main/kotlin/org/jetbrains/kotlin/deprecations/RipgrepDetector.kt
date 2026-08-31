package org.jetbrains.kotlin.deprecations

import java.util.concurrent.atomic.AtomicBoolean

internal object RipgrepDetector {
    private val warningPrinted = AtomicBoolean(false)

    fun reportMissing() {
        if (warningPrinted.compareAndSet(false, true)) {
            println(
                """
                ================================================================================
                [WARNING] ripgrep (rg) is NOT installed or not on PATH!
                Scanning will be significantly slower (falling back to in-process file walk).
                The scan results are identical, but installing 'rg' is strongly recommended:

                  • macOS:   brew install ripgrep
                  • Ubuntu:  sudo apt install ripgrep
                  • Fedora:  sudo dnf install ripgrep
                  • Windows: winget install BurntSushi.ripgrep.MSVC

                If 'rg' is installed but not on PATH (common on CI agents), point at it directly:
                  -PrgPath=/path/to/rg
                ================================================================================
                """.trimIndent()
            )
        }
    }

    // For tests
    internal fun reset() {
        warningPrinted.set(false)
    }
}
