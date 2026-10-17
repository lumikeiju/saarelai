# Contributing to Saarelai

Read [AGENTS.md](AGENTS.md) before editing. Saarelai is a polyglot monorepo: component folders own their build and validation requirements, and there is no root application build.

## Setup

1. Clone the repository and check `git status`.
2. Read this guide, [AGENTS.md](AGENTS.md), and the documentation for the component you intend to change.
3. Install only that component's documented toolchain and dependencies. Do not install a root JavaScript runtime or package set; Saarelai does not use one for its current repository-level tasks.
4. Keep credentials and machine-specific configuration out of the repository.

If the desired component has not yet been migrated, do not copy it in as an ad hoc contribution; coordinate the migration so source, history, metadata, and licensing are handled together.

## Changes and validation

The supported JOSM runtime target is Java 25. Plugin builds use their Gradle 8.x wrappers with JOSM Gradle plugin 0.8.2 on JDK 21, so plugin builds use Java 21 bytecode and declare `Plugin-Minimum-Java-Version: 25`. Install JDK 21 for building and Azul JDK 25 for the target JOSM runtime. The release validator checks both the JAR bytecode and the manifest requirement.

- Keep each change focused on one component or one clearly shared repository concern.
- Use a branch named `type/scope/short-description`, such as `fix/arkki-snappi/snap-preview`.
- Use Conventional Commits, with a component scope where applicable, for example `fix(improve-node): preserve selection`.
- Run the component's documented tests, build, or resource validation before opening a pull request. For shared workflow or release changes, validate every affected component.
- At the repository root, `git diff --check` is the available generic whitespace check; it does not replace component validation.
- Update the relevant changelog after every development change: the affected component's changelog for component work, or [CHANGELOG.md](CHANGELOG.md) for shared repository work. Update documentation for user-visible changes. The shared changelog does not define a repository-wide product version.
- Describe affected component IDs, validation performed, and any JOSM compatibility or release impact in the pull request.

## Parallel work

For concurrent agent or chat work, assign each task distinct component paths. Use separate branches or worktrees where available. If work shares one checkout, coordinate ownership and avoid concurrent edits to root policy, shared workflows, or stable feeds.

## Releases and publication

Keep `main` as the primary development branch: create feature branches from it and merge reviewed changes back into it. Each component is released independently through the manual **Component release** workflow. Run `beta` from `main` to publish that component's outputs to the output-only `beta` branch and create a GitHub prerelease. After testing that exact beta in JOSM, run `stable` with its tag. Stable publication updates only that component's files on the output-only `stable` branch, without rebuilding. Neither output branch contains development source or receives feature merges. The component registry and operator instructions are in [catalog/README.md](catalog/README.md).

Configure GitHub environments named `beta` and `stable`; these are separate from the Git branches of the same names. For this solo-maintainer setup, do not require environment reviewers: the workflow already requires explicitly confirming that you tested the exact beta before promotion. Beta releases do not write to the `stable` branch. Initial JOSM catalog registration and all JOSM-owned page edits remain human-only; the workflow never submits catalog entries or edits JOSM pages.

## Licensing and attribution

Preserve the license and attribution notices of every component and bundled dependency. Do not add a repository-wide license to component files; see [LICENSES.md](LICENSES.md) for the repository's licensing policy.
