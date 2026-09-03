# KGP Deprecation Detector

Detects usages of **deprecated Kotlin Gradle Plugin (KGP) APIs** in blind spots where the compiler cannot see through:
1. **Embedded Gradle scripts** - Groovy or Kotlin-DSL scripts hardcoded as string literals inside `.kt`/`.java` (IDE-injected init/build scripts).
2. **Reflective calls** - member names passed as string literals to `callReflective*` helpers (cross-KGP-version compat dispatch).

---

## Motivation

Real `.gradle.kts` files are resolved in-editor by IntelliJ (rendering deprecations visible). However, uncompiled string literals and runtime reflective dispatches have zero dev-time compiler signal:
- **Embedded scripts:** Never compiled; Groovy is dynamically typed. (e.g. KT-85590: removal of `KotlinCompilation.defaultSourceSetName` broke an uncompiled Groovy init string).
- **Reflective calls:** Resolved only at runtime (`instance.callReflectiveGetter("getCompilation", logger)`).

This tool restores the warning signal via **offline name-matching** against `@Deprecated` metadata read directly from KGP jars using ASM.

---

## How It Works

The detector operates in two independent passes combined with an index and a final allowlist filter:

```
 ┌─────────────────────────────────────────────────────────┐
 │               1. INDEX GENERATION (ASM)                 │
 │  KGP Jars ──► KgpDeprecationExtractor ──► Symbol Index  │
 └────────────────────────────┬────────────────────────────┘
                              │
             ┌────────────────┴────────────────┐
             ▼                                 ▼
 ┌───────────────────────┐         ┌───────────────────────┐
 │  PASS 1: EMBEDDED     │         │  PASS 2: REFLECTIVE   │
 │        SCRIPTS        │         │         CALLS         │
 ├───────────────────────┤         ├───────────────────────┤
 │ 1. Finder (.kt/.java) │         │ 1. Finder (markers)   │
 │ 2. Extractor (triple  │         │ 2. Extractor (string  │
 │    quotes & @Language)│         │    literals)          │
 │ 3. Scanner (masking & │         │ 3. Scanner (exact JVM │
 │    whole-word match)  │         │    member lookup)     │
 └───────────┬───────────┘         └───────────┬───────────┘
             │                                 │
             └────────────────┬────────────────┘
                              ▼
 ┌─────────────────────────────────────────────────────────┐
 │              COMBINE FINDINGS & FILTER                  │
 │  Combined Findings ──► Allowlist Filter ──► Severity    │
 │                        (ERROR/HIDDEN → 1, WARNING → 0)  │
 └─────────────────────────────────────────────────────────┘
```

### Pipeline Breakdown

| Phase | Component | Action / Mechanism |
| :--- | :--- | :--- |
| **1. Index Generation** | `KgpDeprecationExtractor` | Reads every `@Deprecated` declaration directly from KGP jars via ASM (no class loading or Gradle daemon). |
| **2. Pass 1 (Embedded Scripts)** | `EmbeddedScriptFinder`<br/>`EmbeddedScriptExtractor`<br/>`EmbeddedScriptScanner` | • Locates `.kt`/`.java` files using ripgrep/walk.<br/>• Extracts triple-quoted script literals (Groovy / Kotlin-DSL) and checks `@Language` tag.<br/>• Masks comments/strings and performs whole-word matching. |
| **3. Pass 2 (Reflective Calls)** | `ReflectiveCallFinder`<br/>`ReflectiveCallArgExtractor`<br/>`ReflectiveCallArgScanner` | • Locates files containing `callReflective` markers.<br/>• Extracts target name string literals (preserving string content).<br/>• Performs exact JVM member name lookup. |
| **4. Filtering & Output** | Allowlist & Exit Gate | • Combines findings from both passes.<br/>• Applies allowlist filter once.<br/>• Evaluates severity (`ERROR`/`HIDDEN` → exit 1, `WARNING` → exit 0). |

> **Note on False Positives:** Because this is name-matching rather than compiler resolution, generic names (`target`, `project`, `compilation`, etc.) can cause collisions. Use an allowlist to suppress confirmed non-issues.

