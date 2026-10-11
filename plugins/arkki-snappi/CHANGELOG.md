<!-- @format -->
<!-- SPDX-License-Identifier: GPL-2.0-or-later -->

# ArkkiSnappi - Changelog

All notable changes to ArkkiSnappi are documented in this file.

The format of this changelog is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) and [Conventional Commits](https://www.conventionalcommits.org/).

## Unreleased

### Changed

- Set Java 25 as the minimum JOSM runtime while keeping the build bytecode at Java 21.

## v0.7.0 (2026-09-06)

- Added Ctrl-click midpoint handles to split edges into three equal sections in one undoable command

## v0.6.0 (2026-08-30)

- Added Shift-click edge handles to select or deselect parallel edges for one extrusion
- Added simultaneous extrusion of selected parallel edges with grouped undo-safe edits and live previews

## v0.5.0 (2026-08-29)

- Added a configurable modifier key (default `Ctrl+Shift`) for extruding as a new polygon
- Shared boundary nodes, applied default tags, and kept each new polygon active for chained extrusion
- Added an "Extrude as new polygon" preference
- Kept grid snapping active when `Ctrl` and `Shift` were held during extrusion

## v0.4.0 (2026-08-20)

- Added `AGENTS.md`, `CONTRIBUTING.md`, and `CHANGELOG.md` for human and agent development
- Added four agent skills under `.agents/skills/`
- Added Dependabot coverage for Gradle dependencies and GitHub Actions updates
- Re-licensed the project to **GPL-2.0-or-later** and updated license texts and source headers
- Moved and rewrote AI instructions to `.agents/instructions/`
- Linked the contributing guide and changelog from the README

## v0.3.0 (2026-05-22)

- Preserved collinear nodes when `Shift` was held during extrusion or completion
- Converted displayed step values when the preferences unit changed between `ft` and `m`

## v0.2.0 (2026-04-05)

- Automated GitHub releases for `v*` tags with generated notes and the plugin JAR attached

## v0.1.0 (2026-04-04)

- Released the initial version
- Added Snappi map mode (`B`) for accurate building-footprint mapping with a two-axis snap grid
- Added reference orientation detection from cardinal alignment, selected ways, or nearby buildings
- Added independent X/Y snapping with configurable steps, presets, and keyboard shortcuts
- Added edge extrusion via drag handles and edge-click splitting
- Added auto-simplification and shrinkwrap on finish
- Added Snappi preferences for steps, snapping, tags, and colors
- Added unit tests for grid geometry and shrinkwrap logic
