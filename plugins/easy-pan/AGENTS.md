<!-- @format -->
<!-- SPDX-License-Identifier: GPL-2.0-or-later -->

# Easy Pan - Agent Conventions

Read this file before editing.

- Keep changes focused, update related documentation, and validate them with the project's configured checks.
- For the versioned plugin, use Semantic Versioning and synchronize release metadata where it exists.
- Place production source in `src/main/java` and tests in `src/test/java`.
- Gradle is pinned to 8.x because JOSM Gradle plugin 0.8.2 uses `Provider.forUseAtConfigurationTime()`, removed in Gradle 9. Do not upgrade the wrapper past 8.x until the JOSM Gradle plugin is updated, and keep dependency updates compatible with the pinned toolchain.
- Run this component's Gradle 8.x wrapper on JDK 21. The component compiles to Java 21 bytecode and declares Java 25 as its JOSM runtime minimum.
