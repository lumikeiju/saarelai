---
name: josm-validation-update
description: "Use when changing Saarelai's pinned JOSM release for shared preset, MapCSS style, or validator validation. Verify the official release asset and checksum, update the registry, workflow caches, docs and craft-steward guidance, then validate every affected resource."
---

# Updating Saarelai's JOSM validation release

Use this skill when the user asks to update the JOSM version used by Saarelai's shared resource validator. The normal scope is the repository-wide parser pin, not a component release.

## Keep version roles distinct

- `catalog/components.json`'s `josmValidation` entry selects the JOSM parser/runtime used to validate presets, styles, and validator rules.
- Plugin `josmCompileVersion` values select a separate Gradle compile dependency, currently resolved from the JOSM Maven repository.
- Plugin `minJosmVersion` / manifest minimums and MapCSS `min-josm-version` fields describe component compatibility. Do not raise them just because the shared validation runtime changes.
- Do not change a component's own version, changelog, stable feed, or JOSM catalog entry as part of this shared update.
- Do not commit, publish a component release, or promote anything to stable unless the user explicitly requests it.

If the request is ambiguous about changing plugin compile dependencies or component minimums, keep the default scope to the shared validation pin and explain what remains separate.

## Procedure

1. Read the repository `AGENTS.md`, `CONTRIBUTING.md`, and `catalog/README.md`. Inspect the working tree and the current `josmValidation` entry, all `josm-core-*` cache keys, and any steward profiles that name the current parser version. Preserve unrelated worktree changes.
2. Resolve the requested version from the official JOSM release URL. Verify the exact tag, prerelease status, publication date, and assets using the GitHub CLI:

   ```powershell
   $version = "19646"
   gh release view $version --repo JOSM/josm --json tagName,isPrerelease,publishedAt,url,assets
   gh api "repos/JOSM/josm/releases/tags/$version" --jq '.assets[] | {name,size,digest,browser_download_url}'
   ```

   Select an official JAR compatible with the target Java version in `components.json` and the runner operating system in the validation/release workflows. Prefer an immutable, versioned release asset with an official SHA-256 digest. Record whether the tag is a prerelease.
3. Check whether the requested version is available from the JOSM Maven repository before considering any plugin compile-version changes. If it is not published there, leave `josmCompileVersion` unchanged; do not point Gradle at a nonexistent artifact or silently substitute a snapshot or mutable download.
4. Update only the shared `josmValidation` version, artifact URL, and SHA-256 in `catalog/components.json`. Refresh every corresponding `josm-core-<version>-<sha256>` cache key in the release and validation workflows. Update `catalog/README.md` and any craft-steward profile that states the previous parser version or artifact source. Keep compatibility minimums and component versions untouched.
5. Verify the JSON parses, the digest is 64 lowercase hexadecimal characters, all workflow cache keys exactly match the registry pin, and no stale cache key remains. Do not replace every occurrence of the old JOSM number: some occurrences are intentional minimum-version metadata, Gradle compile pins, or test fixtures.
6. Run the release utility tests:

   ```powershell
   python -m unittest discover -s scripts/tests -v
   ```

7. Validate every non-plugin component registered in `catalog/components.json` against the new pin using `python scripts/deploy.py validate --component <component-id>`. Enumerate the current preset, style, and validator IDs from the registry rather than assuming the component count. The validator downloads the pinned JAR and checks its SHA-256 before invoking JOSM. Do not use `--for-release` for this baseline check; it also requires release metadata unrelated to the parser update.
8. Run `git diff --check`. Report the selected release and asset, prerelease status, checksum verification, test and component-validation results, and any compile/minimum-version pins intentionally left unchanged. Do not claim GitHub Actions passed unless they actually ran.

If no official, versioned JAR suitable for the target runner/runtime is available, stop before editing and report the blocker instead of using an unpinned or platform-incompatible artifact.
