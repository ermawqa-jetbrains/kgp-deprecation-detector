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
    [-PreportFile=/path/to/report.txt]
```

### Parameters

| Parameter | Description | Default |
| :--- | :--- | :--- |
| `monorepoDir` | Root directory to scan for `.kt`/`.java` files | `test-monorepo` |
| `allowlist` | Optional file with one deprecated-symbol qualified name per line (`#` for comments) | `(none)` |
| `kgpEngineVersion` | KGP version whose `@Deprecated` API set is indexed | `2.4.0` |
| `excludePatterns` | Comma-separated path substrings to skip (added to built-in defaults) | Built-in test/fixture paths |
| `reportFile` | Path to mirror stdout/stderr output for CI artifacts | `build/reports/kgp-deprecations.txt` |

Unknown `-P` properties fail the build at configuration time (with a "did you mean" suggestion for near-misses). Gradle itself ignores unrecognised `-P` flags, so a typo such as `-PmonrepoDir=<path>` would otherwise silently scan the default `test-monorepo` fixture and report a clean run.

### Built-in Exclusions
Drops test fixtures, test sources, and known false positives:
`/testData/`, `/testdata/`, `/testResources/`, `/testSources/`, `/testSrc/`, `/test/`, `/tests/`, `/integration-tests/`, `/agpIntegrationTestSrc/`, `/resources/`, `/privacy/KotlinNotebookSystemPromptPrivacySafeWrapper.kt`, `/fleet/buildtool/bundles/helpers.kt`.

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
