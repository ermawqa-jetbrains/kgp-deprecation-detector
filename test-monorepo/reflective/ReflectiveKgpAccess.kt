package reflective

private class LinkTaskReflection(private val linkTask: Any) {
    val compilation: Any? by lazy {
        // Deprecated KGP API, reached through reflective dispatch this time:
        linkTask.callReflectiveAnyGetter("getCompilation", logger)
    }
}
