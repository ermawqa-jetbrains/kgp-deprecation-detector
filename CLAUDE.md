# KGP Deprecation Detector — contributor notes

Detects usages of **deprecated Kotlin Gradle Plugin (KGP) APIs** in a monorepo of Gradle
build scripts, so they can be fixed while still `WARNING`-level (before KGP removes them or
escalates to `ERROR`). Motivation: Bazel-built monorepos never compile `.gradle.kts`, so the
compiler's deprecation warning never fires; this tool restores that signal for CI.

## Two independent passes (never mixed)

1. **Resolution pass — `.gradle.kts`, zero false positives, owns the CI gate.**
   Per script: Gradle Tooling API → `KotlinBuildScriptModel` (real classpath incl. generated
   accessors + implicit imports) → compile with the Kotlin scripting host → harvest the
   compiler's own `DEPRECATION`/`DEPRECATION_ERROR` diagnostics. Findings are
   `Finding(source = RESOLVED)`. Exit 1 on ERROR, exit 2 on UNRESOLVED.

2. **Groovy heuristic pass — separate, non-gating, name-matching.**
   Groovy is dynamically typed → **no frontend can resolve it**, so this is text name-matching
   (the pre-rebuild approach, scoped to Groovy only). Index of `@Deprecated` names is read from
   the KGP jars via ASM; matched whole-word against masked Groovy text in standalone `.gradle`
   files and Groovy scripts embedded as string literals in `.kt`/`.java`. Findings are
   `Finding(source = HEURISTIC)`, printed in a separate "review required" section, and **never
   change the exit code** unless `-PgroovyGating`. Has false positives by nature (generic names
   like `project`/`target`) — that is the unavoidable cost of covering dynamic Groovy.

## File map

- `Main.kt` — CLI orchestration: walk, exclude, per-script skip/resolve, both passes, report, exit policy.
- `GradleScriptModelProvider.kt` — Tooling API fetch → `ScriptModelResult.Resolved | Failed`.
- `KgpDeprecationAnalyzer.kt` — the resolver: scripting-host compile + diagnostic harvest. **Core.**
- `Finding.kt` — `Finding` + `DeprecationLevel` + `FindingSource{RESOLVED,HEURISTIC}`.
- `org/gradle/kotlin/dsl/tooling/models/KotlinBuildScriptModel.kt` — client copy of Gradle's model
  interface; **must keep this exact package + FQN** (Tooling API adapts by name, avoids an unpublished jar).
- Groovy pass: `KgpDeprecationExtractor.kt` (ASM name index), `DeprecatedSymbol.kt` (index entry),
  `EmbeddedGroovyScriptExtractor.kt` (pull `"""…"""` literals), `GroovyDeprecationScanner.kt`
  (mask + match + offset remap), `GroovySourceFinder.kt` (`rg` pre-filter, walk fallback).

## Load-bearing gotchas (do not regress)

- **Scripting host classloader:** the implicit-receiver class (`org.gradle.api.Project`) is loaded
  via `ScriptingHostConfiguration.configurationDependencies` (a `JvmDependency(classPath)`), NOT
  `jvm.baseClassLoader`/thread CL. Omit it → `ClassNotFoundException Project` even with Project on
  the compile classpath. (Source: `JvmGetScriptingClass.invoke`.)
- **Both compiler plugins Gradle uses must be applied:** sam-with-receiver (makes `kotlin { jvm {} }`
  accessor blocks resolve) AND assignment (lazy `prop = value`). Located on the classpath by
  `locatePluginJar`, passed via `-Xplugin` + `-P plugin:<id>:annotation=<fqn>`.
- **Strip the leading `plugins { }` block** before compiling (Gradle compiles it separately;
  left in, it resolves to a deprecated stub and errors). `stripPluginsBlock` is string/comment-aware.
- **Engine version ≥ scanned KGP version**, else "class compiled with a newer Kotlin" read error.
  `-PkgpEngineVersion` (default `2.4.0`); dev versions resolve via the `kt/dev` repo.
- **Fast pre-skips** (no Gradle bootstrap): script with no ancestor `settings.gradle(.kts)`, or a
  settings root with no `gradle/wrapper/gradle-wrapper.properties` (and no `-PgradleInstallation`).
  These avoid slow distribution downloads that would only fail.
- **`gradleInstallation` defaulting:** only falls back to the detector's own Gradle for the built-in
  `test-monorepo`; external monorepos use each project's own wrapper (pass empty / omit the flag).
- **Groovy marker regex** (in `EmbeddedGroovyScriptExtractor` + `GroovySourceFinder`) must stay
  specific — `gradle\.` alone matched arbitrary path strings (false positives). Use
  `gradle\.(ext|rootProject|settingsEvaluated|buildFinished)` etc. Keep the two copies in sync.
- **Heuristic findings reuse the same allowlist** (`Finding.symbol`); resolved symbols are rendered
  signatures (`fun withJava(): Unit`), heuristic symbols are qualified names.

## Build / test / run

```bash
./gradlew build                          # compile + 27 unit tests (no Gradle/network)
./gradlew checkKgpDeprecations           # run against bundled test-monorepo fixture
# external monorepo (each project uses its own wrapper; allow incomplete coverage):
./gradlew checkKgpDeprecations -PmonorepoDir=<path> -PallowUnresolved
# opt-in end-to-end Tooling-API test:
KGP_IT_PROJECT=<kmp-project> [KGP_IT_GRADLE=<gradle>] ./gradlew test
```

Flags: `-Pallowlist` `-PgradleInstallation` `-PkgpEngineVersion` `-PexcludePatterns`
`-PallowUnresolved` `-PscanGroovy=false` `-PgroovyGating` `-PgroovyScanRoot`. See README.

## Repo

`main` is mirror-pushed to **both** JetBrains Space (primary, `git.jetbrains.team/kqa/...`) and a
GitHub mirror via two push URLs on `origin`. A plain `git push` writes to both. Keep Space's own
outgoing GitHub mirror **disabled** (it blocks pushes). `main` is protected — no force-push.
