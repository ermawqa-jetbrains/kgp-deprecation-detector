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
   implicit `Project` receiver, and the **sam-with-receiver** plugin (so implicit
   accessor blocks like `kotlin { jvm { withJava() } }` resolve exactly as in Gradle).
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
    [-PkgpEngineVersion=2.2.20]
```

- **`monorepoDir`** — root to scan for `.gradle.kts` (default: `test-monorepo`).
- **`allowlist`** — optional file; one deprecated-symbol signature per line, `#` comments.
  A finding whose signature is listed is suppressed.
- **`gradleInstallation`** — optional; defaults to each project's own Gradle wrapper.
- **`kgpEngineVersion`** — analysis compiler version (build-time). **Must be ≥ the KGP
  version used in the scanned monorepo**, or KGP classes "compiled with a newer Kotlin"
  cannot be read. Default `2.2.20`.

**Exit code:** `1` if any `ERROR`-level deprecation is found, else `0`. Output groups
findings by level (`ERROR` → `WARNING` → `HIDDEN`) with a caret under each usage.

## Scope and limits

- **`.gradle.kts` only.** Groovy `.gradle` is out of scope (resolution needs the Kotlin
  frontend).
- **All resolved deprecations, not KGP-only.** Findings come from compiler diagnostics,
  which don't carry the symbol's package; in practice Kotlin build scripts are
  KGP-dominant. Filtering strictly to KGP packages would require descriptor access.
- **Receiver is `Project`.** `settings.gradle.kts` / `init.gradle.kts` use different
  receivers and are not yet special-cased.
- **`UNRESOLVED` scripts.** If a script already uses an `ERROR`-level deprecated API,
  Gradle cannot model it (its own compile fails); such scripts are counted as
  `UNRESOLVED` and reported as warnings, not silently dropped.
- Driving Gradle per project is the cost of correctness; results are cacheable by Gradle.

## Build & test

```bash
./gradlew build      # compile + unit tests (no Gradle/network needed)
```

Unit tests resolve a synthetic classpath (no Gradle), including the no-false-positive
case. The end-to-end Tooling-API test is opt-in:

```bash
KGP_IT_PROJECT=/path/to/kmp-project [KGP_IT_GRADLE=/path/to/gradle] ./gradlew test
```