---

## Usage

```bash
./gradlew checkKgpDeprecations \
    -PmonorepoDir=/path/to/monorepo \
    [-Pallowlist=/path/to/allowlist.txt] \
    [-PkgpEngineVersion=2.5.0-dev-6260] \
    [-PkgpBuildType=Kotlin_KotlinDev_Artifacts] \
    [-PexcludePatterns=/foo/,/bar/] \
    [-PreportFile=/path/to/report.txt] \
    [-PfullIndex] \
    [-PrgPath=/path/to/rg] \
    [-PtargetSymbols=name1,name2] \
    [-PbuildScan]
```

### Parameters

| Parameter | Description | Default |
| :--- | :--- | :--- |
| `monorepoDir` | Root directory to scan for `.kt`/`.java` files | `test-monorepo` |
| `allowlist` | Optional file with one deprecated-symbol qualified name per line (`#` for comments). Allowlisting one declaring class suppresses the whole grouped deprecation (all sibling classes), not just that one entry. | `(none)` |
| `kgpEngineVersion` | KGP version whose `@Deprecated` API set is indexed. A version is a **build number** of the source build (see below); `latest` takes its last successful build | `latest` |
| `kgpBuildType` | TeamCity build configuration the KGP jars are taken from | `Kotlin_KotlinDev_Artifacts` (Kotlin Dev / Artifacts, i.e. Kotlin master) |
| `excludePatterns` | Comma-separated path substrings to skip (added to built-in defaults) | Built-in test/fixture paths |
| `reportFile` | Path to write the full report to. When set, stdout only prints the banner, the scan/summary counts, and a "Report is ready" pointer - the full per-finding dump goes to this file only | `build/reports/kgp-deprecations.txt` |
| `fullIndex` | Keep `internal`/`utils`/`impl` packages and `Android*` classes in the deprecation index (more coverage, more noise) | Filtered out |
| `rgPath` | Explicit path to the `rg` executable, bypassing `PATH`. Needed on CI runners that recompute `PATH` (TeamCity's Gradle step with `jdkHome` set silently drops `PATH` prepends) | `rg` from `PATH` |
| `targetSymbols` | Comma-separated symbol names to check explicitly: for each, the report states whether it was found in the indexed KGP jar, its real usage count, and whether those usages are already allowlisted - so '0 usages' never has to be read as ambiguous between 'genuinely unused' and 'not indexed at all' | `(none)` |
| `buildScan` | Publish a Develocity build scan to `ge.labs.jb.gg`. Opt-in so an offline or network-restricted CI agent does not depend on reaching it | Not published |

### Where The KGP Jars Come From

KGP is taken from the Kotlin build that produced it, not from a published Maven repository: a deprecation phase is prepared against KGP built from Kotlin master, before it is deployed anywhere. That build publishes its whole Maven repository as one `maven.zip` artifact, and TeamCity serves files from inside an archive, so the artifact is used as a Maven repository as is:

```
https://buildserver.labs.intellij.net/guestAuth/app/rest/builds/buildType:(id:<kgpBuildType>),number:<version>,branch:default:any/artifacts/content/maven.zip!/
```

Consequences worth knowing:
- **A version is a build number** (`2.5.0-dev-6260`), so a released version such as `2.4.10` is not a valid input - the build then fails at configuration time with a link to the build configuration instead of an unresolved-dependency error.
- **No default version is hard-coded** - a build's artifacts are cleaned up over time, so any pinned default would rot; `latest` asks TeamCity for the last successful build on every run.
- **The JetBrains network is required** (guest access, no credentials). Unit tests do not need it; only `checkKgpDeprecations` resolves the jars.
- **Another branch** can be indexed with `-PkgpBuildType=<that branch's Artifacts build id>`.

`monorepoDir` is resolved against the project directory and validated at **configuration** time: a path that is not an existing directory fails the build before the JVM starts (a truncated absolute path used to be caught only after startup). The check task always runs (`outputs.upToDateWhen { false }`) - a checker must never be skipped as up-to-date.

The build pins `jvmToolchain(21)`, so the tool compiles and runs against the same JDK locally and in CI.

Unknown `-P` properties fail the build at configuration time (with a "did you mean" suggestion for near-misses). Gradle itself ignores unrecognised `-P` flags, so a typo such as `-PmonrepoDir=<path>` would otherwise silently scan the default `test-monorepo` fixture and report a clean run.

### Matching Coverage And Its Limits
Matching is by name, never by resolution - Groovy is dynamically typed, a Kotlin-DSL string literal is compiled by nothing, and a reflective target is known only at runtime. Within that:
- Reflective call sites are matched over the whole file, so a call wrapped across lines (`callReflectiveGetter(` on one line, the name on the next) is found.
- Every occurrence is reported separately, each with its own `file:line:column` and caret; identical hits at the same position are collapsed.
- **One section per deprecated API, not per declaring class.** An inherited or default member is declared in every sub-interface of a hierarchy (`kotlinOptions` in `KotlinCompile`, `KotlinJvmCompile`, `KotlinCompilation`, ...), which used to repeat one call site 20+ times. Findings are grouped and counted by member + deprecation message, the section lists the declaring classes (`Declared in: ..., +N more`), and `$DefaultImpls` copies are dropped from the index (the banner's `Synthetic` line prints how many).
- A reflective target held in a **string constant declared in the same file** (`const val`/`val`/Java `static final String`, referenced bare or qualified as `Names.GETTER`) is resolved from that file's own declarations; the reported position stays on the call site.
- **Not seen:** a target constant declared in *another* file (matching on the simple name across files would give wrong values for same-named constants), or a name built by concatenation/interpolation (undecidable without the compiler). An unresolvable identifier yields no hit rather than a guess.
- **Not seen by default:** deprecations in `internal`/`utils`/`impl` packages and `Android*` classes; the banner prints how many classes were dropped and `-PfullIndex` includes them.
- **Never seen:** deprecations outside `org.jetbrains.kotlin.*`. They belong to libraries KGP bundles, not to KGP's API, and their generic names (e.g. `kotlinx.coroutines.flow.FlowKt.merge`) match ordinary words anywhere in the monorepo. The banner's `Scope` line prints how many classes were ignored.

### Which Files Are Searched
Both passes share one candidate search (`SourceFileFinder`): a ripgrep fast path with an in-process walk fallback when `rg` is not on the PATH. The two paths are pinned to the **same** semantic - every `.kt`/`.java` file under the scan root except `.git` - so whether `rg` happens to be installed changes only how long the scan takes, never which files it reports:
- `.gitignore`/`.ignore` are **not** honoured (`--no-ignore`): a monorepo can gitignore generated-but-shipped sources; what to skip is `excludePatterns`' decision.
- Hidden files and directories **are** scanned (`--hidden`), matching the walk fallback.
- `.git` is skipped by both paths.

### Built-in Exclusions
Drops test fixtures, test sources, and known false positives:
`/testData/`, `/testdata/`, `/testResources/`, `/testSources/`, `/testSrc/`, `/test/`, `/tests/`, `/integration-tests/`, `/agpIntegrationTestSrc/`, `/resources/`, `/privacy/KotlinNotebookSystemPromptPrivacySafeWrapper.kt`, `/fleet/buildtool/bundles/helpers.kt`.

### Allowlist Rules
The curated list for the IntelliJ monorepo is `config/allowlist-intellij.txt`; it is versioned here and changed by merge request, so a suppression is reviewable instead of living on someone's machine.

An allowlist entry suppresses a finding permanently, so it must stay auditable:
- **A reason per entry.** Every entry is preceded by a `#` comment explaining why it is a false positive; an unexplained entry cannot be re-verified later. Pinned by `AllowlistTest`.
- **One entry per deprecated API.** An entry is a qualified `<class>.<member>` name, and allowlisting a single declaring class suppresses the whole grouped deprecation - every sibling class the report lists under `Declared in: ...`.
- **Never list `$DefaultImpls` classes or non-KGP packages.** Neither is indexed, so such an entry can never match anything.

For a one-off experiment, point `-Pallowlist` at a scratch file instead of editing the shared one.

### Target Symbol Check
When `-PtargetSymbols` is set, the report always includes a `TARGET SYMBOL CHECK` section, printed
before the usual findings and independent of whether there are any (unrelated) findings elsewhere:
for each requested name, `[FOUND] <requested> -> <memberName>` with its level, declaring class(es),
reason, allowlist status, and real usage count, or `[NOT FOUND] <requested>` if no such member
exists in the indexed jar(s). Matching mirrors the scanner: both the raw member name and its
getter/setter-normalized form are tried (case-insensitively), so a caller can say either
`enabledLanguageFeatures` or `getEnabledLanguageFeatures` and match the same symbol. Matching is
otherwise exact - any further fuzziness (e.g. reconciling imprecise ticket wording) is the
caller's job, not this tool's. A caller (e.g. an automation) should treat `NOT FOUND` as
inconclusive, never as a safe/ready result - it usually means the wrong KGP version was scanned,
or the API is already fully removed from it.

### Exit Codes
- **`0`** - Clean or `WARNING`-only matches (warnings reported but do not fail the build).
- **`1`** - At least one `ERROR`- or `HIDDEN`-level match found.
- **`2`** - Setup failure: the check never ran (missing/blank scan root, scan root is not a directory, allowlist file not found, no KGP jars provided, or the jars yielded an empty index). Distinct from `1` so CI can tell a broken invocation from real violations.

Gradle collapses any non-zero exit into its own generic failure, so `checkKgpDeprecations` inspects the code itself and fails with a distinguishable message:
- `1` → `KGP deprecation check FAILED: deprecated API usages found.` (When running in TeamCity, `Main` emits `##teamcity[buildStatus]` and `##teamcity[buildProblem]` instead of throwing a Gradle exception, failing the build cleanly without 100-line stack traces).
- `2` → `KGP deprecation check DID NOT RUN (setup failure, exit 2).`

---

## Running It In TeamCity

Nobody has to clone the IntelliJ monorepo to get a report: the scan runs as
**Kotlin Infrastructure → KGP Deprecation Detector → Scan IntelliJ monorepo**
(`.teamcity/infra/subprojects/kgpDeprecationDetector/`).

- **Manual runs only** - there is no trigger; press *Run* when a deprecation phase is being prepared.
- **What it scans** - the `intellij-kt-master` VCS root, checked out by TeamCity, so the report always reflects IntelliJ master.
- **KGP version** - the `kgp.engine.version` build parameter (default `latest`, the last successful build of the source build); override it in the *Run…* dialog with a build number to index a specific KGP.
- **Where KGP comes from** - the `kgp.build.type` build parameter (default `Kotlin_KotlinDev_Artifacts`); point it at a release branch's Artifacts build to index that branch instead of master.
- **Where the result is** - the build log holds the banner and the summary line; the full per-finding report is the `kgp-deprecations-report.txt` artifact, published even when the build is red.
- **Red build** - `ERROR`/`HIDDEN` findings trigger a clean `##teamcity[buildProblem]` and set `##teamcity[buildStatus]` summary, failing the build without duplicate failures or Tooling API stack traces. A setup failure (exit 2) is reported with a different message.
- **ripgrep** - a build step caches a pinned `rg` under `$HOME/.cache/kgp-detector/`, and the Gradle step passes it via `-PrgPath`; if the download fails, the scan still runs on the walk fallback.

Detector code, allowlist and build configuration live in the same repository, so one merge request changes all three, and the run's VCS revision *is* the detector version.

---

## Scope & Limits

- **Target Files:** `.kt`/`.java` files containing embedded scripts or reflective calls only. (Standalone `.gradle.kts` and direct API references are handled by the IDE / compiler).
- **Matching Mechanism:** Name-matching (not type resolution).
- **`@Language` Tag:** Authoritative in Pass 1. Non-Gradle tagged literals are ignored.
- **Reflective Matching:** Exact member-name matching.

---

## Build & Test

```bash
./gradlew build      # Compile + unit tests (pure offline ASM, no Gradle daemon or network needed)
```
