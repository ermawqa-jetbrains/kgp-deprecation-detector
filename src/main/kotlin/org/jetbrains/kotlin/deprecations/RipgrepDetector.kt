package org.jetbrains.kotlin.deprecations

import java.util.concurrent.atomic.AtomicBoolean

internal object RipgrepDetector {
    private val warningPrinted = AtomicBoolean(false)

    fun reportMissing() {
        if (warningPrinted.compareAndSet(false, true)) {
            println("<--------INSTALL RG FOR FASTER DETECTION --------->")
        }
    }

    // for testing purposes
    internal fun reset() {
        warningPrinted.set(false)
    }
}
