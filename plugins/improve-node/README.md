<!-- @format -->
<!-- SPDX-License-Identifier: GPL-2.0-or-later -->

# ImproveNode

JOSM plugin for efficiently improving standalone nodes.

Requires JOSM 19044 or newer and Java 25 at runtime. ImproveNode is maintained in [Saarelai](https://github.com/lumikeiju/saarelai/tree/main/plugins/improve-node).

Build with JDK 21 using the included Gradle wrapper; the plugin's Java 21 bytecode runs on the Java 25 JOSM target.

## Usage

Enable **Improve Node** mode, then click the corrected imagery position for a highlighted standalone node. The plugin moves only nodes that are not members of any parent way, so it is suited for imported point datasets such as light poles, trees, or utility assets.

- Move near a standalone node to highlight it, then click where it should be moved.
- Select one or more standalone nodes to limit the mode to that selection.
- Hold `Shift` to keep the current highlighted node locked while aiming.
- Hold `Ctrl` and click to add a new standalone node.
- Hold `Alt` and click to delete the highlighted standalone node if it is untagged.

## Build

Build with the included Gradle wrapper from this directory:

```powershell
.\gradlew.bat clean build
```

On Linux or macOS, run `./gradlew clean build`.

The development plugin update site is generated at:

```text
build/localDist/list
```

Add that file URL as a JOSM plugin update site in expert mode to load `ImproveNode-dev` into a local JOSM instance.

There are currently no tracked unit tests; the build validates compilation and packaging.

## Versioning and releases

ImproveNode keeps an independent version stream; its existing baseline is `0.1.0` in `gradle.properties`. Releases will use Saarelai's shared per-component process. Do not release from a component-specific workflow.

See the [Saarelai overview](../../README.md) and [contributor guide](../../CONTRIBUTING.md).

## License

Licensed under GPL-2.0-or-later. See [LICENSE](LICENSE) and the bundled [GPL v2](gpl-2.0.txt) and [GPL v3](gpl-3.0.txt) texts.

## Credits

[ImproveWayAccuracy](https://josm.openstreetmap.de/wiki/Help/Action/ImproveWayAccuracy) by [Alexander Kachkaev](https://github.com/kachkaev).

[ImproveWay](https://github.com/JOSM/improve-way) by [András Kolesár](https://github.com/kolesar-andras).

[ImproveNode](https://github.com/lumikeiju/saarelai/tree/main/plugins/improve-node) by [Amy Bordenave](https://github.com/Lumikeiju).
