# Component releases

`components.json` is the source of truth for component IDs, native version selectors, build artifacts, and persistent `stable` branch paths. It deliberately does not contain component version values.

## Validation and build runtimes

The supported JOSM runtime target is Azul Java 25. JOSM's plugin Gradle integration is pinned to Gradle 8.14.5/JOSM Gradle plugin 0.8.2, so the Windows build workflow runs Gradle on JDK 21. The Java plugins compile to Java 21 bytecode and declare `Plugin-Minimum-Java-Version: 25` in their JAR manifests. This lets the existing build stack remain stable while JOSM runs the plugins on Java 25.

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

## Candidate and stable process

Use **Actions → Component release → Run workflow**:

1. Choose a category-qualified component ID and `candidate`. For a preset, also enter `stable_date` as the planned stable publication date.
2. Candidate builds validate only that component, package the exact stable-feed payload, and attach the package, SHA-256 sidecar, and provenance JSON to a namespaced prerelease:
   `<category>/<component-id>/v<version>-rc.<run-number>.<attempt>`.
3. Test the candidate artifact in JOSM 25.
4. Run the workflow again with `promote-stable`, the same component ID, the exact candidate tag, and—if it is a preset—the same `stable_date`. It must match the date recorded in both the committed preset version and candidate provenance. Check the explicit approval box after testing the exact candidate.
5. Stable promotion validates the candidate commit and checksums without rebuilding. It creates a draft versioned release, commits only the selected component's persistent paths to the `stable` branch, pushes that branch without force, then publishes the release. The job is serialized across all components and can safely be retried if it stops between feed publication and release publication.

Candidate jobs never write to the `stable` branch. Stable promotion refuses stale/equal versions with changed bytes, partial component feeds, out-of-component paths, invalid candidate tags, and candidate packages that do not match their provenance. The workflow does not bump versions or edit component source/changelogs; prepare and commit those changes before dispatching a candidate.

When adding a new persistent file to an already released component, set `introducedInVersion` on its `stableFiles` entry to the first component version that includes it. Promotion may then add that path when the current stable version predates it; missing files that were already required at the current stable version are still rejected as an incomplete feed.

For a preset's first stable release, use `1.0_YYYY-MM-DD` in its root `version` attribute, with the actual planned publication date. If that date changes before stable promotion, update and commit the version, then prepare a new candidate; candidates cannot be retagged or have their payload rewritten during promotion.

## GitHub configuration and JOSM onboarding

- Create a GitHub environment named `stable` before using stable promotion. This deployment environment is separate from the Git branch also named `stable`. For this solo-maintainer setup, do not require environment reviewers; the workflow is manually dispatched and requires the `confirm_stable` checkbox after you test the exact candidate. Optionally restrict deployments to `main`. If other maintainers later become available, add required reviewers.
- Require the `Component validation gate` status check on `main`; it aggregates utility tests and only the affected component matrix jobs.
- If branch rules protect `stable`, permit the workflow's `GITHUB_TOKEN` to create fast-forward commits while preventing force-pushes and deletion. The workflow serializes all stable writes and never force-pushes.
- Ensure the default branch, `stable`, tags, and GitHub releases are public so JOSM can retrieve the unauthenticated raw URLs.
- The first successful stable promotion initializes the otherwise empty `stable` branch. There is no release or feed branch created by adding these utilities.
- `plugins/<id>/list` is the persistent plugin-update-site URL; the plugin JAR is adjacent to it. Presets, styles, and validators use the stable file path recorded in `components.json`.
- Copy those stable URLs into the relevant JOSM catalog through the maintainer's normal process. **Humans only** register or edit catalog pages. The workflow never writes JOSM pages or submits catalog entries.
- Do not dispatch a stable action until the selected component has a valid version/changelog and the candidate has been tested. No release is triggered automatically on a push, tag, or pull request.

The release utility tests run with:

```powershell
python -m unittest discover -s scripts/tests -v
```
