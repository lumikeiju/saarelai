---
description: Easy Pan - Main Agent Instructions
applyTo: "**"
---

<!-- SPDX-License-Identifier: GPL-2.0-or-later -->

# Easy Pan - Main Agent Instructions

Follow [AGENTS.md](../../AGENTS.md) and Saarelai's repository-level guidance. Follow the root commit and release policies. Keep changes focused, run `.\gradlew.bat clean build` on Windows or `./gradlew clean build` on Linux/macOS, and update related documentation including the [CHANGELOG.md](../../CHANGELOG.md).

Gradle is pinned to 8.x because JOSM Gradle plugin 0.8.2 uses `Provider.forUseAtConfigurationTime()`, removed in Gradle 9. Do not upgrade the wrapper past 8.x until the JOSM Gradle plugin is updated, and keep dependency updates compatible with the pinned toolchain.

Run this component's Gradle 8.14.5 wrapper on JDK 21. It compiles to Java 21 bytecode and declares Java 25 as the JOSM runtime minimum.
