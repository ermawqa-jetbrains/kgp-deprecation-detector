package org.jetbrains.kotlin.deprecations

import java.util.concurrent.atomic.AtomicBoolean

internal object RipgrepDetector {
    private val warningPrinted = AtomicBoolean(false)

    fun reportMissing() {
        if (warningPrinted.compareAndSet(false, true)) {
            println(
                "NOTE: ripgrep (rg) was not found on PATH - falling back to an in-process file walk. " +
                "Install rg to make the scan much faster."
            )
        }
    }

    // For tests
    internal fun reset() {
        warningPrinted.set(false)
    }
}
