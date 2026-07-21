# KGP Deprecation Detector

Detects usages of **deprecated Kotlin Gradle Plugin (KGP) APIs** inside **embedded Gradle
scripts** - Groovy or Kotlin-DSL scripts hardcoded as string literals inside `.kt`/`.java`
(IDE-injected init/build scripts) - *before* those APIs are removed.

## Motivation

Real `.gradle.kts` files are resolved in-editor by IntelliJ, so their deprecation warnings are
already visible to developers. **Embedded scripts are not**: they are string literals, never
compiled by anything. Groovy is dynamically typed, so no frontend could resolve one even if it
tried. This blind spot is exactly what caused a real incident in KT-85590: KGP escalated
`KotlinCompilation.defaultSourceSetName` and removed it, breaking a Gradle init script that had
been hardcoded as a Groovy string inside a `.kt` file - with zero dev-time signal, because the
string was never compiled or type-checked by anything. This tool restores that signal by
name-matching against the deprecated-API index read from the KGP jars.

## How it works

1. **Index.** Read every `@Deprecated` declaration out of the KGP jars via ASM (no class
   loading) - this is the name index to match against.
2. **Find candidates.** Locate `.kt`/`.java` files under the scan root that look like they embed
   a Gradle script (`ripgrep` pre-filter, in-process fallback).
3. **Extract.** Pull triple-quoted string literals that look like Gradle scripts (Groovy *or*
   Kotlin-DSL idioms) out of each candidate. A literal explicitly tagged `@Language("X")` for
   some non-Gradle language (e.g. `@Language("Markdown")`) is skipped even if its content happens
   to contain a Gradle-ish keyword - this is what keeps a documentation string showing a
   `plugins { }` code sample from being scanned.
4. **Match.** Mask out comments/string literals, then whole-word match the deprecated names
   against the literal's text, remapping positions back to the host `.kt`/`.java` file.

This is pure offline text matching - no Gradle, no daemon, no network beyond the initial KGP
jar download. A full monorepo scan runs in seconds to low minutes, not the tens of minutes a
Gradle-driven resolution pass would take.

**This is name-matching, not resolution - it has false positives.** A deprecated KGP name and an
unrelated same-named symbol are indistinguishable by text alone, and generic names (`target`,
`project`, `compilation`, …) match broadly. That is the inherent cost of covering scripts that
are never compiled by anything. Use the allowlist to silence confirmed non-issues.

## Usage

```bash
./gradlew checkKgpDeprecations \
    -PmonorepoDir=/path/to/monorepo \
    [-Pallowlist=/path/to/allowlist.txt] \
    [-PkgpEngineVersion=2.4.20-dev-5677] \
    [-PexcludePatterns=/foo/,/bar/] \
    [-PreportFile=/path/to/report.txt]
```

- **`monorepoDir`** - root to scan for embedded scripts in `.kt`/`.java` (default: `test-monorepo`).
- **`allowlist`** - optional file; one deprecated-symbol *qualified name* per line, `#` comments.
  A finding whose symbol is listed is suppressed.
- **`kgpEngineVersion`** - the KGP version whose `@Deprecated` API set is indexed (default
  `2.4.0`). Set this to the KGP version used by the scanned monorepo. Dev versions resolve via
  the bundled JetBrains `kt/dev` repo.
- **`excludePatterns`** - comma-separated path substrings to skip, *added on top of* the built-in
  defaults (`/testData/`, `/testdata/`, `/testResources/`, `/testSources/`, `/testSrc/`, `/test/`,
  `/tests/`, `/integration-tests/`, `/agpIntegrationTestSrc/`, `/resources/`, plus two specific
  files that are confirmed non-script false positives) which drop test-fixture, test-source, and
  known-noise files.
- **`reportFile`** - mirrors everything printed to stdout/stderr into a file too, so a run is a
  self-contained CI artifact. **On by default**, written to `build/reports/kgp-deprecations.txt`;
  override the path with `-PreportFile=<path>`. Terminal output is unaffected - this is
  additive, not a replacement.

Example banner + summary:

```
KGP deprecation check (embedded scripts)
  Scanning : …/test-monorepo
  KGP      : 2.4.0 (1790 deprecated symbol(s) indexed)
  Allowlist: (none)
  Report   : …/build/reports/kgp-deprecations.txt

Scanned 2 candidate file(s).

Result: OK - no ERROR/HIDDEN-level matches (3 WARNING noted).
```

**Exit codes:**
- `0` - no `ERROR`- or `HIDDEN`-level matches (`WARNING` matches are reported but don't fail).
- `1` - at least one `ERROR`- or `HIDDEN`-level match.

Output groups findings by level (`ERROR` → `HIDDEN` → `WARNING`) with a caret under each usage.

## Scope and limits

- **Embedded scripts only, in `.kt`/`.java`.** Standalone `.gradle`/`.gradle.kts` files are out
  of scope - real `.gradle.kts` is already resolved in-editor by IntelliJ, so it has a signal
  already; this tool exists for the case that has none.
- **Name-matching, not resolution.** See *How it works* above - this is unavoidable for scripts
  that are never compiled.
- **`@Language` tag is authoritative when present.** A literal tagged for a non-Gradle language
  is never scanned, even if its content happens to contain a Gradle-ish marker word. An untagged
  literal falls back to marker-based detection.

## Build & test

```bash
./gradlew build      # compile + unit tests (no Gradle/network needed)
```

Unit tests are pure - no Gradle, no network, no class loading (the KGP-jar reader uses ASM
directly on bytecode).
