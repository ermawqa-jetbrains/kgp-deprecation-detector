# KGP Deprecation Detector

Detects usages of **deprecated Kotlin Gradle Plugin (KGP) APIs** in code paths the compiler cannot check directly:
1. **Embedded Gradle scripts**: Groovy or Kotlin-DSL scripts hardcoded as string literals inside `.kt`/`.java` files (IDE init/build scripts).
2. **Reflective calls**: Member names passed as strings to `callReflective*` compatibility helpers.

> **Out of Scope:** Direct `.kt`/`.java` code references and standalone `.gradle.kts` files are already checked by the Kotlin compiler and IDE.

---

## Quick Start

Run the detector against a target repository or directory using Gradle:

```bash
./gradlew checkKgpDeprecations \
    -PmonorepoDir=/path/to/monorepo \
    [-PkgpEngineVersion=latest]
```

*(Running `./gradlew checkKgpDeprecations` with no arguments runs against the bundled `test-monorepo` fixture).*

---

## How It Works

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

1. **Bytecode Indexing (ASM)**: Reads `@Deprecated` annotations directly from KGP jars (scoped to `org.jetbrains.kotlin.*`, skipping synthetic `$DefaultImpls` and internal classes).
2. **Embedded Script Scanner**: Finds `.kt`/`.java` script string literals (tagged with `@Language` or matching Gradle DSL patterns) and searches for deprecated member names.
3. **Reflective Call Scanner**: Extracts member names passed to `callReflective*` calls and matches them against the index.
4. **Allowlist & Deduplication**: Groups findings by `(member, message)` to avoid repetitive reports across class hierarchies, and filters against the configured allowlist.

---

## Parameters Reference

| Parameter | Description | Default |
| :--- | :--- | :--- |
| `-PmonorepoDir` | Target directory to scan (`.kt`/`.java` files) | `test-monorepo` |
| `-PkgpEngineVersion` | KGP version from Maven Central (e.g. `2.4.10`, `2.1.20`) | `2.4.10` |
| `-PlocalRepoDir` | Path to local repository directory containing KGP artifacts (used by TeamCity) | `(none)` |
| `-PtargetSymbols` | Comma-separated symbol names to audit explicitly (verifies if present in jar, usages, allowlist state) | `(none)` |
| `-PexcludePatterns` | Additional comma-separated path substrings to exclude | `(none)` |

---

## Allowlist & Suppressions

The default allowlist file (`config/allowlist-intellij.txt`) suppresses known false positives or accepted usages.

### Rules
- **Reason per entry**: Every entry must have a `#` comment above it explaining why it is a false positive or permitted usage (enforced by `AllowlistTest`).
- **Group suppression**: Suppressing one declaring class (`org.jetbrains.kotlin.gradle.dsl.KotlinCompile.kotlinOptions`) automatically suppresses all sibling classes sharing that logical deprecation.
- **KGP scope only**: Do not list `$DefaultImpls` classes or non-KGP third-party packages (they are never indexed).

---

## Target Symbol Check

When `-PtargetSymbols=name1,name2` is provided, the report prepends a `TARGET SYMBOL CHECK` section:
- States `[FOUND]` or `[NOT FOUND]` in the indexed KGP jar.
- Displays the real usage count and allowlist suppression status.
- Matches both raw member names and getter/setter-normalized names (`enabledLanguageFeatures` $\leftrightarrow$ `getEnabledLanguageFeatures`).

---

## Exit Codes

| Code | Meaning | Outcome |
| :---: | :--- | :--- |
| **`0`** | Clean or `WARNING`-only matches | Success |
| **`1`** | `ERROR` or `HIDDEN` deprecations found | Fails build (emits clean `##teamcity[buildProblem]` on CI) |
| **`2`** | Setup failure (missing directory, no jars) | Execution aborted |

---

## Development & Tests

```bash
./gradlew test    # Run unit tests (offline ASM, fast)
./gradlew check   # Run tests and verification checks
```
