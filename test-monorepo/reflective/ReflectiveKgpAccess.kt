// Fixture for the detector (NOT compiled — read as text by the tool).
//
// Mimics KotlinArtifactReflection.kt-style reflective dispatch: the target member name is a
// string literal, resolved only at runtime, so the compiler never sees it as a call to that
// member. Same blind spot as an embedded Gradle script, caught by ReflectiveCallArgExtractor
// + ReflectiveCallArgScanner instead of EmbeddedScriptExtractor + EmbeddedScriptScanner.
package reflective

private class LinkTaskReflection(private val linkTask: Any) {
    val compilation: Any? by lazy {
        // Deprecated KGP API, reached through reflective dispatch this time:
        linkTask.callReflectiveAnyGetter("getCompilation", logger)
    }
}
