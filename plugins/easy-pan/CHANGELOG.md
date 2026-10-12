<!-- @format -->
<!-- SPDX-License-Identifier: GPL-2.0-or-later -->

# Easy Pan - Changelog

All notable changes to Easy Pan are documented here, using the [Keep a Changelog](https://keepachangelog.com/) format, [Semantic Versioning](https://semver.org/), and [Conventional Commits](https://www.conventionalcommits.org/).

## Unreleased

### Changed

- Keep the Gradle wrapper on the 8.x line because the JOSM Gradle plugin 0.8.2 is incompatible with Gradle 9
- Set Java 25 as the minimum JOSM runtime while compiling Java 21 bytecode.

## v0.2.0 (2026-07-27)

### Added

- "Throw" momentum after release, with configurable falloff and minimum-speed controls in the preferences dialog
- A reset-to-defaults action and clearer per-setting help text for the Easy Pan preferences UI

## v0.1.0 (2026-07-26)

### Added

- Initial release!
