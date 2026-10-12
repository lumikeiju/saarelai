<!-- SPDX-License-Identifier: GPL-2.0-or-later -->

# Contributing to Improve Node

Improve Node is maintained as an independent plugin component in Saarelai. Follow the shared development and review process in [Saarelai's contributor guide](../../CONTRIBUTING.md) and the component guidance in [.agents/instructions/main.instructions.md](.agents/instructions/main.instructions.md).

## Build and test

Use the included Gradle wrapper from this directory:

```powershell
.\gradlew.bat clean build
```

On Linux or macOS, run `./gradlew clean build`. There are currently no tracked unit tests; the build validates compilation and packaging.

The distributable JAR is `build/dist/improve-node.jar`; the local-development JAR is `build/localDist/improve-node-dev.jar`, with its update-site list at `build/localDist/list`.

Use JDK 21 to run this component's Gradle 8.x wrapper. The plugin manifest requires JOSM to run on Java 25; the plugin bytecode targets Java 21.

Update `CHANGELOG.md` for user-visible changes. Improve Node follows Saarelai's independent component versioning and shared release process; no standalone release workflow is included here.

## Licensing

By contributing, you agree that your contributions are licensed under **GPL-2.0-or-later**. See [LICENSE](LICENSE) for details.
