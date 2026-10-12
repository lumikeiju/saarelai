---
name: build-test-run
description: Build, unit-test, and run the Arkki Snappi JOSM plugin with the Gradle wrapper. Use when compiling, running tests, producing the plugin JAR, or launching JOSM for manual testing.
triggers:
    - gradle build
    - run tests
    - plugin jar
    - runJosm
    - build Arkki Snappi
paths:
    - build.gradle
    - settings.gradle
    - gradle.properties
    - gradlew
    - gradlew.bat
---

<!-- @format -->

# Build, Test, Run

Use JDK 21 to run this component's Gradle 8.x wrapper. The plugin manifest requires Java 25 at runtime; release validation checks the Java 21 bytecode target.

## Commands

| Task        | Command             | Notes                                                 |
| ----------- | ------------------- | ----------------------------------------------------- |
| Unit tests  | `./gradlew test`    | Pure EastNorth arithmetic; no JOSM instance           |
| Build JAR   | `./gradlew build`   | Runs tests first; output `build/dist/arkki-snappi.jar` |
| Run in JOSM | `./gradlew runJosm` | Launches JOSM with the plugin pre-loaded              |
| Clean       | `./gradlew clean`   | Removes `build/`                                      |

Windows: use `.\gradlew.bat` instead of `./gradlew`.

## Gotchas

- JavaExec and `test` tasks need `--add-exports` JVM flags — already configured in `build.gradle`; don't remove them.
- Gradle is pinned to 8.x. JOSM Gradle plugin 0.8.2 uses `Provider.forUseAtConfigurationTime()`, which was removed in Gradle 9. Do not upgrade the wrapper to Gradle 9 until the JOSM Gradle plugin is compatible.
- Run `.\gradlew.bat build` on Windows or `./gradlew build` on Linux/macOS before submitting changes. Saarelai's shared CI will validate this plugin when configured.
- Manual JOSM install: copy `build/dist/arkki-snappi.jar` to `arkki-snappi.jar` in the JOSM plugins dir, or register local update site `file:/<repo>/build/localDist/list` for hot reload during development. The development JAR is `build/localDist/arkki-snappi-dev.jar`.
