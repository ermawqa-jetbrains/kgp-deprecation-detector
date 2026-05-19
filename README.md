# KGP Deprecation Detector

A small, dependency-light Kotlin/Gradle tool that scans a monorepo of Gradle
build scripts for usages of **deprecated Kotlin Gradle Plugin (KGP) APIs**
*before* those APIs are removed or escalated from `WARNING` to `ERROR`.

The detector compares identifiers found in `.gradle.kts` / `.gradle` files
against the `@Deprecated` annotations carried by a real KGP jar. The exact KGP
version under inspection is a parameter, so the tool can be run against current,
upcoming (`-dev` builds), or historic versions to catch regressions early.

---

## 1. Background and motivation

This project was created in response to a concrete regression observed in
IntelliJ Community:

| Step | What happened |
|------|---------------|
| 1 | IntelliJ build scripts used `defaultSourceSetName` (a KGP API) |
| 2 | KGP escalated the deprecation level of `defaultSourceSetName` to `ERROR` and later removed it |
| 3 | IntelliJ received **no actionable signal** at development time |
| 4 | Builds failed at runtime: *"Gradle: cannot create task `MainKt.main()` due to missing `defaultSourceSetName`"* |

The root cause was a missing feedback loop: there was no automated way to learn,
*before upgrading KGP or before KGP cuts a release*, that the IntelliJ
monorepo still relied on APIs that would soon disappear.

This tool closes that loop.

---

## 2. What the tool does (in one paragraph)

Given the `kotlin-gradle-plugin-X.Y.Z.jar` and `kotlin-gradle-plugin-api-X.Y.Z.jar`
(public-API artifacts ship deprecations separately from implementation) and a
directory tree of Gradle build files, the tool extracts every
`@Deprecated`-annotated symbol from both jars using ASM, then scans the build
files for identifiers that match those symbols. Results are grouped by deprecation level (`ERROR` / `WARNING` /
`HIDDEN`), pinpointed to `file:line`, and the tool exits with status `1` when
any `ERROR`-level usage is detected. This makes it suitable as a CI gate.

---

## 3. Architecture

```
┌──────────────────────────────────────┐
│  kotlin-gradle-plugin-X.Y.Z.jar      │ ◄── -PkgpVersion / -PkgpJar
│  kotlin-gradle-plugin-api-X.Y.Z.jar  │     (both jars: impl + public API)
└──────────────────┬───────────────────┘
               │
               ▼
   ┌──────────────────────────┐
   │  KgpDeprecationExtractor │   ASM ClassReader walks every .class entry
   │                          │   Reads kotlin.Deprecated(level, message,
   │                          │       replaceWith) on classes/methods/fields
   │                          │   Filters out internal/utils/impl packages
   │                          │   and Android-prefixed classes
   └──────────────┬───────────┘
                  │  List<DeprecatedSymbol>
                  ▼
   ┌──────────────────────────┐
   │   GradleFileScanner      │   Builds identifier→symbol index
   │                          │   (uses both Kotlin property name and JVM
   │                          │   getter/setter form, length ≥ 4)
   │                          │
   │   maskCommentsAndStrings │   Char-level state machine masks //, /* */,
   │                          │   "..." , """...""" , '...' , '''...'''
   │                          │   so identifiers inside comments / string
   │                          │   literals are NOT matched
   │                          │
   │                          │   Word-boundary regex per identifier;
   │                          │   dedupe by (file, line, symbol)
   │                          │   Honors caller-supplied allowlist
   └──────────────┬───────────┘
                  │  List<GradleMatch>
                  ▼
   ┌──────────────────────────┐
   │           Main           │   Groups by level → qualified name → hits
   │   (output + exit code)   │   Prints relative path, line content, ^^^
   │                          │   underline, replaceWith hint
   │                          │   Exits 1 iff any ERROR-level match
   └──────────────────────────┘
```

**Why this shape:**

- **ASM, not Kotlin reflection or kotlinx-metadata.** The jar can be inspected
  without instantiating any KGP classes (avoids classloader / classpath
  conflicts) and without depending on Kotlin metadata format quirks.
- **Identifier-only matching, not AST.** Parsing every Gradle file as a Kotlin
  AST would require Embedded Kotlin Compiler (heavyweight, multi-second
  startup) and would not help with the Groovy `.gradle` files. Identifier
  matching with comment/string masking covers the realistic majority of usages
  at a fraction of the complexity.
