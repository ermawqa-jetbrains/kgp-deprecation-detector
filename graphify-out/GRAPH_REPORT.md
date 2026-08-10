# Graph Report - .  (2026-08-10)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 143 nodes · 234 edges · 19 communities (11 shown, 8 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 13 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `e13ee60c`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- AnnotationVisitor
- Main.kt
- ReflectiveCallArg
- KgpDeprecationExtractorTest
- EmbeddedScriptExtractorTest
- EmbeddedScriptScannerTest
- ReflectiveCallArgExtractorTest
- EmbeddedScriptExtractor
- EmbeddedScriptFinder
- ReflectiveCallFinder
- gradlew
- RipgrepDetector
- RipgrepDetectorTest
- ReflectiveKgpAccess.kt

## God Nodes (most connected - your core abstractions)
1. `KgpDeprecationExtractorTest` - 15 edges
2. `EmbeddedScriptExtractorTest` - 12 edges
3. `AnnotationVisitor` - 11 edges
4. `DeprecatedSymbol` - 9 edges
5. `DeprecationAnnotationVisitor` - 9 edges
6. `EmbeddedScriptScannerTest` - 9 edges
7. `ReflectiveCallArgExtractorTest` - 9 edges
8. `ReflectiveCallArg` - 8 edges
9. `ReflectiveCallArgScanner` - 8 edges
10. `DeprecationSpec` - 8 edges

## Surprising Connections (you probably didn't know these)
- `EmbeddedScriptScannerTest` --calls--> `DeprecatedSymbol`  [INFERRED]
  src/test/kotlin/org/jetbrains/kotlin/deprecations/EmbeddedScriptScannerTest.kt → src/main/kotlin/org/jetbrains/kotlin/deprecations/DeprecatedSymbol.kt
- `main()` --calls--> `EmbeddedScriptScanner`  [INFERRED]
  src/main/kotlin/org/jetbrains/kotlin/deprecations/Main.kt → src/main/kotlin/org/jetbrains/kotlin/deprecations/EmbeddedScriptScanner.kt
- `main()` --calls--> `ReflectiveCallArgScanner`  [INFERRED]
  src/main/kotlin/org/jetbrains/kotlin/deprecations/Main.kt → src/main/kotlin/org/jetbrains/kotlin/deprecations/ReflectiveCallArgScanner.kt
- `EmbeddedScriptScanner` --references--> `DeprecatedSymbol`  [EXTRACTED]
  src/main/kotlin/org/jetbrains/kotlin/deprecations/EmbeddedScriptScanner.kt → src/main/kotlin/org/jetbrains/kotlin/deprecations/DeprecatedSymbol.kt
- `ReflectiveCallArgScanner` --references--> `DeprecatedSymbol`  [EXTRACTED]
  src/main/kotlin/org/jetbrains/kotlin/deprecations/ReflectiveCallArgScanner.kt → src/main/kotlin/org/jetbrains/kotlin/deprecations/DeprecatedSymbol.kt

## Import Cycles
- None detected.

## Communities (19 total, 8 thin omitted)

### Community 0 - "AnnotationVisitor"
Cohesion: 0.16
Nodes (9): ClassVisitor, DeprecatedSymbol, DeprecationAnnotationVisitor, AnnotationVisitor, DeprecationClassVisitor, FieldVisitor, MethodVisitor, isExcluded() (+1 more)

### Community 1 - "Main.kt"
Cohesion: 0.17
Nodes (14): OutputStream, DeprecationLevel, ERROR, HIDDEN, WARNING, Finding, ByteArray, loadAllowlist() (+6 more)

### Community 2 - "ReflectiveCallArg"
Cohesion: 0.25
Nodes (5): maskComments(), ReflectiveCallArg, ReflectiveCallArgExtractor, ReflectiveCallArgScanner, ReflectiveCallArgScannerTest

### Community 3 - "KgpDeprecationExtractorTest"
Cohesion: 0.36
Nodes (4): DeprecationSpec, KgpDeprecationExtractorTest, ByteArray, MethodSpec

### Community 5 - "EmbeddedScriptScannerTest"
Cohesion: 0.18
Nodes (3): EmbeddedScriptScanner, maskCommentsAndStrings(), EmbeddedScriptScannerTest

### Community 10 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **4 isolated node(s):** `WARNING`, `ERROR`, `HIDDEN`, `LinkTaskReflection`
  These have ≤1 connection - possible missing edges or undocumented components.
- **8 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `DeprecatedSymbol` connect `AnnotationVisitor` to `ReflectiveCallArg`, `EmbeddedScriptScannerTest`?**
  _High betweenness centrality (0.201) - this node is a cross-community bridge._
- **Why does `AnnotationVisitor` connect `AnnotationVisitor` to `KgpDeprecationExtractorTest`?**
  _High betweenness centrality (0.125) - this node is a cross-community bridge._
- **Why does `ReflectiveCallArgScanner` connect `ReflectiveCallArg` to `AnnotationVisitor`, `Main.kt`?**
  _High betweenness centrality (0.081) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `DeprecatedSymbol` (e.g. with `EmbeddedScriptScannerTest` and `.symbol()`) actually correct?**
  _`DeprecatedSymbol` has 2 INFERRED edges - model-reasoned connections that need verification._
- **What connects `WARNING`, `ERROR`, `HIDDEN` to the rest of the system?**
  _4 weakly-connected nodes found - possible documentation gaps or missing edges._