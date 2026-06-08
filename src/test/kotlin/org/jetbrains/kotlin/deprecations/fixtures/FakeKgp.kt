package org.jetbrains.kotlin.deprecations.fixtures

/**
 * Stand-in for a KGP DSL extension, used as the implicit receiver in analyzer unit tests.
 * Lets the tests exercise real symbol resolution without driving Gradle or downloading KGP.
 */
@Suppress("unused")
class FakeKotlinExtension {
    @Deprecated("Use newApi() instead", ReplaceWith("newApi()"), DeprecationLevel.WARNING)
    fun oldApi() {}

    @Deprecated("Removed", level = DeprecationLevel.ERROR)
    fun goneApi() {}

    fun newApi() {}
}

/**
 * A different receiver with a method of the SAME NAME that is NOT deprecated. The old
 * text-matching detector flagged this as a false positive; the resolving analyzer must not.
 */
@Suppress("unused")
class UnrelatedReceiver {
    fun oldApi() {}
}
