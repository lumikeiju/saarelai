---
applyTo: "**"
---

<!-- @format -->

# arkki-snappi — AI Instructions

## Identity & Build

JOSM plugin · Package `org.openstreetmap.josm.plugins.arkkisnappi` · Map-mode shortcut `B` · Min JOSM 19439 · GPL-2.0-or-later (license header required on every source file)

Version lives in `gradle.properties` (`version=0.7.0`). Use JDK 21 to run the Gradle 8.14.3 wrapper. The plugin manifest requires Java 25 at runtime; the build validator checks its Java 21 bytecode target.

Gradle version is pinned to 8.x. The JOSM Gradle plugin 0.8.2 uses Provider.forUseAtConfigurationTime(), which was removed in Gradle 9. Do not upgrade the Gradle wrapper past 8.x until the JOSM Gradle plugin is updated. The Dependabot gradle ecosystem entry has been intentionally omitted for this reason.

```bash
./gradlew build        # → build/dist/ArkkiSnappi.jar (runs tests first)
./gradlew test         # unit tests; no JOSM instance needed
./gradlew runJosm      # launch JOSM with the plugin pre-loaded
./gradlew clean        # remove build/
# Windows: use .\gradlew.bat instead of ./gradlew
```

Build and release policies are shared at the Saarelai repository root. This component has no standalone release workflow; do not use the legacy branch-promotion process from its former repository. Saarelai's shared workflow will build only the selected component and preserve its independent version stream.

## File Map

```
src/main/java/…/arkkisnappi/
├── ArkkiSnappiPlugin.java             # entry point; registers mode + settings menu
├── SnappiMode.java                    # MapMode state machine + all event handling
├── SnappiGrid.java                    # geometry + rendering (stateless, all-static)
├── SnappiShrinkwrap.java              # self-intersection → outer boundary (stateless)
├── ReferenceOrientationDetector.java  # axis detection at anchor placement
├── SnappiPreferences.java             # Config.getPref wrappers (all-static)
└── SnappiPreferencesDialog.java       # JOSM Preferences tab + standalone dialog

src/test/java/…/arkkisnappi/
├── SnappiGridTest.java                # pure EastNorth arithmetic
└── SnappiShrinkwrapTest.java          # shrinkwrap invariants
```

There is no authoritative spec document — the code and tests are the source of truth. All classes except `SnappiMode` are stateless utilities; never introduce instance state into them.

## State Machine (SnappiMode)

Phases: `IDLE → PHASE_ANCHOR → PHASE_DEPTH → PHASE_EXTRUDE`

```
IDLE →(click 1)→ PHASE_ANCHOR
  hasReferenceOrientation: →(click 2)→ PHASE_EXTRUDE
  no reference:            →(click 2)→ PHASE_DEPTH →(click 3)→ PHASE_EXTRUDE

PHASE_EXTRUDE:
  drag handle   → commitExtrude() → wayCorners updated → shrinkwrapWay() → simplifyWay()
                  (Shift-click handles selects parallel edges; a drag on any selected handle
                  extrudes them together)
                  (new-polygon modifier held → commitExtrudeAsNewPolygon() instead: spins each
                  selected section off into its own way, sewn to the dragged edge's existing nodes)
  click edge    → handleEdgeClickExtrude() (splits edge, inserts node)
  Ctrl-click handle → splitEdgeIntoThirds() (inserts one-third and two-thirds nodes)
  click away    → finish + start new building preserving axes
  Enter         → finishShape() → shrinkwrapWay() → simplifyWay() → IDLE
  Esc           → IDLE (resetState())
```

Reference orientation priority (see `ReferenceOrientationDetector.detect`): `A` key (cardinal) > first selected way with ≥ 2 nodes > nearby closed way > none (3-click mode).

## ⚠ Coordinate System — Common Error Source

Three spaces; **never mix** them:

| Space     | Type             | Use                                        |
| --------- | ---------------- | ------------------------------------------ |
| EastNorth | projected coords | **All geometry and snapping**              |
| LatLon    | WGS-84           | OSM storage and projection-correction only |
| Point     | screen pixels    | Hit-testing and rendering only             |

**Problem:** In Web Mercator (EPSG:3857), EastNorth ≠ real-world metres — inflated by `sec(lat)`. At 60° lat: ~2× error if uncorrected.

**Correction (mandatory):**

- Display lengths → `SnappiGrid.realWorldDistance(a, b)` (calls `greatCircleDistance()`)
- EN snap steps → `activeStepMetres * projectionScale` via `enStepU()` / `enStepV()`
- `projectionScale` computed once at anchor time: `SnappiGrid.projectionScale(anchorEN)`
- **Never** use raw EN Euclidean arithmetic for measurements or grid spacing

**Local grid frame:**

```
u-axis = unit vector anchor→mouse (or reference edge first segment)
v-axis = u rotated 90° CCW
world  = anchor + uSnapped·uAxis + vSnapped·vAxis
(u, v) = (dot(world-anchor, uAxis), dot(world-anchor, vAxis))
```

## Architecture Rules

