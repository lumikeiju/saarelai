<!-- SPDX-License-Identifier: GPL-2.0-or-later -->

# Easy Pan

Easy Pan is a JOSM plugin that reduces repetitive input while mapping.

Hold the activation key (recommended: `shift` or `space`) over the map and move the pointer relative to its activation position to pan the map, with speed scaling by the pointer displacement.

https://github.com/user-attachments/assets/41e61ec2-f824-4251-9454-098142987077

_(Background imagery: Bing)_

## Curve helpers:

- Pan response uses this formula, capped by $maxSpeed$:

$$speed = gain \times distance^{exponent}$$

- Throw decay uses this formula, stopping below $minSpeed$

$$velocity = velocity \times falloffMultiplier^{elapsedSeconds \times 60}$$

## Development

Easy Pan is maintained in [Saarelai](https://github.com/lumikeiju/saarelai/tree/main/plugins/easy-pan). Use the included Gradle wrapper from this directory.

Use JDK 21 to run this component's Gradle 8.x wrapper. The plugin manifest requires JOSM to run on Java 25; the plugin bytecode targets Java 21.

```powershell
.\gradlew.bat clean build
```

On Linux or macOS, run `./gradlew clean build`. The Gradle build produces the plugin artifact at `build/dist/EasyPan.jar` and a development update-site list at `build/localDist/list`. To test a local build, add that list as a file-based JOSM plugin update site in expert preferences.

## Versioning and releases

Easy Pan keeps an independent version stream configured in `gradle.properties`. Releases use Saarelai's shared per-component process. Do not release from a component-specific workflow.

See the [Saarelai overview](../../README.md) and [repository guidance](../../AGENTS.md).

## License

Licensed under the [GNU General Public License v2.0 or later](LICENSE).
