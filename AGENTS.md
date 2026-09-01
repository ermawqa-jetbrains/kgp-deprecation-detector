# KGP Deprecation Detector — contributor notes

Detects usages of **deprecated Kotlin Gradle Plugin (KGP) APIs** in two places the compiler can't
see through:
1. **Embedded Gradle scripts** — Groovy or Kotlin-DSL scripts hardcoded as string literals inside
   `.kt`/`.java` (IDE-injected init/build scripts). Motivation: this is exactly the case that
   caused a real incident — KGP removed `KotlinCompilation.defaultSourceSetName`, breaking a
   Gradle init script hardcoded as a Groovy string inside a `.kt` file, with zero dev-time signal
   because the string was never compiled or type-checked by anything.
2. **Reflective calls** — a member name passed as a string literal to a `callReflective*` helper
   (the cross-KGP-version compat-dispatch convention used e.g. in
   `.../gradleTooling/reflect/*.kt`), such as `instance.callReflectiveGetter("getCompilation",
   logger)`. The target is resolved only at runtime; the compiler never sees this as a call to
   that member, so a deprecation/removal on the real target produces no warning at the call site.
   Same blind spot as an embedded script, different mechanism — added as a second pass alongside
   the first, not a replacement.

**Real `.gradle.kts` files are explicitly out of scope.** They're already resolved in-editor by
IntelliJ (same Tooling API model an earlier version of this tool used), so their deprecation
warnings are already visible to developers. Building a second resolver for that case was
redundant — a whole prior architecture (Gradle Tooling API, Kotlin scripting-host compile,
parallel daemon fan-out, orphan-rescue via project enumeration, fetch timeouts) existed to serve
it and was deleted. If you're tempted to resurrect resolution of `.gradle.kts`, don't — it isn't
what this tool is for. Likewise, plain `.kt`/`.java` code calling a deprecated API by direct
reference is out of scope — the compiler already flags that; only *unresolvable* call shapes
(hardcoded script text, reflective dispatch) belong here.

## Two independent passes, offline (no Gradle, no daemon, no network beyond the KGP jar download)

Both build on the same index and combine into one `findings` list before the allowlist is
applied once in `Main.kt`. Each pass has its own finder/extractor/scanner trio — extending one
pass never requires touching the other's files.

**Index** — `KgpDeprecationExtractor` reads every `@Deprecated` (Kotlin) declaration out of the
KGP jars via ASM, no class loading. Produces `List<DeprecatedSymbol>`, shared by both passes
below.

**Pass 1 — embedded scripts:**
1. **Find candidates** — `EmbeddedScriptFinder` locates `.kt`/`.java` under the scan root that
   look like they embed a Gradle script (`rg` pre-filter; in-process walk fallback).
2. **Extract** — `EmbeddedScriptExtractor` pulls triple-quoted literals that look like Gradle
   scripts (Groovy *or* Kotlin-DSL markers) out of each candidate.
3. **Match** — `EmbeddedScriptScanner` masks comments/strings, then whole-word matches the
   deprecated names against the text, remapping positions to the host file.

**Pass 2 — reflective calls:**
1. **Find candidates** — `ReflectiveCallFinder` locates `.kt`/`.java` files containing the
   `callReflective` marker (same `rg`/walk pattern as pass 1, different marker).
2. **Extract** — `ReflectiveCallArgExtractor` pulls the string-literal target name out of each
   `callReflective\w*(...)` call site (comments masked, strings deliberately left intact — the
   string content *is* the name being searched for).
3. **Match** — `ReflectiveCallArgScanner` looks the name up in the index by **exact** JVM
   `memberName` — the extracted name is already an isolated identifier, so no whole-word
   regex/masking is needed the way pass 1 needs it for a block of script text.

Findings from both passes are gated together: `ERROR`/`HIDDEN` → exit 1; `WARNING`-only (or
clean) → exit 0.