- **Single-binary tool.** No daemon, no plugin install — just a `JavaExec`
  Gradle task that loads the jar and walks the tree.

---

## 4. Project structure

```
kgp-deprecation-detector/
├── README.md                                       ← this file
├── LICENSE                                         ← Apache-2.0
├── build.gradle.kts                                ← plugins, deps, checkKgpDeprecations task
├── settings.gradle.kts                             ← pins Kotlin 2.1.20 (stable)
├── gradle.properties                               ← kotlin.code.style=official
├── gradlew, gradlew.bat                            ← Gradle wrapper scripts
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── .gitignore                                      ← ignores .gradle, build, .idea, .kotlin, OS junk
│
├── src/main/kotlin/org/jetbrains/kotlin/deprecations/
│   ├── Main.kt                                     ← CLI entry, output formatting, exit code
│   ├── DeprecatedSymbol.kt                         ← Data model + searchName heuristic
│   ├── GradleFileScanner.kt                        ← Directory walk + identifier match + masker
│   └── KgpDeprecationExtractor.kt                  ← ASM jar visitor for @Deprecated symbols
│
├── src/test/kotlin/org/jetbrains/kotlin/deprecations/
│   ├── DeprecatedSymbolTest.kt                     ← 8  tests: searchName / qualifiedName edge cases
│   ├── GradleFileScannerTest.kt                    ← 17 tests: real matches, FP suppression, allowlist
│   ├── GradleSourceMaskerTest.kt                   ← 10 tests: comments + strings + length preservation
│   ├── KgpDeprecationExtractorTest.kt              ← 8  tests: synthetic ASM jars, all deprecation levels, $annotations strip
│   └── DefaultSourceSetNameRegressionTest.kt       ← 1  test: end-to-end reproduction of IntelliJ bug
│
└── test-monorepo/                                  ← Plain-text fixtures, NOT a real Gradle project
    ├── build.gradle.kts                            ← Fixture A: targetHierarchy.default()
    └── defaultSourceSetName-sample/
        └── build.gradle.kts                        ← Fixture B: defaultSourceSetName (IntelliJ regression)
```

### Purpose of each file

| File | Purpose |
|------|---------|
| **`Main.kt`** | Orchestrates the pipeline. Parses positional args (`jar`, `monorepoDir`, optional `allowlist`), invokes the extractor and scanner, formats the human-readable report (level → symbol → hits → underline), and sets the process exit code (`1` on any `ERROR`-level hit). |
| **`DeprecatedSymbol.kt`** | Defines `DeprecatedSymbol` and `GradleMatch` data classes. Contains the `searchName` heuristic that maps a JVM getter/setter (`getDefaultSourceSetName`) back to its Kotlin property form (`defaultSourceSetName`) — necessary because Kotlin call sites use the property name, but the bytecode carries the JVM accessor name. |
| **`KgpDeprecationExtractor.kt`** | Uses ASM (`ClassReader`) to walk every class entry in the jar and extracts every `@kotlin.Deprecated` annotation it finds on classes, methods, and fields. Captures `level` (`WARNING` / `ERROR` / `HIDDEN`), `message`, and `replaceWith`. Excludes classes whose package contains an `internal`, `utils`, or `impl` segment, and classes whose simple name contains `Android`. |
| **`GradleFileScanner.kt`** | Walks the monorepo directory, restricting to `.gradle.kts` and `.gradle` files. Builds an index of `(regex → symbol)` pairs from the extracted symbols, with a min-length-4 identifier filter to keep noise manageable. Before matching, runs each file through `maskCommentsAndStrings`, a character-level state machine that replaces the contents of line comments, block comments, and single/double/triple-quoted strings with spaces while preserving offsets — so a match on `// foo defaultSourceSetName` no longer fires. Deduplicates matches per `(file, line, symbol)` to handle the case where both the Kotlin and JVM names match the same line. Honors a caller-supplied allowlist of symbol qualified names. |
| **`build.gradle.kts`** | Declares Kotlin JVM plugin, ASM 9.7 runtime dependency, and `kotlin("test")` test dependency. Registers the `checkKgpDeprecations` task as a `JavaExec` that wires the project parameters (`-PkgpVersion` / `-PkgpJar`, `-PmonorepoDir`, `-Pallowlist`) into the `Main.kt` CLI. The `resolveKgpJar` helper performs a Maven resolve for a given KGP coordinate using a detached configuration. |
| **`settings.gradle.kts`** | Pins the Kotlin plugin version used to compile the project (`2.1.20`, stable, available from `gradlePluginPortal()` / `mavenCentral()`). The JetBrains `kt/dev` Maven repository is registered in `build.gradle.kts` instead, so dev KGP jars (`-PkgpVersion=…-dev-…`) can still be resolved for inspection at runtime. |
| **`gradle.properties`** | Sets `kotlin.code.style=official` — affects formatter behavior in IDEs. |
| **`.gitignore`** | Excludes `.gradle/`, `build/`, IDE folders (`.idea/`, `.kotlin/`), Eclipse/NetBeans/VS Code metadata, OS junk (`.DS_Store`). |
| **Gradle wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) | Pins the Gradle version (9.4.0) used to build the project — committed so consumers do not need a system-wide Gradle install. |
| **`test-monorepo/build.gradle.kts`** | Fixture A — uses `targetHierarchy.default()`, a real KGP API that was deprecated then escalated. Used by the live `checkKgpDeprecations` task as a smoke fixture. |
| **`test-monorepo/defaultSourceSetName-sample/build.gradle.kts`** | Fixture B — reproduces the original IntelliJ regression pattern (`defaultSourceSetName`). The symbol lives on the `KotlinCompilation` interface in the `kotlin-gradle-plugin-api` jar (annotated `@Deprecated(level = ERROR)`), so the live detector flags it once `-api` is included in the scan. The synthetic regression test (`DefaultSourceSetNameRegressionTest`) provides permanent coverage independent of any specific KGP version. The directory layout also exercises the scanner's recursive walk. |
| **Test files** | See §6 ("Why we believe the project is correct"). |

