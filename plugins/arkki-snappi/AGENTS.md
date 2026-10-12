<!-- @format -->

# Arkki Snappi — JOSM plugin

Quick building-footprint mapping with a 2-axis snap grid. Package `org.openstreetmap.josm.plugins.arkkisnappi` · map-mode shortcut `B` · min JOSM 19439 · License: `GPL-2.0-or-later`.

## Build & test

```bash
./gradlew build        # → build/dist/arkki-snappi.jar (runs tests first)
./gradlew test         # unit tests — pure EastNorth math, no JOSM needed
./gradlew runJosm      # launch JOSM with the plugin pre-loaded
```

Use `Arkki Snappi` as the JOSM display name; the local-development JAR is `build/localDist/arkki-snappi-dev.jar`.

Use JDK 21 to run this component's Gradle 8.x wrapper. The JOSM plugin manifest requires Java 25 at runtime; the build and release validator check that the JAR uses Java 21-or-earlier bytecode.

Gradle version is pinned to 8.x. The JOSM Gradle plugin 0.8.2 uses
`Provider.forUseAtConfigurationTime()`, which was removed in Gradle 9. Do not
upgrade the Gradle wrapper past 8.x until the JOSM Gradle plugin is updated.
The [Dependabot config](../../.github/dependabot.yml) ignores major
`gradle-wrapper` updates for this component until then.

## Non-negotiable rules

1. **Coordinates** — geometry/snapping in `EastNorth` only; never mix `EastNorth`, `LatLon`, screen `Point`. Mercator inflates EN vs real metres by `sec(lat)` (~2× at 60° lat): use `SnappiGrid.realWorldDistance`/`projectionScale` for measurements, never raw EN arithmetic.
2. **OSM writes** — always via JOSM `Command`s (`AddCommand`/`ChangeCommand`/`DeleteCommand`/`SequenceCommand`); never mutate a `DataSet` directly.
3. **`wayCorners[]`** — keep in sync with the committed way after every geometry change; after extrude run `shrinkwrapWay()` then `simplifyWay()`.
4. **Stateless utilities** — `SnappiGrid`, `SnappiShrinkwrap`, `SnappiPreferences`, `ReferenceOrientationDetector` are all-static; only `SnappiMode` holds state.

## Agent resources

- **Full project reference:** `.agents/instructions/main.instructions.md`
- **Contributing guide:** `CONTRIBUTING.md`
- **Release history:** `CHANGELOG.md`
- **Skills** (auto-load when triggered / relevant files edited):
    - `build-test-run` — build, test, run-JOSM commands & gotchas
    - `coordinate-spaces` — the three coordinate spaces + projection correction
    - `undo-safe-edits` — JOSM Command pattern and node merging
    - `plugin-conventions` — formatting, preferences, testing rules