This is **name-matching, not resolution** — unavoidable, since Groovy is dynamically typed
(no frontend can resolve it), a Kotlin-DSL string literal isn't compiled by anything either, and
a reflective call's target isn't known until runtime. Has false positives by nature (generic
names like `project`/`target`/`create`/`dependencies` collide across unrelated declarations); the
allowlist is the mitigation, not a fix. See `config/allowlist-intellij.txt` for the set of
generic-name collisions found scanning the real IntelliJ monorepo.

## File map

- `Main.kt` — CLI orchestration: index build, both passes' candidate scan + extract+match,
  combine findings, report, exit gate.
- `KgpDeprecationExtractor.kt` — ASM jar scanner → `DeprecatedSymbol` index (shared by both
  passes).
- `DeprecatedSymbol.kt` — index entry (className/memberName/level/message/replaceWith,
  computed `qualifiedName`/`searchName`).
- `Finding.kt` — `Finding` + `DeprecationLevel`. One shape for both passes' output.
- `SourceFileFinder.kt` — the single candidate search shared by both passes: `rg` fast path +
  walk fallback, pinned to the same semantic (below).

Pass 1 (embedded scripts):
- `EmbeddedScriptFinder.kt` — marker declaration only; delegates to `SourceFileFinder`.
- `EmbeddedScriptExtractor.kt` — pulls `"""…"""` literals; `@Language` tag filtering (below).
- `EmbeddedScriptScanner.kt` — mask + whole-word match + offset remap; also holds
  `maskCommentsAndStrings`.

Pass 2 (reflective calls):
- `ReflectiveCallFinder.kt` — marker declaration only (`callReflective`, matched as a fixed
  string); delegates to `SourceFileFinder`.
- `ReflectiveCallArgExtractor.kt` — pulls the target name out of each `callReflective\w*(...)`
  call site, either as an inline string literal or via a string constant declared in the same
  file; also holds `maskComments` (comments-only — unlike
  `maskCommentsAndStrings`, must NOT blank string content, since the string content is the name
  being searched for).
- `ReflectiveCallArgScanner.kt` — exact `memberName` lookup, no masking/whole-word regex needed
  (the extracted name is already an isolated identifier).

## Load-bearing gotchas (do not regress)

- **`@Language` tag is authoritative, checked with a bounded lookback.**
  `EmbeddedScriptExtractor.languageAnnotationBefore` scans up to `LANGUAGE_LOOKBACK_LINES` (3)
  lines above the opening `"""` for `@Language("X")` — as a real annotation OR the
  `// @Language("X")` comment form (used on locals, since the real annotation can't target a
  local `val`). If `X` isn't in `ALLOWED_LANGUAGES` (`groovy`, `kotlin`), the literal is skipped
  **unconditionally**, even if its content matches `SCRIPT_MARKER`. This exists because a
  1-line lookback missed a real case: `@Language("Markdown")` / `internal val x: String =` /
  `"""` on its own line — the annotation sits 2 lines above the quote when the declaration wraps.
  Regression if narrowed: a `JewelReadme.kt`-style `@Language("Markdown")` string showing a
  `plugins { }` code *sample* gets scanned as if it were a real script (166 false hits in one
  file, observed on the IntelliJ monorepo).
- **`SCRIPT_MARKER` must stay specific.** It's shared (as text, kept in sync) between
  `EmbeddedScriptExtractor` and `EmbeddedScriptFinder`. `gradle\.` alone matched arbitrary path
  strings; use `gradle\.(ext|rootProject|settingsEvaluated|buildFinished)` etc. The Kotlin-DSL
  half of the marker (`plugins\s*\{`, `kotlin\s*\{`, `dependencies\s*\{`, …) is *intentionally*
  broader and is exactly what the `@Language` check above exists to guard against.
- **Default excludes include both `/test/` and `/tests/`** (singular and plural) — real
  monorepo paths use both conventions inconsistently; missing either lets test-only fixtures
  and generator-test files through as noise.
- **Every setup failure must exit 2, never 0.** `main` delegates to `run(args): Int`; all paths
  where the check never actually ran (no/blank scan root, scan root not a directory, allowlist
  file missing, no KGP jars, empty index) return `EXIT_SETUP_FAILURE`. A bare `return` there
  produces a green CI build with zero findings — the worst failure mode for a tool whose purpose
  is restoring a missing signal. `1` (`EXIT_FINDINGS`) stays reserved for real violations so CI
  can distinguish the two. Per-jar extract failures are printed, never swallowed silently (a
  partial index looks exactly like a clean run). Pinned by `MainExitCodeTest`.