---

## 5. How it works (pipeline, step by step)

1. **Resolve / load jars.** The `checkKgpDeprecations` Gradle task either resolves
   both `org.jetbrains.kotlin:kotlin-gradle-plugin:<version>` and
   `org.jetbrains.kotlin:kotlin-gradle-plugin-api:<version>` from Maven (using
   detached, non-transitive configurations so we get exactly those artifacts), or
   accepts one or more absolute paths via `-PkgpJar=<jar1>:<jar2>` (use the
   platform path separator). Both jars must be scanned: the implementation jar
   carries internal/MPP deprecations, while the `-api` jar carries deprecations
   on public interfaces such as `KotlinCompilation.defaultSourceSetName`.

2. **Extract deprecated symbols.** `KgpDeprecationExtractor` opens the jar with
   `java.util.jar.JarFile`, iterates every `.class` entry, hands each to an ASM
   `ClassReader`, and visits class-level / method-level / field-level
   `Lkotlin/Deprecated;` annotations. The annotation visitor captures
   `message`, the enum constant of `level`, and the nested `ReplaceWith.expression`.

3. **Build the search index.** For every extracted symbol, the scanner emits
   one or two search terms: the **Kotlin property name** derived from the JVM
   accessor (`getDefaultSourceSetName` → `defaultSourceSetName`), and the
   **raw JVM name** itself (so calls like `compilation.getDefaultSourceSetName()`
   are also caught). Terms shorter than 4 characters, blank terms, and the
   special method names `<init>` / `<clinit>` are filtered out. Each term is
   compiled into a word-boundary regex (`\bterm\b`). Symbols on the caller's
   allowlist are dropped before any of this.

4. **Walk and mask.** The scanner walks the supplied monorepo directory and
   processes every `.gradle.kts` and `.gradle` file. Each file's content is
   passed through `maskCommentsAndStrings`, a character-level state machine
   that replaces the *contents* of line comments, block comments, single-quoted
   strings, double-quoted strings, and triple-quoted strings of either kind
   with space characters — newlines, line counts, and column offsets are
   preserved exactly.

5. **Match and dedupe.** For each line of the masked file, the scanner tests
   every regex in the search index. On hit, it records a `GradleMatch`
   carrying the **original** line content (not the masked one) so the report
   is readable. A `(file, line, symbol)` seen-set deduplicates matches that
   arise from multiple terms of the same symbol matching the same line.

