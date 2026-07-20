# KGP Deprecation Detector

Detects usages of **deprecated Kotlin Gradle Plugin (KGP) APIs** in a monorepo of
Kotlin Gradle build scripts (`.gradle.kts`) — *before* those APIs are removed or
escalated from `WARNING` to `ERROR`.

It resolves each build script the way Gradle/IntelliJ do (real classpath, generated
accessors, implicit `Project` receiver) and reports the Kotlin compiler's own
`DEPRECATION` diagnostics. Every finding is therefore compiler-verified: **no false
positives** — a user symbol named like a deprecated KGP one is never mis-flagged.

## Motivation

KGP escalated `KotlinCompilation.defaultSourceSetName` to `ERROR` and removed APIs
with no dev-time signal in monorepos that build with Bazel (which never compiles the
`.gradle.kts`, so the compiler warning never fires). This tool restores that signal:
run it in CI to catch deprecated KGP usage while it is still a warning.

## How it works

For each `.gradle.kts`:

1. **Gradle Tooling API** fetches the script's `KotlinBuildScriptModel` — the real
   classpath (including the per-project *generated accessors* that back `kotlin { }`,
   `compilations`, …) and Gradle's implicit imports.
2. The script is compiled with the **Kotlin scripting host** using that classpath, an
   implicit `Project` receiver, and the same compiler plugins Gradle applies —
   **sam-with-receiver** (so implicit accessor blocks like `kotlin { jvm { withJava() } }`
   resolve) and **assignment** (so lazy-property assignment like `jvmTarget = …` resolves).
   The leading `plugins { }` block is skipped (Gradle compiles it separately).
3. The compiler's `DEPRECATION` / `DEPRECATION_ERROR` diagnostics become findings, each
   with the exact `file:line:column`, the deprecated declaration's signature, and the
   compiler's message.

## Usage

```bash
./gradlew checkKgpDeprecations \
    -PmonorepoDir=/path/to/monorepo \
    [-Pallowlist=/path/to/allowlist.txt] \
    [-PgradleInstallation=/path/to/gradle] \
    [-PkgpEngineVersion=2.4.20-dev-5677] \
    [-PexcludePatterns=/foo/,/bar/] \
    [-PallowUnresolved] \
    [-PreportFile=/path/to/report.txt]
```

- **`monorepoDir`** — root to scan for `.gradle.kts` (default: `test-monorepo`).
- **`allowlist`** — optional file; one deprecated-symbol signature per line, `#` comments.
  A finding whose signature is listed is suppressed.
- **`gradleInstallation`** — optional; defaults to each project's own Gradle wrapper.
- **`excludePatterns`** — comma-separated path substrings to skip, *added on top of* the
  built-in defaults (`/testData/`, `/testdata/`, `/testResources/`, `/testSources/`,
  `/testSrc/`, `/tests/`, `/integration-tests/`, `/agpIntegrationTestSrc/`, `/resources/`)
  which drop test-fixture scripts that are never standalone-buildable projects.
- **`allowUnresolved`** — downgrade unanalysable scripts from a failure to a warning
  (see exit codes below).
- **`kgpEngineVersion`** — analysis compiler version (build-time, default `2.4.0`). **Must be
  ≥ the KGP version used in the scanned monorepo**, or KGP classes "compiled with a newer
  Kotlin" cannot be read. Dev versions resolve via the bundled JetBrains `kt/dev` repo.
- **`reportFile`** — mirrors everything printed to stdout/stderr into a file too, so a run is a
  self-contained CI artifact. **On by default**, written to `build/reports/kgp-deprecations.txt`;
  override the path with `-PreportFile=<path>`. Terminal output is unaffected — this is
  additive, not a replacement.

