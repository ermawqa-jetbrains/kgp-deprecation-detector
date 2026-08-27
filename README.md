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
    [-PkgpEngineVersion=2.4.20-dev-5677] \
    [-PexcludePatterns=/foo/,/bar/] \
    [-PreportFile=/path/to/report.txt] \
    [-PfullIndex] \
    [-PbuildScan]
```

### Parameters

| Parameter | Description | Default |
| :--- | :--- | :--- |
| `monorepoDir` | Root directory to scan for `.kt`/`.java` files | `test-monorepo` |
| `allowlist` | Optional file with one deprecated-symbol qualified name per line (`#` for comments) | `(none)` |
| `kgpEngineVersion` | KGP version whose `@Deprecated` API set is indexed | `2.4.10` |
| `excludePatterns` | Comma-separated path substrings to skip (added to built-in defaults) | Built-in test/fixture paths |
| `reportFile` | Path to mirror stdout/stderr output for CI artifacts | `build/reports/kgp-deprecations.txt` |
| `fullIndex` | Keep `internal`/`utils`/`impl` packages and `Android*` classes in the deprecation index (more coverage, more noise) | Filtered out |
| `buildScan` | Publish a Develocity build scan to `ge.labs.jb.gg`. Opt-in so an offline or network-restricted CI agent does not depend on reaching it | Not published |

`monorepoDir` is resolved against the project directory and validated at **configuration** time: a path that is not an existing directory fails the build before the JVM starts (a truncated absolute path used to be caught only after startup). The check task always runs (`outputs.upToDateWhen { false }`) - a checker must never be skipped as up-to-date.

The build pins `jvmToolchain(17)`, so the tool compiles and runs against the same JDK locally and in CI.

Unknown `-P` properties fail the build at configuration time (with a "did you mean" suggestion for near-misses). Gradle itself ignores unrecognised `-P` flags, so a typo such as `-PmonrepoDir=<path>` would otherwise silently scan the default `test-monorepo` fixture and report a clean run.

### Matching Coverage And Its Limits
Matching is by name, never by resolution - Groovy is dynamically typed, a Kotlin-DSL string literal is compiled by nothing, and a reflective target is known only at runtime. Within that:
- Reflective call sites are matched over the whole file, so a call wrapped across lines (`callReflectiveGetter(` on one line, the name on the next) is found.
- Every occurrence is reported separately, each with its own `file:line:column` and caret; identical hits at the same position are collapsed.
- A reflective target held in a **string constant declared in the same file** (`const val`/`val`/Java `static final String`, referenced bare or qualified as `Names.GETTER`) is resolved from that file's own declarations; the reported position stays on the call site.
- **Not seen:** a target constant declared in *another* file (matching on the simple name across files would give wrong values for same-named constants), or a name built by concatenation/interpolation (undecidable without the compiler). An unresolvable identifier yields no hit rather than a guess.
- **Not seen by default:** deprecations in `internal`/`utils`/`impl` packages and `Android*` classes; the banner prints how many classes were dropped and `-PfullIndex` includes them.

### Which Files Are Searched
Both passes share one candidate search (`SourceFileFinder`): a ripgrep fast path with an in-process walk fallback when `rg` is not on the PATH. The two paths are pinned to the **same** semantic - every `.kt`/`.java` file under the scan root except `.git` - so whether `rg` happens to be installed changes only how long the scan takes, never which files it reports:
- `.gitignore`/`.ignore` are **not** honoured (`--no-ignore`): a monorepo can gitignore generated-but-shipped sources; what to skip is `excludePatterns`' decision.
- Hidden files and directories **are** scanned (`--hidden`), matching the walk fallback.
- `.git` is skipped by both paths.

### Built-in Exclusions
Drops test fixtures, test sources, and known false positives:
`/testData/`, `/testdata/`, `/testResources/`, `/testSources/`, `/testSrc/`, `/test/`, `/tests/`, `/integration-tests/`, `/agpIntegrationTestSrc/`, `/resources/`, `/privacy/KotlinNotebookSystemPromptPrivacySafeWrapper.kt`, `/fleet/buildtool/bundles/helpers.kt`.

### Allowlist Rules
An allowlist entry suppresses a finding permanently, so it must stay auditable:
- **A reason per entry.** Every entry is preceded by a `#` comment explaining why it is a false positive; an unexplained entry cannot be re-verified later. Pinned by `AllowlistTest`.
- **A declared KGP version.** The file records `# kgp-version: <ver>` - the index the entries were curated against. The banner prints a note when it differs from `-PkgpEngineVersion` (or when the header is missing), because a KGP bump can turn a former false positive into a silenced real violation. Re-review the entries and update the header when the version moves.

### Exit Codes
- **`0`** - Clean or `WARNING`-only matches (warnings reported but do not fail the build).
- **`1`** - At least one `ERROR`- or `HIDDEN`-level match found.
- **`2`** - Setup failure: the check never ran (missing/blank scan root, scan root is not a directory, allowlist file not found, no KGP jars provided, or the jars yielded an empty index). Distinct from `1` so CI can tell a broken invocation from real violations.

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
