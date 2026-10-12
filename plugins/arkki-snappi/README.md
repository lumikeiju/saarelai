<!-- @format -->

# Arkki Snappi

JOSM plugin for quick and accurate building footprint mapping with a 2-axis snap grid.

JOSM compatibility is declared in the plugin manifest. The plugin adds a dedicated Arkki Snappi map mode with the `B` shortcut and builds a distributable JAR at `build/dist/arkki-snappi.jar`.

## What It Does

**Arkki Snappi** combines the rectangle-drawing workflow of [BuildingsTools](https://wiki.openstreetmap.org/wiki/JOSM/Plugins/BuildingsTools) with the edge-extrusion UX of [Extrude](https://josm.openstreetmap.de/wiki/Help/Action/Extrude), adding a snap grid that works in _both_ axes simultaneously.

### Workflow

1. **Click 1** — Place the anchor corner (red dot). A snap grid appears, oriented to a nearby building, selected way, or cardinal alignment.
2. **Move mouse** — Both X and Y snap independently to step multiples (e.g. 1 ft). A live rectangle preview follows your cursor.
3. **Click 2** — Commit the rectangle. Nodes, way, and default tags are created.
4. **Drag edge handles** — Extrude any edge outward/inward with the same step snapping. Shift-click handles to select multiple parallel edges, then drag any selected handle to extrude them together. Hold the configured modifier (default `Ctrl+Shift`) while releasing the drag to spin each selected section off into its own polygon, sewn to the edge it came from.
5. **Click an edge** — Insert a new node at the nearest grid point, splitting the edge for partial extrusions. Ctrl-click a midpoint handle to split the edge into three equal sections.
6. **Enter** — Finish the current building and return to idle.
7. **Esc** — Cancel and return to idle.

Corners automatically snap to nearby existing nodes for shared-wall accuracy. After each extrusion and when a shape is finished, the outline is simplified automatically: collinear nodes are removed and coincident duplicates (left when an edge is extruded out and immediately back in) are merged, while any self-intersections from overlapping extrusions are resolved (shrinkwrap). Hold `Shift` while releasing an extrusion or finishing to keep the nodes for that operation.

### Keyboard Shortcuts

| Key                       | Effect                                                                  |
| ------------------------- | ----------------------------------------------------------------------- |
| `B`                       | Activate Arkki Snappi mode                                              |
| `A`                       | Toggle cardinal grid alignment                                          |
| `C`                       | Halve the current snap step size                                        |
| `V`                       | Double the current snap step size                                       |
| `Shift`                   | Lock snap to the dominant axis                                          |
| `Shift`                   | Bypass simplification for an operation                                  |
| `Shift` + click handle    | Select/deselect a parallel edge for the next extrusion                  |
| `Ctrl`                    | Disable snapping (free position)                                        |
| `Ctrl` + click handle     | Split the edge into three equal sections                                |
| `Alt`                     | Cycle to the next step preset                                           |
| `Ctrl+Shift` (rebindable) | While releasing an extrude drag: commit as a new, sewn-together polygon |
| `Enter`                   | Finish the current building                                             |
| `Esc`                     | Cancel current operation                                                |

## Settings

Available in the **JOSM Preferences → Arkki Snappi** tab, or via **More tools → Arkki Snappi settings…**:

- **Step size** — Base snap increment (default: 1 ft / 0.3048 m), X and Y independently or linked
- **Display unit** — `ft` or `m`
- **Node snap radius** — Screen-space pixel radius for snapping corners to existing nodes (default: 15 px), so the real-world snap distance changes with zoom level
- **Max grid lines** — Per-axis cap to keep the overlay responsive on large zoom levels
- **Drag threshold** — Minimum cursor movement before a press becomes an extrude drag
- **Default tags** — Key/value pairs applied to new buildings (default: `building=yes`)
- **Tag presets** — One-click buttons for common tag sets
- **Auto-select** — Select newly created way (default: on)
- **Auto-simplify** — Remove collinear nodes and merge coincident duplicates after extrusions and when finishing (default: on; hold `Shift` to bypass once)
- **Auto-shrinkwrap** — Resolve self-intersecting polygons after extrusions and when finishing (default: on)
- **Winding order** — Counter-clockwise or clockwise node ordering
- **Extrude as new polygon** — Modifier key (`Ctrl+Shift` default, or `Shift`/`Ctrl`/`Alt`/`None`) held while releasing an extrude drag to spin the section off into its own way instead of growing the current one, sewn together at the shared edge. Grid snapping stays active for this chord even though plain `Ctrl` alone means free mode.
- **Color themes** — Blueprint (default), Satellite, Neon, Ink — or pick individual colors

## Building

Build with JDK 21 using the included Gradle wrapper. The plugin manifest requires JOSM to run on Java 25; Java 21 bytecode runs on that target runtime. The wrapper downloads other build prerequisites automatically.

```sh
# Run the test suite
.\gradlew.bat test          # Windows
./gradlew test              # macOS / Linux

# Build the plugin JAR
.\gradlew.bat build          # Windows
./gradlew build              # macOS / Linux
```

The build runs the test suite and produces the release JAR at `build/dist/arkki-snappi.jar`. Its local-development JAR is `build/localDist/arkki-snappi-dev.jar`.

### Releases

Arkki Snappi keeps an independent version stream; its Gradle version is in `gradle.properties`. Releases use Saarelai's shared per-component process and namespaced tags. The standalone repository's branch-promotion workflows are intentionally not included. See the [Saarelai overview](../../README.md) and [repository guidance](../../AGENTS.md).

### Installing into JOSM

**Option A — Copy the JAR manually:**

```sh
copy build\dist\arkki-snappi.jar %APPDATA%\JOSM\plugins\arkki-snappi.jar
```

Keep the lower-kebab-case filename `arkki-snappi.jar`.

Restart JOSM, then enable the plugin in **Edit → Preferences → Plugins**.

**Option B — Local update site (hot-reload during development):**

The build also generates a local plugin list at `build/localDist/list`. In JOSM:

1. **Edit → Preferences → Plugins** (enable expert mode first)
2. Add this update site URL: `file:/<path-to-repo>/build/localDist/list`
3. Click **Update plugins**, find Arkki Snappi-dev, enable it
4. After rebuilding, just restart JOSM — it picks up the latest JAR automatically

### Running JOSM with the plugin (one command)

```sh
.\gradlew.bat runJosm
```

This launches a JOSM instance with the plugin pre-loaded.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow, code conventions, and licensing. Release history is tracked in [CHANGELOG.md](CHANGELOG.md).

## Release Notes

- Package name: `org.openstreetmap.josm.plugins.arkkisnappi`
- Main class: `org.openstreetmap.josm.plugins.arkkisnappi.ArkkiSnappiPlugin`
- License: GPL-2.0-or-later

## Credits

[Arkki Snappi](https://github.com/lumikeiju/saarelai/tree/main/plugins/arkki-snappi) by [Amy Bordenave](https://github.com/Lumikeiju).

## License

GPL-2.0-or-later. See [LICENSE](LICENSE) for details.