The run prints the **analysis engine version** and the **KGP version(s)** detected in the
scanned scripts (parsed from each script's classpath), and warns if the engine is older
than a scanned KGP version:

```
KGP deprecation check
  Scanning : …/test-monorepo
  Scripts  : 1 .gradle.kts file(s)
  Engine   : Kotlin 2.4.0 (analysis compiler)
  Allowlist: (none)

KGP version(s) in scanned scripts: 2.4.0
```

**Exit codes:**
- `0` — no `ERROR`-level deprecations (any `WARNING`/`HIDDEN` are reported but don't fail).
- `1` — at least one `ERROR`-level deprecation.
- `2` — one or more scripts could not be analysed (`UNRESOLVED`); coverage is incomplete.
  Pass `-PallowUnresolved` to treat these as warnings and return `0` instead.

Output groups findings by level (`ERROR` → `WARNING` → `HIDDEN`) with a caret under each usage.

## Scope and limits

- **`.gradle.kts` is resolved; Groovy is heuristic.** The resolution pass (zero false
  positives) covers `.gradle.kts` only — full resolution needs the Kotlin frontend. Groovy
  scripts are covered by a separate, non-gating name-matching pass (see *Groovy heuristic
  pass* below), because Groovy is dynamically typed and cannot be resolved.
- **All resolved deprecations, not KGP-only.** Findings come from compiler diagnostics,
  which don't carry the symbol's package; in practice Kotlin build scripts are
  KGP-dominant. Filtering strictly to KGP packages would require descriptor access.
- **Project build scripts only.** `settings.gradle.kts` / `init.gradle.kts` use a different
  receiver (`Settings` / `Gradle`) and are skipped; the implicit receiver is `Project`.
- **`UNRESOLVED` scripts.** Resolution needs Gradle to compile the script; if it does not
  compile (a real syntax/reference error, or it already uses an `ERROR`-level deprecated
  API that breaks Gradle config), no model — and no partial result — is produced. Such
  scripts are reported as `UNRESOLVED` and, by default, **fail the run (exit 2)** so
  incomplete coverage is never a silent pass; `-PallowUnresolved` downgrades that to a warning.
- **No settings root / no wrapper → fast-skipped as `UNRESOLVED`.** Two cheap pre-checks skip
  scripts that can never be configured, *without* booting Gradle (no per-script distribution
  download): (a) no `settings.gradle(.kts)` in any ancestor up to `monorepoDir` — a subproject
  of a build whose settings are not checked in (e.g. a Bazel-driven composite, or a script
  using `project(":…")`/convention plugins it cannot see in isolation); (b) a settings root
  with no `gradle/wrapper/gradle-wrapper.properties` (and no `-PgradleInstallation`), which
  would otherwise make Gradle download a default distribution and fail.
- **Isolation is per Gradle build (root), not per script.** Scripts in *different* roots are
  independent — a broken one only marks itself `UNRESOLVED`, others still report. But
  *subprojects of one build* share configuration: a single broken subproject script aborts
  that build's configuration, so its siblings also become `UNRESOLVED` (this is inherent —
  Gradle itself can't configure the build either).
- Driving Gradle per project is the cost of correctness; results are cacheable by Gradle.

## Performance

Scripts are resolved and analysed in **parallel**, on a pool bounded to
`min(available CPUs, 8)`. The cap protects Gradle daemon memory — each script's Tooling API
connection can spin up its own daemon (~0.5-1 GB), so unbounded fan-out risks exhausting RAM
before it helps wall-clock time. Results are identical to a serial run (each script's
resolution + analysis is fully independent; findings/versions/unresolved-count are merged
after all scripts complete, not mutated concurrently). The Groovy heuristic pass's file scan
is parallelized the same way, since it's pure text matching against a read-only index.

## Groovy heuristic pass

KGP deprecations also appear in **Groovy** scripts — IDE-injected Gradle init scripts hardcoded
as string literals inside `.kt`/`.java`, and standalone `.gradle` files. Groovy is **dynamically
typed**, so `compilation.defaultSourceSetName` has no static receiver and **no frontend can resolve
it** (Gradle only warns at runtime). The resolution pass above therefore cannot see these.

A separate, opt-out **heuristic pass** covers them by **name matching** (the deprecated-API names are
read from the KGP jars with ASM). It runs by default and:

- reports its findings in a clearly-labelled `HEURISTIC` section, **separate** from the resolved
  findings, and **never changes the exit code** (so the zero-false-positive CI gate stays clean);
- discovers files fast via ripgrep (`rg` on the `PATH` is recommended; falls back to an in-process
  walk), scanning `.kt`/`.java`/`.gradle` under `-PgroovyScanRoot` (default: `monorepoDir`).

Because it is name-matching (not resolution), it **has false positives** — a deprecated KGP name and
an unrelated same-named symbol are indistinguishable, and generic names (`project`, `target`, …) match
broadly. That is the inherent cost of covering dynamic Groovy; treat the section as "review required",
and use the allowlist to silence confirmed non-issues.

Flags:
- **`-PscanGroovy=false`** — disable the pass entirely.
- **`-PgroovyGating`** — make `ERROR`-level heuristic matches also fail the run (exit 1). Off by default.
- **`-PgroovyScanRoot=<path>`** — scan a different root than `monorepoDir`.

## Build & test

```bash
./gradlew build      # compile + unit tests (no Gradle/network needed)
```

Unit tests resolve a synthetic classpath (no Gradle), including the no-false-positive
case. The end-to-end Tooling-API test is opt-in:

```bash
KGP_IT_PROJECT=/path/to/kmp-project [KGP_IT_GRADLE=/path/to/gradle] ./gradlew test
```
