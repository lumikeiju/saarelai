---
description: Improve Node component instructions
applyTo: "**"
---

# Improve Node component guidance

Follow the repository-wide rules in [Saarelai AGENTS.md](../../../../AGENTS.md) and [CONTRIBUTING.md](../../../../CONTRIBUTING.md). This component keeps an independent version stream and must not be released through its former standalone workflow.

## Identity and build

- JOSM plugin display name: `Improve Node`.
- Main class: `org.openstreetmap.josm.plugins.improvenode.ImproveNodePlugin`.
- Set `josm.pluginName` to `Improve Node` for the JOSM display name while keeping the component ID and Java package/class identifiers unchanged. The distributable JAR is `build/dist/improve-node.jar`; the local-development JAR is `build/localDist/improve-node-dev.jar`.
- License: GPL-2.0-or-later. New Java source files use `// License: GPL v2 or later. For details, see LICENSE file.`.
- Minimum supported JOSM version: `19044`; the build compiles against JOSM `19439`.
- The component version is in `gradle.properties`; the released history starts at `0.1.0`.
- Run this component's Gradle 8.x wrapper on JDK 21. The plugin manifest requires Java 25 at runtime while the compiled Java 21 bytecode runs on that target. Do not upgrade to Gradle 9 while JOSM Gradle plugin 0.8.2 is incompatible.
- On Windows, run `.\gradlew.bat clean build`; on Linux or macOS, run `./gradlew clean build`.
- No unit-test sources are currently tracked. The build validates compilation and packaging; do not claim a unit-test suite exists.

## Implementation guidance

- `ImproveNodePlugin` is the JOSM plugin entry point; `ImproveNodeAccuracyAction` owns map-mode interaction and `ImproveNodeAccuracyHelper` contains supporting spatial logic.
- The tool is for standalone nodes, not nodes belonging to parent ways. Preserve the selection and parent-way checks when changing which nodes may be moved.
- Keep the documented interactions consistent: `Shift` locks the highlighted node, `Ctrl` adds a standalone node, and `Alt` deletes an untagged highlighted node.
- Route every OSM data mutation through JOSM `Command` objects so undo and redo remain correct. Never mutate a `DataSet`, `Node`, or `Way` directly.
- Convert screen `Point` coordinates to JOSM map coordinates before doing geometry calculations. Keep rendering and data-edit logic clearly separated.
- Use JOSM's localization and logging APIs for user-facing text and errors. The plugin has no third-party runtime dependencies beyond JOSM.
- The former ImproveWay reference checkout and local JOSM guides were not part of this migration. Treat the current Improve Node source and tests added in Saarelai as authoritative; verify any external API guidance separately.

The plugin steward may implement code when requested. Prefer focused changes, and update the component README and changelog when user-facing behavior changes.