- **The Gradle task must keep exit 1 and 2 distinguishable, and handle TeamCity cleanly.**
  `JavaExec` turns any non-zero exit into the same generic `BUILD FAILED`, so through
  `./gradlew checkKgpDeprecations` — the documented entry point — CI could not tell "tool never
  ran" from "real violations". The task sets `isIgnoreExitValue = true` and a `doLast` reads
  `executionResult.get().exitValue`, throwing a different message per code (`0` passes, `1`
  FAILED, `2` DID NOT RUN, anything else unexpected). On TeamCity (`TEAMCITY_VERSION` /
  `teamcity.version`), `Main` emits `##teamcity[buildStatus]` and `##teamcity[buildProblem]`, and
  `doLast` completes cleanly for exit 1 so TeamCity marks the build as failed solely via the
  `buildProblem` message without duplicate "Process exited with code 1" failures or 100-line Gradle
  Tooling API stack traces. `executionResult` must be captured into a local outside `doLast`
  (configuration cache forbids referencing the task from its own action).
- **Unknown `-P` properties must fail the build.** `build.gradle.kts` validates
  `gradle.startParameter.projectProperties` against `knownProjectProperties` and throws (with a
  Levenshtein "did you mean" hint) before any task runs. Gradle silently accepts any `-P<name>`,
  so `-PmonrepoDir=<path>` would otherwise fall back to the default `test-monorepo` fixture and
  report a clean run against the wrong tree — the configuration-time twin of the exit-code-2
  rule above. Names containing `.` are skipped (they belong to Gradle/plugins). When adding a
  new flag, add it to `knownProjectProperties` too.
- **Pass 2 matches over the whole file, not line by line.** `ReflectiveCallArgExtractor` runs
  `CALL_SITE` over the entire masked text and derives line/column from the match offset. The
  `\s*` in the pattern spans newlines, which is the point: these helpers take 2-3 extra args, so
  the formatter wraps the call and puts the literal on the next line. A `lines()`-then-match loop
  missed every such call site — a whole false-negative class in the `gradleTooling/reflect`
  sources this pass exists for. Do not reintroduce per-line splitting.
- **Both scanners report every occurrence, deduped on (line, column, qualifiedName)** — not
  `find()`-first-per-line, and not keyed on (line, name): two usages on one line are two hits,
  each with its own caret. Only the same symbol at the same position collapses.
- **Column remap applies `colOffset` on content line 1 only** (`EmbeddedScriptScanner`). Correct,
  not a bug: the raw `"""` content carries the host file's own indentation from line 2 on, so the
  in-content column already is the host column. Pinned by
  `columnOnLinesAfterTheFirstIsAlreadyAbsolute` and
  `columnOnTheFirstLineIsShiftedByTheLiteralStart`.
- **The index's `internal`/`utils`/`impl`/`Android` filter is opt-out and its cost is printed.**
  `KgpDeprecationExtractor.extractIndex(jar, fullIndex)` returns the skipped-class count, which
  `Main` prints in the banner (~4900 classes / 145 symbols on KGP 2.4.0); `-PfullIndex`
  (`kgp.fullIndex`) disables it. KGP ships public API under `impl`/`utils` and an `Android*`
  symbol is exactly what an AGP-injected init script touches, so a silent unconditional filter
  was hiding real coverage. `extract(jar)` remains as the symbols-only convenience.
- **The index is hard-scoped to `KgpDeprecationExtractor.PACKAGE_SCOPE` (`org.jetbrains.kotlin`).**
  Not configurable: third-party `@Deprecated` members KGP bundles are not KGP API and carry generic
  names - `kotlinx.coroutines.flow.FlowKt.merge` matched the word `merge` anywhere in the monorepo.
  `extractIndex` returns `outOfScopeClasses`, printed as the banner's `Scope` line (~1370 classes on
  KGP 2.5.0-dev), so the cost stays visible. Test fixtures must declare fake classes in a KGP
  package.
