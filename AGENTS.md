# Saarelai repository guidance

Read this file and [CONTRIBUTING.md](CONTRIBUTING.md) before making changes. Also read the nearest component-specific `AGENTS.md`, `README.md`, or contributor guide when working inside a component.

## Boundaries

- Treat each plugin, preset, style, or validator as an independently maintained and independently released component.
- Keep changes scoped to the requested component. Do not change another component's source, version, changelog, or stable feed as incidental cleanup.
- There is no repository-wide product version. Do not add a root version or bump unrelated component versions.
- Preserve component-specific licenses, copyright headers, attribution, bundled notices, plugin identities, and JOSM-facing metadata.
- Do not move, rename, or remove a component's public JOSM identity or stable URL without explicit authorization.
- When changing shared configuration or workflows, consider all affected components and document the impact.

## Implementation and validation

- Follow each component's pinned toolchain and documented validation commands; do not infer a single root runtime or build command.
- Run the smallest applicable checks, then any broader checks required by shared changes. Report checks that could not be run.
- Update the component's documentation and changelog for user-visible changes. Keep root documentation for repository-wide policy and workflow only.
- Avoid adding a root dependency or abstraction when a component-local tool or simple documented convention is sufficient.
- Preserve formatting and conventions already used in the affected component. Keep code comments rare and limited to non-obvious behavior.
- For user-facing behavior, consider keyboard operation, clear feedback, and accessibility; apply WCAG 2.2 AA where web interfaces are involved.

## Releases and external changes

- Never publish a release, promote a candidate to stable, update a stable JOSM feed, or submit a catalog entry unless the user explicitly requests that action.
- Never edit JOSM-owned wiki pages, catalog source pages, or other JOSM-managed pages. Initial catalog registration and all page edits are exclusively the human maintainer's responsibility, without exception. Do not automate catalog edits or store credentials for them.
- Agents may prepare and validate component files, stable URLs, and instructions for the human maintainer, but must not submit or modify JOSM catalog entries.
- A release must target one component and use that component's documented version and changelog. Do not silently rebuild or promote a different commit than the one validated.
- Stable feed writes must be limited to the selected component. Candidate releases must not affect stable URLs.
- Use least-privilege permissions for automation. Do not add or expose credentials; report missing secrets explicitly.
- Do not delete or archive source repositories as part of a code change. Treat repository retirement as a separate, explicitly authorized step after migration verification.

## AI workshop roles

Use the workshop metaphor to make responsibilities easier to discover, not to blur ownership:

- The **master artificer** coordinates repository-wide work and shared systems.
- A **craft steward** focuses on one category: plugins, presets, styles, or validators.
- The repository will provide one master profile and one custom profile per category. It will not provide a dedicated agent profile for every component.

Category agents may work on individual components within their category, but must honor component boundaries and assigned file scope. These profiles are working guidance, not security boundaries. Agents must honor human approval for releases. When multiple agents or chats work in parallel, assign non-overlapping paths; serialize edits to shared root files and release feeds.

## Collaboration conventions

- Use Conventional Commits (`type(scope): description`) and branches named `type/scope/short-description`.
- Keep pull requests focused and describe behavior changes, affected components, validation, and release impact.
- Do not create commits unless the user explicitly requests one.
- Do not assume `local-storage/` exists for other contributors; it is local-only and ignored.
