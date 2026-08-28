package org.jetbrains.kotlin.deprecations

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ensures the allowlist is auditable: every entry must have a reason.
 */
class AllowlistTest {

    private val allowlist = File("config/allowlist-intellij.txt")

    @Test
    fun everyEntryIsPrecededByAReasonComment() {
        var lastComment: String? = null
        val unexplained = mutableListOf<String>()
        allowlist.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#") -> lastComment = line
                else -> if (lastComment == null) unexplained += line
            }
        }
        assertTrue(unexplained.isEmpty(), "allowlist entries without a reason comment: $unexplained")
    }
}