- **`$DefaultImpls` classes are dropped from the index, and findings are grouped/counted by
  `Finding.deprecationId` (member, else simple class name) + message, not by declaring class.** A
  default interface method or inherited property is declared in every sub-interface of a hierarchy,
  so one `kotlinOptions { }` call site produced 20+ identical report sections (`KotlinCompile`,
  `KotlinJvmCompile`, `KotlinCompilation`, ...) and was counted 20+ times. `Main` dedupes usages on
  (file, line, column, deprecationId, message) before the summary and the exit gate, and each report
  section lists the declaring classes (`Declared in: <qualified>, +N more` - qualified because an
  allowlist entry is `<class>.<member>`). The `$DefaultImpls` skip is counted and printed as the
  banner's `Synthetic` line.
- **Allowlisting one declaring class suppresses the whole group.** A finding is still matched by
  its full `Finding.symbol` (`<class>.<member>`), but before building the report, `Main` computes
  `allowlistedGroups`: the `(deprecationId, message)` of every finding whose `symbol` is in the
  allowlist. Any finding whose `(deprecationId, message)` is in that set is dropped, regardless of
  which sibling class it was declared in. So one allowlist entry (e.g.
  `org.jetbrains.kotlin.gradle.dsl.KotlinCompile.kotlinOptions`) suppresses the same call site
  reported under `KotlinJvmCompile`, `KotlinCompilation`, etc. - matching the "one call site, one
  entry" expectation created by the report grouping, with no allowlist file format change.
- **Pass 2 resolves same-file string constants (Tier 1), and only those.**
  `ReflectiveCallArgExtractor` builds a `name -> literal` map from the file's own
  `val`/`const val`/Java `static final String` declarations and accepts an identifier (optionally
  qualified, resolved by simple name) as the call argument. Deliberate boundaries: a name declared
  twice in the file with different values is dropped, and an unknown identifier yields nothing —
  the pass fails closed, because a wrong hit is worse than a missing one in a tool whose main
  weakness is false positives. Cross-file constants (Tier 2) stay out of scope: matching a simple
  name across the whole tree gives the wrong value for same-named constants. Concatenated /
  interpolated names (Tier 3) are undecidable without the compiler this pass works without.
- **`CALL_SITE` requires the literal to be the *whole* argument** (trailing `\s*[,)]`). Without
  it, `callReflectiveGetter("get" + name, …)` matched the fragment `get` and reported it as a
  usage — a wrong hit, not merely a missed one. Pinned by `ignoresConcatenatedTargetName`.
- **The `rg` fast path and the walk fallback must report the same files.** Both live in
  `SourceFileFinder`; the finders only declare a marker. The pinned semantic is "every `.kt`/
  `.java` under the root except `.git`": `--no-ignore` (a monorepo gitignores
  generated-but-shipped sources; what to skip is `excludePatterns`' job, not the VCS's),
   `--hidden` (the walk has no notion of hidden files, so `rg` must not either) and `-g !.git/`
   plus an `onEnter` skip. Without those flags a run's results depended on whether `rg` happened
   to be installed, making CI and local runs incomparable. Pinned by `SourceFileFinderTest`.
- **`EmbeddedScriptFinder.MARKER` is the single source of truth for the "looks like a Gradle
  script" pattern.** It used to be duplicated by hand as `EmbeddedScriptExtractor.SCRIPT_MARKER`
  - two byte-identical string literals with nothing testing they stayed equal, so a finder marker
  narrower than the extractor's would silently lose candidates. `EmbeddedScriptExtractor` now
  builds its `Regex` directly from `EmbeddedScriptFinder.MARKER` instead of its own copy. Pinned
  by `EmbeddedScriptMarkerTest`.
- **`-PrgPath` exists because PATH is not reliable on CI.** `SourceFileFinder` invokes
  `System.getProperty("kgp.rgPath")` and only falls back to a bare `rg`. TeamCity's Gradle runner
  recomputes PATH internally when `jdkHome` is set, so an `env.PATH` prepend was silently dropped
  and every CI run degraded to the walk fallback while the preceding step reported `rg` as cached.
  Pass the executable, don't hope for PATH.