6. **Report.** `Main.kt` groups matches by deprecation level
   (`ERROR` → `WARNING` → `HIDDEN`), then by symbol qualified name, then lists
   each hit with file (relativized to the scan root), line number, the
   offending line trimmed, and a `^^^` underline pointing at the matched
   identifier. The exit code is `1` if any `ERROR`-level usage was found,
   otherwise `0`.

---

## 6. Why we believe the project is correct

### 6.1 Test strategy

The detector is verified end-to-end by **44 tests** across **5 suites**, all
passing on every run:

| Suite | Tests | Focus |
|-------|-------|-------|
| `DeprecatedSymbolTest` | 8 | `searchName` heuristic (strips `get`/`set`, preserves `is`), `qualifiedName` |
| `KgpDeprecationExtractorTest` | 8 | Synthetic jars built with ASM in-memory: class/method/field annotations, all three levels, default-level fallback, package exclusion, `$annotations` synthetic-method suffix strip |
| `GradleSourceMaskerTest` | 10 | Comment/string masking: line/block comments, double/triple/single-quoted strings, escape handling, length preservation |
| `GradleFileScannerTest` | 17 | Real matches across `.gradle.kts` / `.gradle`, recursion, deduplication, allowlist behavior, comment/string FP suppression, the one documented residual FP (no receiver awareness) |
| `DefaultSourceSetNameRegressionTest` | 1 | Permanent end-to-end reproduction of the IntelliJ regression: a synthetic KGP-shaped jar carrying a deprecated `getDefaultSourceSetName` is fed to the detector against a build script using that property; the test asserts the pipeline flags it at `ERROR` level |

The synthetic jars in the extractor and regression tests are built with ASM
`ClassWriter` and bundled into temp `.jar` files via `JarOutputStream`. No
network access, no external KGP version pinning, no flaky resolution — the
unit tests run offline in well under one second.

### 6.2 Regression test design

`DefaultSourceSetNameRegressionTest` is the load-bearing test for this
project's reason for existing. It is constructed so the detector *cannot*
regress to the original blind spot without this test failing:

- It synthesizes a jar that contains `KotlinCompilation.getDefaultSourceSetName`
  annotated with `@Deprecated(level = ERROR, message = "...", replaceWith = ...)`.
  This is the exact shape that the real KGP carried when the IntelliJ
  incident occurred.
- It writes a fixture build script with the canonical IntelliJ usage pattern.
- It runs the full extractor + scanner pipeline and asserts the resulting
  `GradleMatch` is at `ERROR` level and points at the right file/line.

The reason this test uses a *synthetic* jar rather than depending on a real
historic KGP version is to decouple the regression check from any specific
KGP coordinate. The live KGP at the time of writing still carries the
`@Deprecated(level = ERROR)` annotation on `KotlinCompilation.defaultSourceSetName`
in the `kotlin-gradle-plugin-api` jar — but if/when it is eventually removed,
the synthetic-jar test will continue to fail-stop a regression to the
original blind spot.

### 6.3 Live behavior verification

Beyond automated tests, the project has been manually verified against a real
KGP build (`2.4.0-dev-8644`). Scanning both the `kotlin-gradle-plugin` and
`kotlin-gradle-plugin-api` jars yields **1475 deprecated symbols** (302
`ERROR`, 1092 `WARNING`, 81 `HIDDEN`):

- The bundled fixtures correctly produce findings including `defaultSourceSetName`
  on `defaultSourceSetName-sample/build.gradle.kts:10` (the IntelliJ regression
  scenario, caught at `ERROR` level via the public `KotlinCompilation` interface
  in the `-api` jar), `targetHierarchy.default()` on `build.gradle.kts:3`, and
  the `kotlinOptions { … }` DSL deprecation on line 19.
- Empty input directories produce a clean `Result: OK` with exit code 0.
- The same jars fed twice produce byte-identical output (deterministic).
- The Maven-resolution path (`-PkgpVersion`) and the direct-jar path
  (`-PkgpJar`) produce identical results.

### 6.4 What "correct" does *not* mean here

Honest framing for the team:

