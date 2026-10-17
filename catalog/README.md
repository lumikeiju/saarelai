# Component releases

`components.json` is the source of truth for component IDs, native version selectors, build artifacts, and persistent feed paths. `beta` and `stable` use the same component-relative paths on separate output-only branches. It deliberately does not contain component version values.

## Latest stable and beta releases

This table is updated by the **Component release** workflow after publishing a GitHub Release. Dates show when GitHub last updated each release. Missing releases are shown as `N/A`.

<!-- release-status:start -->
| Component | Stable version | Stable updated | Beta version | Beta updated |
| --- | --- | --- | --- | --- |
| `plugins/arkki-snappi` | N/A | N/A | [1.0.0-beta.15.1](https://github.com/lumikeiju/saarelai/releases/tag/plugins%2Farkki-snappi%2Fv1.0.0-beta.15.1) | 2026-10-17 |
| `plugins/easy-pan` | N/A | N/A | [1.0.0-beta.16.1](https://github.com/lumikeiju/saarelai/releases/tag/plugins%2Feasy-pan%2Fv1.0.0-beta.16.1) | 2026-10-17 |
| `plugins/improve-node` | [1.0.0](https://github.com/lumikeiju/saarelai/releases/tag/plugins%2Fimprove-node%2Fv1.0.0) | 2026-10-12 | [1.0.1-beta.17.1](https://github.com/lumikeiju/saarelai/releases/tag/plugins%2Fimprove-node%2Fv1.0.1-beta.17.1) | 2026-10-17 |
| `presets/3d-building-details` | N/A | N/A | N/A | N/A |
| `presets/lgbtq-plus` | N/A | N/A | N/A | N/A |
| `presets/public-transport` | N/A | N/A | N/A | N/A |
| `presets/road-marking` | N/A | N/A | N/A | N/A |
| `presets/rooftop-solar-panel` | N/A | N/A | N/A | N/A |
| `presets/seattle-city-light-pole` | N/A | N/A | N/A | N/A |
| `presets/stops-and-signs` | N/A | N/A | N/A | N/A |
| `presets/trees` | N/A | N/A | N/A | N/A |
| `presets/us-wa-addr` | N/A | N/A | N/A | N/A |
| `presets/us-wa-gtfs` | N/A | N/A | N/A | N/A |
| `styles/tiger-stripes` | [1.0.2](https://github.com/lumikeiju/saarelai/releases/tag/styles%2Ftiger-stripes%2Fv1.0.2) | 2026-10-12 | [1.0.2-rc.9.1](https://github.com/lumikeiju/saarelai/releases/tag/styles%2Ftiger-stripes%2Fv1.0.2-rc.9.1) | 2026-10-12 |
| `validators/contact-prefix` | N/A | N/A | N/A | N/A |
| `validators/public-transport` | N/A | N/A | N/A | N/A |
| `validators/route-ref-reformatter` | N/A | N/A | N/A | N/A |
| `validators/seattle-city-light-pole` | N/A | N/A | N/A | N/A |
| `validators/tiger-cleanup` | N/A | N/A | N/A | N/A |
| `validators/typo-fixer` | N/A | N/A | N/A | N/A |
<!-- release-status:end -->

## Validation and build runtimes

The supported JOSM runtime target is Azul Java 25. Plugin builds use their Gradle 8.x wrappers with JOSM Gradle plugin 0.8.2 on JDK 21. The Java plugins compile to Java 21 bytecode and declare `Plugin-Minimum-Java-Version: 25` in their JAR manifests. This lets the existing build stack remain stable while JOSM runs the plugins on Java 25.

Resource validation runs JOSM 19646's preset and MapCSS parsers on Java 25 using the Windows Java 25 JAR from the [official JOSM 19646 GitHub prerelease](https://github.com/JOSM/josm/releases/tag/19646). Its SHA-256 is pinned in `components.json`.

Local utilities require Python 3.12+ (standard library only) and PowerShell 7. For local Windows plugin builds, install JDK 21 and run:

```powershell
.\scripts\Invoke-ComponentBuild.ps1 -Component plugins/arkki-snappi
```

For a resource:

```powershell
python scripts\deploy.py validate --component validators/typo-fixer
```

The JOSM core validation JAR is cached under `.cache/saarelai/`; set `SAARELAI_CACHE_DIR` to use another ignored cache directory.

## Beta and stable process

Use **Actions → Component release → Run workflow**:

1. Create a feature branch from `main`, prepare the component's version/changelog, and merge the reviewed changes back to `main`. Source code stays on `main`; never merge it into the feed branches.
2. Select `main` in **Run workflow**, choose a category-qualified component ID and the `beta` action. For a preset, also enter `stable_date` as the planned stable publication date.
3. Beta builds validate only that component, package the exact stable-feed payload, and attach the package, SHA-256 sidecar, and provenance JSON to a GitHub **Pre-release**:
   `<category>/<component-id>/v<version>-beta.<run-number>.<attempt>`.
   The workflow also commits and pushes only that component's outputs to `beta`, initializing an empty output-only branch on first use. Plugin lists point to the adjacent JAR on `beta`; the checksummed promotion package retains stable URLs. JARs and resource bytes are unchanged.
4. Test the beta artifact in JOSM 25 using the beta URLs below.
5. Run the workflow again from `main` with `promote-stable`, the same component ID, the exact beta tag in `candidate_tag`, and—if it is a preset—the same `stable_date`. It must match the date recorded in both the committed preset version and provenance. Check the explicit approval box after testing the exact beta. The `candidate_tag` input and package/provenance field names are retained for compatibility, including promotion of existing `-rc` prereleases.
6. Stable promotion checks out and validates the beta's source commit and checksums without rebuilding. It creates a draft versioned release, commits only the selected component's persistent paths to `stable`, pushes that branch without force, then publishes the release. Writes are serialized separately for each feed branch. Stable publication can safely be retried if it stops between feed publication and release publication.

Beta jobs never write to `stable`. Stable promotion refuses stale/equal versions with changed bytes, partial component feeds, out-of-component paths, invalid tags, and packages that do not match their provenance. Beta feeds allow replacement of the same native version for iterative testing, but reject older versions. The workflow does not bump versions or edit component source/changelogs; prepare and commit those changes before dispatching a beta. A beta prerelease may exist if the subsequent feed push fails; do not test via the feed until its workflow succeeds. Rerunning beta creation produces a new attempt-specific tag.

### Testing beta feeds in JOSM

Beta output browsing example: <https://github.com/lumikeiju/saarelai/tree/beta/plugins/improve-node>.

- For plugins, add the component's raw beta list URL in JOSM's plugin update-site preferences, for example `https://raw.githubusercontent.com/lumikeiju/saarelai/beta/plugins/improve-node/list`, then refresh the available plugins and install/update the plugin. Remove or disable the stable update site for the same plugin while testing.
- For presets, styles, and validators, configure the raw URL using the path in `stableFiles` with the branch changed to `beta`, for example `https://raw.githubusercontent.com/lumikeiju/saarelai/beta/styles/tiger-stripes/tiger-stripes.mapcss`. Bundled assets keep the same relative paths.
- A feed push makes outputs available; it does not automatically configure JOSM or register a catalog entry. Restart/reload as appropriate. Beta and stable share the component's native version and plugin identity; a different GitHub beta tag alone does not trigger JOSM's version-based updater. Bump native versions for automatic update detection, or explicitly reinstall/reload when testing successive betas with the same native version.
- The beta URL tracks the latest published beta for that component. To test an older tag, use its release package; for plugins install its JAR directly rather than using the package's stable-targeted list.

When adding a new persistent file to an already released component, set `introducedInVersion` on its `stableFiles` entry to the first component version that includes it. Promotion may then add that path when the current stable version predates it; missing files that were already required at the current stable version are still rejected as an incomplete feed.

To retire an existing stable-feed file, list its component-scoped path in `retiredStablePaths`. Stable promotion removes that file while publishing the selected component's new payload.

For a preset's first stable release, use `1.0_YYYY-MM-DD` in its root `version` attribute, with the actual planned publication date. If that date changes before stable promotion, update and commit the version, then prepare a new candidate; candidates cannot be retagged or have their payload rewritten during promotion.

## GitHub configuration and JOSM onboarding

- Configure GitHub environments named `beta` and `stable`, separate from their Git branches. For this solo-maintainer setup, do not require environment reviewers; the workflow is manually dispatched and requires `confirm_stable` after you test the exact beta. Dispatch from `main`; the stable job checks out the chosen tag internally. If other maintainers later become available, add required reviewers.
- Require the `Component validation gate` status check on `main`; it aggregates utility tests and only the affected component matrix jobs.
- The **Component release** workflow commits the generated release table to the default branch. If branch rules protect that branch, allow the workflow's `GITHUB_TOKEN` to push its documentation update.
- If branch rules protect `beta` or `stable`, permit the workflow's `GITHUB_TOKEN` to create the branches and fast-forward commits while preventing force-pushes and deletion. The workflow serializes feed writes and never force-pushes.
- Keep `main` as the default branch. Ensure `main`, `beta`, `stable`, tags, and GitHub releases are public so JOSM can retrieve unauthenticated raw URLs.
- The first beta publication and first stable promotion initialize their respective output-only branches. Adding these utilities does not itself create branches or publish anything.
- `plugins/<id>/list` is the persistent plugin-update-site URL; the plugin JAR is adjacent to it. Presets, styles, and validators use the stable file path recorded in `components.json`.
- Copy those stable URLs into the relevant JOSM catalog through the maintainer's normal process. **Humans only** register or edit catalog pages. The workflow never writes JOSM pages or submits catalog entries.
- Do not dispatch a stable action until the selected component has a valid version/changelog and the candidate has been tested. No release is triggered automatically on a push, tag, or pull request.

The release utility tests run with:

```powershell
python -m unittest discover -s scripts/tests -v
```