- **The ripgrep path catches `Exception`, not just `IOException`, and redirects stderr.** A
  missing `rg` throws `IOException`, but a `SecurityException` or an interrupted `waitFor` used
  to kill the whole run instead of degrading to the walk; `redirectErrorStream(true)` removes the
  unconsumed-stderr deadlock hazard. Every failure path must fall back, never propagate.
- **This file is tracked; `CLAUDE.md` is not.** Every gotcha here is the only record of a
  decision that looks like dead weight to a "cleanup" refactor, so it must survive a fresh
  clone. `AGENTS.md` was gitignored once — don't re-add it. `graphify-out/` (generated analysis
  output) is gitignored for the opposite reason.
- **`monorepoDir` is validated at configuration time** (`resolveScanRoot` in
  `build.gradle.kts`): resolved against `layout.projectDirectory` when relative, and a
  non-directory throws before the JVM starts. A raw relative string used to reach the JavaExec
  working dir untouched, so the truncated-path incident (`/Useyermukhamed.shakhman/…`) was caught
  only after startup — and a path that happens to exist relative to the daemon's cwd would have
  been scanned silently. Same family as the exit-2 rule.
- **The check task always runs** (`outputs.upToDateWhen { false }`) — stated deliberately, not
  left implied by declaring no inputs/outputs. `kgp.pluginJars` uses `kgpJars.asPath` inside the
  `tasks.register` block, which is lazy enough (the block only runs when the task is in the
  graph, so `./gradlew help` never resolves the configuration). It must NOT be a `Provider`:
  `JavaExec.systemProperty` does not unwrap one and passes its `toString()` through, which the
  tool then reports as `Failed to read KGP jar 'map(provider(...))'`.
- **KGP jars come from a TeamCity build, not from a published Maven repository.** The scan is run
  while a deprecation phase is being prepared, i.e. against KGP built from Kotlin master before it
  is deployed anywhere, so `packages.jetbrains.team/maven/p/kt/dev` was replaced by the
  `maven.zip` artifact of `-PkgpBuildType` (default `Kotlin_KotlinDev_Artifacts`), which TeamCity
  serves from inside the archive (`.../artifacts/content/maven.zip!/`) over guest auth — no
  credentials, but the JetBrains network is required. Consequences that are easy to break:
  - **A version IS a build number** (`2.5.0-dev-6260`); a released version like `2.4.10` does not
    exist there. That is checked against the REST API up front, so the user gets "no build
    numbered X, pick one at <url>" instead of "Could not find kotlin-gradle-plugin:2.4.10".
  - **No default version is hard-coded** — a build's artifacts are cleaned up, so a pinned default
    would rot. `latest` (the default) asks TeamCity for the last successful build.
  - **The lookup is a `ValueSource`**, not a plain `URL.readText()`: the configuration cache
    re-evaluates it on every run, so `latest` cannot stay pinned to yesterday's build on an agent
    that reuses its checkout. It also has connect/read timeouts.
  - **An unresolvable version fails the *task*, not the configuration** (`versionProblem` is
    thrown inside `tasks.register`): `./gradlew build` (unit tests, pure offline ASM) must keep
    working without TeamCity.
  - **The repository is `exclusiveContent`-scoped** to `org.jetbrains.kotlin*` at exactly that
    version, so mavenCentral keeps serving everything else (ASM, `kotlin-test`, the build's own
    stdlib) and no lookup for that version leaks to mavenCentral.
- **`jvmToolchain(21)` is pinned.** The tool reads bytecode with ASM; "whatever JDK is on PATH"
  made local runs (JDK 26 here) and CI compile to different bytecode with a different behaviour
  surface. 21 is also what the TeamCity step runs (`jdkHome = "%env.JDK_21_0%"`).
