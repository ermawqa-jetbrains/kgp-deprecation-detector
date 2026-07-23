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

1. **Index:** Reads every `@Deprecated` declaration out of KGP jars via ASM (no class loading or Gradle daemon required).
2. **Pass 1 - Embedded Scripts** (`EmbeddedScriptFinder` → `EmbeddedScriptExtractor` → `EmbeddedScriptScanner`):
   - Locates `.kt`/`.java` files under scan root (`ripgrep` pre-filter with walk fallback).
   - Extracts triple-quoted script literals (Groovy or Kotlin-DSL).
   - Skips literals explicitly tagged with non-Gradle `@Language("X")` (e.g. `@Language("Markdown")`).
   - Masks comments/strings and performs whole-word matching of deprecated names against literal text.
3. **Pass 2 - Reflective Calls** (`ReflectiveCallFinder` → `ReflectiveCallArgExtractor` → `ReflectiveCallArgScanner`):
   - Locates `.kt`/`.java` files containing `callReflective*` markers.
   - Extracts string-literal target names (comment-aware; strings preserved since the literal *is* the search target).
   - Performs exact JVM member name lookup against the index.
4. **Filtering & Output:** Combines findings from both passes, applies the allowlist once, and groups results by severity (`ERROR` → `HIDDEN` → `WARNING`).

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

### Built-in Exclusions
Drops test fixtures, test sources, and known false positives:
`/testData/`, `/testdata/`, `/testResources/`, `/testSources/`, `/testSrc/`, `/test/`, `/tests/`, `/integration-tests/`, `/agpIntegrationTestSrc/`, `/resources/`, `/privacy/KotlinNotebookSystemPromptPrivacySafeWrapper.kt`, `/fleet/buildtool/bundles/helpers.kt`.

### Exit Codes
- **`0`** - Clean or `WARNING`-only matches (warnings reported but do not fail the build).
- **`1`** - At least one `ERROR`- or `HIDDEN`-level match found.

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
