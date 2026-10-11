---
description: Generate a changelog entry from recent commits and prepend it to CHANGELOG.md
---

<!-- @format -->

# Generate ImproveNode Changelog Entry

Generate a new `CHANGELOG.md` entry from commits since the last release. Project uses [Conventional Commits](https://www.conventionalcommits.org/) + [SemVer](https://semver.org/) + [Keep a Changelog](https://keepachangelog.com/).

## 1. Collect ImproveNode commits

From the Saarelai repository root (PowerShell), filter both the namespaced ImproveNode tags and the component path so another component's release or commits are not included:

```powershell
$lastTag = git describe --tags --abbrev=0 --match 'plugins/improve-node/v*' 2>$null
if ($LASTEXITCODE -eq 0) {
    git log "$lastTag..HEAD" --pretty=format:"%s" -- plugins/improve-node
} else {
    git log --pretty=format:"%s" -- plugins/improve-node
}
```

Treat the `version` in `plugins/improve-node/gradle.properties` as the authoritative current version. Historical standalone-repository tags are not present in Saarelai; use this component's existing changelog as its history baseline.

## 2. Choose the next version

Apply the **highest** applicable bump:

- `BREAKING CHANGE` footer or `!` suffix (e.g. `feat!:`) → **major**.
- Any `feat(...)` → **minor**.
- Only `fix`/`chore`/`docs`/`test`/`ci` → **patch**.

State the chosen version explicitly.

## 3. Build the entry

**Include:** `feat` → Features, `fix` → Fixes. **Omit** everything else (chore, docs, version bumps, merges).

**Write:**

- Rewrite terse subjects into plain English.
- Keep scope when it adds context, e.g. `(carousel)`.
- Group related commits into one bullet.
- Omit any empty section heading.

**Template:**

```markdown
## vX.Y.Z (YYYY-MM-DD)

### Features

- Description

### Fixes

- Description
```

## 4. Apply edits

- If an `## Unreleased` section exists in `plugins/improve-node/CHANGELOG.md`, replace it with the versioned entry (use its content as additional input when building step 3, then remove the section).
- Otherwise, prepend the entry after the header block, before the first existing `## v` entry.
- Do not touch any existing versioned entries.
- Update `version` in `plugins/improve-node/gradle.properties` only as part of an explicitly requested release preparation.
- Do not edit another component's changelog or version.

## 5. Report back

1. New version.
2. The inserted entry verbatim.
3. Commits omitted by inclusion rules (one line).
4. Commits that looked miscategorized (type vs actual change).