- **The Develocity build scan is opt-in**: the plugin is declared `apply false` and applied only
  when `-PbuildScan` is present (registered in `knownProjectProperties`). Publishing on every
  build made the check depend on reaching `ge.labs.jb.gg`, which an offline/network-restricted CI
  agent cannot do. `publishing.onlyIf { }` is the obvious shape and does NOT work here — the
  plugin cannot serialize a settings-script lambda into the configuration cache (it fails the
  build with "cannot serialize Gradle script object references"), and merely leaving `server`
  unset still prints a scan-not-published notice on every run.
- **The allowlist must stay auditable: a reason per entry.** It is a plain list of qualified
  names, one per line, with `#` starting a comment; every entry must be preceded by a `#` comment
  explaining why it is a false positive. Pinned by `AllowlistTest`, which fails on an entry with
  no preceding comment. No other structure (owner, KGP version header, etc.) is enforced.
- **The banner stays minimal.** It records what was scanned, the KGP version, where its jars came
  from (`Jars from: TeamCity <build type>` — not implicit any more, now that the source is a build
  configuration and a version is its build number) and the allowlist — nothing else.
  A `Tool : <git describe>` revision line was added once for CI traceability and
  removed again: the check runs once per branching/deprecation phase and is read by developers,
  so extra header noise costs more than it gives.
- **Allowlist entries are qualified names** (`Finding.symbol`, e.g.
  `org.jetbrains.kotlin.gradle.plugin.KotlinCompilation.getDefaultSourceSetName`), not rendered
  signatures — there's no compiler involved to render one.
- **Candidate scan is parallelized via `parallelStream()`** (`Main.kt`, both passes) — safe
  because every finder/extractor/scanner is stateless over its inputs (the search index is built
  once, read-only). No Gradle daemon involved, so there's no memory-cap concern here (unlike the
  deleted resolution pass) — this is pure CPU-bound text scanning.
- **`ReflectiveCallArgExtractor`'s `maskComments` is a different function from
  `EmbeddedScriptScanner`'s `maskCommentsAndStrings`** — do not merge them. Pass 1 needs strings
  blanked (a deprecated name quoted in a string literal inside an embedded script must not
  match); pass 2 needs the opposite (the string literal IS the target name to search for). Same
  comment-stripping logic, deliberately different string handling.
- **`-PexcludePatterns` / default excludes apply to both passes** (`Main.kt` builds one
  `excludePatterns` list, passed to both `EmbeddedScriptFinder.candidates` and
  `ReflectiveCallFinder.candidates`) — an excluded test-fixture path is excluded from both scans,
  not just the embedded-script one.

## Build / test / run

```bash
./gradlew build                          # compile + unit tests (offline ASM; TeamCity not required)
./gradlew checkKgpDeprecations           # run against bundled test-monorepo fixture
./gradlew checkKgpDeprecations -PmonorepoDir=<path> -Pallowlist=<file>
```

Flags: `-Pallowlist` `-PkgpEngineVersion` `-PkgpBuildType` `-PexcludePatterns` `-PreportFile`
`-PfullIndex` `-PrgPath` `-PbuildScan`. See README.

## Repo

The tool lives inside **`kotlin-infrastructure`** (`kgp-deprecation-detector/`), next to the other
vendored tools (`publishing-utils`, `github-commands`, …). It is a standalone nested Gradle build:
its own `settings.gradle.kts` and wrapper, deliberately *not* listed in the root
`settings.gradle.kts`. The public GitHub repository it was imported from is history only — changes
made there are invisible to CI.

- Changes go to `master` by merge request; the repo-wide quality gate runs `./gradlew ktlintCheck`
  from the root, and its default patterns (`**/*.kt`, `**/*.kts`) **include this directory** — a
  missing final newline in `settings.gradle.kts` failed it once.
- The TeamCity configuration is `.teamcity/infra/subprojects/kgpDeprecationDetector/`, registered
  in `Kotlin_Service.kt`. One commit can change the tool, its allowlist and its build config; the
  run's VCS revision is the detector version.
- Settings are generated on JDK 21 (`mvn process-sources teamcity-configs:generate@kotlin-service`
  inside `.teamcity`); a newer JDK breaks the Kotlin 2.0.21 Maven compiler.
