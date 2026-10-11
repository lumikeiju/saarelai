<!-- @format -->

<!-- SPDX-License-Identifier: GPL-2.0-or-later -->

# Contributing to ArkkiSnappi

Thanks for contributing! This guide covers how to build, test, and submit changes to ArkkiSnappi, the JOSM building-footprint mapping plugin.

## Getting started

Use JDK 21 to run the included Gradle wrapper. The produced plugin requires JOSM to run on Java 25 and uses Java 21 bytecode.

```bash
./gradlew build        # → build/dist/ArkkiSnappi.jar (runs tests first)
./gradlew test         # unit tests — pure EastNorth math, no JOSM instance needed
./gradlew runJosm      # launch JOSM with the plugin pre-loaded
```

Windows: use `.\gradlew.bat` instead of `./gradlew`.

## Development workflow

Follow the shared development and review process in [Saarelai's contributor guide](../../CONTRIBUTING.md). Use focused changes, run the Gradle tests and build, and update `CHANGELOG.md` for user-visible changes.

Arkki Snappi is released independently through Saarelai's shared release process. No component-specific release workflow is included here; do not use the legacy branch-promotion instructions from the former standalone repository.

## Code conventions

- 4-space indent · UTF-8 · **CRLF** line endings (`.editorconfig` enforces).
- GPL-2.0-or-later license header on every source file: `// License: GPL v2 or later. For details, see LICENSE file.`
- Javadoc on all public API.
- All OSM writes go through JOSM `Command`s — never mutate a `DataSet` directly.
- Geometry/snapping in `EastNorth` only; never mix `EastNorth`, `LatLon`, and screen `Point`; always apply the Mercator projection correction.
- `SnappiGrid`, `SnappiShrinkwrap`, `SnappiPreferences`, and `ReferenceOrientationDetector` are stateless all-static utilities — no instance state.
- Preference keys: `arkki_snappi.<name>`; step values stored in real-world metres.
- No external dependencies beyond JOSM core and JUnit 6 (tests).
- Unit tests cover pure EastNorth arithmetic only — keep them JOSM-free.

For full detail see `.agents/instructions/main.instructions.md` and the auto-loading skills in `.agents/skills/`.

## Licensing

By contributing, you agree that your contributions are licensed under **GPL-2.0-or-later**. See [LICENSE](LICENSE) for details.
