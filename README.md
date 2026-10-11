# Saarelai

Saarelai is the home space for independently maintained JOSM mapping tools: plugins, tagging presets, MapCSS paint styles, validator rules, and related resources.

Like a workshop, Saarelai has a master artificer coordinating category stewards, each caring for one kind of tool.

## Organization

Each independently released item will live in its own directory under a category:

```text
plugins/<plugin-id>/
presets/<preset-id>/
styles/<style-id>/
validators/<rule-id>/
```

An item owns its source, assets, tests, JOSM metadata, and changelog. There is no Saarelai-wide component version: releases are versioned per item. [catalog/components.json](catalog/components.json) maps each component to its native version source, validation, artifact, and persistent stable path. Shared automation and repository instructions live at the root.

## Development

There is no single application runtime or root build. Use the instructions and pinned toolchain for the component being changed. The plugin build workflow uses JDK 21 for Gradle; resulting plugins declare Java 25 as their minimum runtime, matching the target JOSM installation. Run the root release-utility tests with:

```sh
python -m unittest discover -s scripts/tests -v
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the contribution workflow and [AGENTS.md](AGENTS.md) for repository-wide guidance.

## Releases and JOSM catalogs

Use the manual **Component release** workflow to create a candidate for one item and later promote that exact candidate to stable. Candidate assets include a checksummed package and source-commit provenance. Stable promotion updates only that item's persistent raw URL on the `stable` branch. See [catalog/README.md](catalog/README.md) for version preparation, dispatch inputs, environment protection, and local validation.

## Licensing

Saarelai gathers components with different licenses; there is no single license for the whole repository. See [LICENSES.md](LICENSES.md) and the license and attribution notices belonging to each component.