1. **Separation of concerns** — snapping math → `SnappiGrid`; self-intersection → `SnappiShrinkwrap`; `SnappiMode` = state + events only.
2. **Undo safety** — every OSM write uses the Command pattern; no direct `DataSet` mutation ever.
3. **`wayCorners[]` cache** — must reflect the current way geometry at all times; updated in `commitExtrude()`, `simplifyWay()`, `shrinkwrapWay()`, and rebuilt by `syncWayCorners()`.
4. **Cleanup order** — after `commitExtrude()` updates `wayCorners`: `shrinkwrapWay()` then `simplifyWay()`. Same pair on `finishShape()`.
5. **Simplify bypass** — `shouldAutoSimplify()` returns `false` when `shiftDown`; this is per-operation, not a global toggle.
6. **Projection-aware geometry** — never store or measure in screen `Point` space; convert to `EastNorth` first.
7. **Extrude-as-new-polygon sewing** — `commitExtrudeAsNewPolygon()` must reuse the dragged edge's existing `Node` objects (never recreate them) so the spun-off way shares corners with its neighbour instead of duplicating coincident nodes; only the two new outward corners are freshly created.
8. **Ctrl+Shift snap override** — `computeExtrudeOffset()` only drops to free mode when `ctrlDown && !shiftDown`; with Shift also held (the default new-polygon chord) snapping stays active so verge-section extrusions stay on-grid.

## Keyboard & Modifiers

| Key                                                              | During drawing                 | During extrude / finish                                                                                                          |
| ---------------------------------------------------------------- | ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------- |
| `Shift`                                                          | axis-lock (dominant axis only) | bypass `simplifyWay()` for this operation                                                                                        |
| `Ctrl`                                                           | free mode (no snapping)        | free mode, _unless_ Shift is also held (see new-polygon row)                                                                     |
| `Alt`                                                            | cycle step preset              | —                                                                                                                                |
| `Ctrl+Shift` default, rebindable to `Shift`/`Ctrl`/`Alt`/`None`) | —                              | on drag release: `commitExtrudeAsNewPolygon()` instead of `commitExtrude()`; snapping stays active for `Ctrl+Shift` (see rule 8) |
| `Shift` + click handle                                           | —                              | toggle a parallel edge in the extrusion selection                                                                                |
| `Ctrl` + click handle                                            | —                              | split the edge into three equal sections                                                                                         |
| `A`                                                              | toggle cardinal grid           | —                                                                                                                                |
| `C` / `V`                                                        | halve / double step            | —                                                                                                                                |
| `Enter`                                                          | finish shape                   | —                                                                                                                                |
| `Esc`                                                            | cancel → IDLE                  | —                                                                                                                                |

## Preferences (`arkki_snappi.*`)

All step values are stored and operated on in **real-world metres**. Defaults are declared as `DEFAULT_*` constants in `SnappiPreferences`.

```
step_x_metres / step_y_metres   double   0.3048                (1 ft)
linked_steps                    bool     true
step_unit                       string   "ft"                  ("ft" | "m")
step_presets                    string   "0.0762;0.1524;0.3048;0.6096;1.0;0.5;0.25"
tags                            list     [["building","yes"]]  default tags for new buildings
auto_select                     bool     true                  auto-select created way
auto_simplify / auto_shrinkwrap bool     true
ccw_winding                     bool     true
handle_radius / node_snap_radius int     10 / 15
drag_threshold_px               int      5
max_grid_lines                  int      200
new_polygon_modifier            string   "Ctrl+Shift"          ("Ctrl+Shift" | "Shift" | "Ctrl" | "Alt" | "None")
color.grid / color.rect / color.anchor / color.target / color.handle / color.extrude  # ARGB ints; blueprint theme by default
```

Display: `SnappiPreferences.formatStep(metres)` / `formatStepPair(xM, yM)` — rounds to 5 sig-figs, strips trailing zeros. `metresToFeet()` / `feetToMetres()` use exact `0.3048` (no rounding loss).

## Key JOSM API Patterns

```java
// Coordinate conversion
mv.getEastNorth(point)                                        // screen → world
mv.getPoint(en)                                               // world → screen
ProjectionRegistry.getProjection().eastNorth2latlon(en)       // EN → LatLon (node creation)

// Data mutation — always wrap in Commands
DataSet ds = getLayerManager().getEditDataSet();
List<Command> cmds = new ArrayList<>();
cmds.add(new AddCommand(ds, newNode));
Way updated = new Way(existingWay);                           // clone, then mutate
updated.setNodes(newNodeList);
cmds.add(new ChangeCommand(existingWay, updated));
cmds.add(new DeleteCommand(ds, Collections.singleton(orphan)));
UndoRedoHandler.getInstance().add(new SequenceCommand(tr("label"), cmds));

// Mode lifecycle (enterMode / exitMode)
map.mapView.addTemporaryLayer(this);          // MapViewPaintable overlay
map.keyDetector.addModifierExListener(this);  // ModifierExListener
map.keyDetector.addKeyListener(this);         // KeyPressReleaseListener
```

## Testing

- Unit tests cover **pure EastNorth arithmetic only** — no JOSM instance required (`SnappiGridTest`, `SnappiShrinkwrapTest`). Keep it that way for new tests.
- Run with `./gradlew test`; CI runs the same suite as part of `./gradlew build`.
- When changing snapping/geometry/shrinkwrap behaviour, add or extend a unit test before/with the change.

## Coding Conventions

- 4-space indent · UTF-8 · CRLF (enforced by `.editorconfig`) · GPL-2.0-or-later header on every source file · Javadoc on public API
- CCW winding default (JOSM convention); `SnappiPreferences.isCcwWinding()` toggleable
- Preference keys: `arkki_snappi.<name>` — no external deps beyond JOSM core and JUnit (tests)
- Keep existing formatting in project documentation and metadata; the component does not configure a separate JavaScript formatter.
- Keep changes minimal and focused; this is a small, dependency-free plugin
