<!-- @format -->
<!-- SPDX-License-Identifier: GPL-2.0-or-later -->

# Contributing to Easy Pan

Easy Pan is maintained as an independent plugin component in Saarelai. Follow the shared development and review process in [Saarelai's contributor guide](../../CONTRIBUTING.md) and the component boundaries in [AGENTS.md](../../AGENTS.md).

## Build and test

Use the included Gradle wrapper from this directory:

```powershell
.\gradlew.bat clean build
```

On Linux or macOS, use `./gradlew clean build`. The build runs the test suite and produces `build/dist/EasyPan.jar`.

Use JDK 21 to run the Gradle 8.14.3 wrapper. The plugin manifest requires JOSM to run on Java 25; the plugin bytecode targets Java 21.

Update `CHANGELOG.md` for user-visible changes. Easy Pan follows Saarelai's independent component versioning and shared release process; no standalone release workflow is included here.

## Licensing

Easy Pan and its contributions are licensed under GPL-2.0-or-later. See [LICENSE](LICENSE).