- The tool **does not parse Kotlin/Groovy ASTs.** It cannot tell that
  `compilation.create("foo")` (Gradle DSL) is different from
  `KotlinJsCompilation.Companion.create(...)` (deprecated KGP method).
  Identifier collisions between deprecated KGP symbols and legitimate Gradle /
  Kotlin DSL methods are mitigated by the comment/string masker and the
  allowlist, but not fully eliminated.
- The tool **only sees what is still annotated in the jars it is given.** Once
  an API has been removed from both the `kotlin-gradle-plugin` and
  `kotlin-gradle-plugin-api` jars, it disappears entirely and the detector
  cannot flag it anymore. This is why the IntelliJ-style regression test uses a
  synthetic jar.
- The tool **does not know about source-set / configuration scoping.** Every
  matched identifier is reported regardless of which target / source-set it
  appears in.

These limitations are listed explicitly in §8 with the suggested remediations.

---

## 7. Usage

All commands run from the repository root. Requires JDK 17+ (Gradle's JVM toolchain auto-provisions if not present).

### 7.1 Run the test suite

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, 44 tests, 0 failures. HTML report written to
`build/reports/tests/test/index.html`.

### 7.2 Run the live detector

```bash
# Resolve KGP from Maven, scan the bundled fixtures
./gradlew checkKgpDeprecations -PkgpVersion=2.4.0-dev-8644

# Or point at jar files directly (skip Maven resolution).
# Join multiple jars with ':' on macOS/Linux, ';' on Windows (the platform path separator).
./gradlew checkKgpDeprecations \
  -PkgpJar=/abs/path/kotlin-gradle-plugin-X.Y.Z.jar:/abs/path/kotlin-gradle-plugin-api-X.Y.Z.jar

# Scan a different monorepo
./gradlew checkKgpDeprecations \
  -PkgpVersion=2.4.0-dev-8644 \
  -PmonorepoDir=/abs/path/to/some/monorepo

# Suppress acknowledged false positives via an allowlist
./gradlew checkKgpDeprecations \
  -PkgpVersion=2.4.0-dev-8644 \
  -Pallowlist=/abs/path/.kgp-allowlist
```

### 7.3 Parameters

| Parameter | Required | Default | Purpose |
|-----------|----------|---------|---------|
| `-PkgpVersion=<ver>` | one of these two | — | Resolve both `kotlin-gradle-plugin:<ver>` and `kotlin-gradle-plugin-api:<ver>` from Maven |
| `-PkgpJar=<path>[:<path>]` | one of these two | — | Use jars directly; join multiple paths with the platform path separator (`:` on macOS/Linux, `;` on Windows) |
| `-PmonorepoDir=<path>` | no | `test-monorepo` | Root to scan recursively |
| `-Pallowlist=<path>` | no | — | Text file: one symbol qualified name per line; `#` introduces a comment |

### 7.4 Exit codes

| Code | Meaning |
|------|---------|
| 0 | No `ERROR`-level usages (may still have `WARNING` / `HIDDEN`) |
| 1 | At least one `ERROR`-level usage — fail CI |

### 7.5 Allowlist format

```
# Cross-DSL collision: `getTargets` of the JS extension shares the
# `targets` identifier with the multiplatform DSL. Acknowledged FP.
org.jetbrains.kotlin.gradle.dsl.KotlinJsProjectExtension.getTargets

# One entry per line. Use the fully qualified name as reported in the
# `[LEVEL]` header of the detector output.
```

---

## 8. Output format

Annotated example from a live run against `test-monorepo`:

```
KGP deprecation check
  Inspecting: /…/kotlin-gradle-plugin-2.4.0-dev-8644-gradle813.jar
  Scanning  : /…/kgp-deprecation-detector/test-monorepo
  Allowlist : (none)

Deprecated symbols in KGP: 1315  (ERROR=175  WARNING=1065  HIDDEN=75)

Found 1 deprecated usage(s) in 1 file(s):
------------------------------------------------------------

[ERROR] org.jetbrains.kotlin.gradle.dsl.DeprecatedKotlinTargetHierarchyDsl.default
  Reason : Replace with 'kotlin.applyDefaultHierarchyTemplate'. Scheduled for removal in Kotlin 2.3.
  Hits   : 1
    build.gradle.kts:4
      targetHierarchy.default()
                      ^^^^^^^
------------------------------------------------------------
Result: FAIL — 1 ERROR-level usage(s) detected (0 WARNING, 0 HIDDEN).
```

- **Header banner** records jar, scan root, allowlist source — useful when
  output is captured by CI.
- **Symbol totals** answer "did the extractor see what it was supposed to?"
  An anomalous count (e.g. 0) signals a wrong jar (sources jar, javadoc jar)
  or a layout change in KGP.
- **Per-symbol blocks** carry the upstream `message` and `replaceWith` so the
  fix is suggested inline.
- **File:line + underline** lets the developer jump straight to the call site.
- **Result line** is the summary; the process exit code matches.

---

## 9. Known limitations and false positives

These are intentionally documented in tests as well as here, so reviewers know
what the tool can and cannot do.

| Limitation | Effect | Mitigation today | Long-term fix |
|------------|--------|------------------|---------------|
| Identifier-only matching, no receiver awareness | Common short identifiers (`name`, `targets`, `create`, `default`) match all deprecated symbols that happen to share that identifier | Allowlist; comment/string masking eliminates the majority of incidental hits | AST-based scanning (Embedded Kotlin Compiler) |
| String interpolation contents are masked | `"${defaultSourceSetName}"` will not be detected | Rare in build scripts; document | Lex `${…}` segments separately |
| Nested `/* /* */ */` block comments | The first `*/` is treated as end | Rare in build scripts | Track nesting depth in the masker |
| Detector window | Once an API is fully removed, it disappears from the jar and is no longer flagged | Permanent regression tests for known historic incidents | Combine with a static deprecation registry |
| Jar selection mistakes (sources / javadoc jar) | Silently returns 0 symbols | "Symbols in KGP" count in output makes anomaly obvious | Refuse jars with no `.class` entries, or warn |

---

## 10. Suggested CI integration

In a typical pipeline, you would:

1. Pin a target KGP version somewhere your team controls (could be the version
   used by the project, or a known "next" `-dev` build to get advance warning).
2. Maintain a `.kgp-allowlist` file in the monorepo, treated like a lint
   baseline — entries are added with explanation in commit messages and
   reviewed periodically.
3. Add a CI step:

   ```bash
   ./gradlew checkKgpDeprecations \
     -PkgpVersion=$KGP_VERSION_FOR_DEPRECATION_GATE \
     -PmonorepoDir="$MONOREPO_ROOT" \
     -Pallowlist="$MONOREPO_ROOT/.kgp-allowlist"
   ```

4. Fail the job on non-zero exit.
5. Optionally, also run the same command nightly against a forward-looking
   `-dev` KGP build to surface upcoming breakage well before adoption.

---

## 11. Future work / extension points

Roughly in increasing order of effort:

- **`WARNING`-gate flag.** Add `-PfailOnWarning` to also exit 1 on `WARNING`
  usages, for teams that want a stricter posture.
- **JSON / SARIF output.** A `-Pformat=sarif` option to emit structured
  results for ingestion by code-scanning tools (GitHub Advanced Security,
  GitLab SAST, Sonar).
- **Allowlist scoping.** Extend the allowlist format to support
  `symbol :: file-glob` to acknowledge an FP only in specific files.
- **Better receiver inference.** Lightweight heuristics (e.g. detect
  `kotlin { … }` / `tasks { … }` blocks and bias matches accordingly) before
  resorting to a full AST pass.
- **AST-based scanning.** Use Embedded Kotlin Compiler / PSI to do
  receiver-aware matching, fully eliminating cross-DSL identifier collisions.
- **IDE integration.** Wrap the detector in an inspection / plugin so
  feedback shows up in the IDE in addition to CI.

---

## 12. Quick reference

```bash
# All tests (offline, ~1s)
./gradlew test

# Live scan with bundled fixtures
./gradlew checkKgpDeprecations -PkgpVersion=2.4.0-dev-8644

# Live scan with allowlist
./gradlew checkKgpDeprecations \
  -PkgpVersion=2.4.0-dev-8644 \
  -PmonorepoDir=/path/to/your/monorepo \
  -Pallowlist=/path/to/.kgp-allowlist

# Clean state
./gradlew clean

# List Gradle tasks
./gradlew tasks
```

Project size: **~400 LOC** main source, **~700 LOC** test source, **44
tests**, zero runtime dependencies beyond ASM 9.7.
