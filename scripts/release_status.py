#!/usr/bin/env python3
"""Update the component release summary from published GitHub Releases."""

from __future__ import annotations

import datetime as dt
import json
import os
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from functools import cmp_to_key
from pathlib import Path
from typing import Any
from urllib.parse import quote

import deploy


ROOT = Path(__file__).resolve().parents[1]
DOCUMENT_PATH = ROOT / "catalog" / "README.md"
START_MARKER = "<!-- release-status:start -->"
END_MARKER = "<!-- release-status:end -->"
CANDIDATE_SUFFIX_RE = re.compile(r"^(?P<version>.+)-rc\.(?P<run>\d+)\.(?P<attempt>\d+)$")
GITHUB_API = "https://api.github.com"


class ReleaseStatusError(Exception):
    """An actionable error while fetching or rendering release status."""


@dataclass(frozen=True)
class ReleaseInfo:
    tag: str
    version: str
    updated_at: dt.datetime
    run_number: int | None = None
    run_attempt: int | None = None


def fetch_releases(repository: str, token: str | None = None) -> list[dict[str, Any]]:
    releases: list[dict[str, Any]] = []
    page = 1
    while True:
        request = urllib.request.Request(
            f"{GITHUB_API}/repos/{repository}/releases?per_page=100&page={page}",
            headers={
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                **({"Authorization": f"Bearer {token}"} if token else {}),
            },
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.loads(response.read().decode("utf-8"))
        if not isinstance(result, list) or any(not isinstance(item, dict) for item in result):
            raise ReleaseStatusError("GitHub releases API returned an unexpected response.")
        releases.extend(result)
        if len(result) < 100:
            return releases
        page += 1


def _parse_updated_at(value: Any, tag: str) -> dt.datetime:
    if not isinstance(value, str):
        raise ReleaseStatusError(f"Release {tag!r} has no valid updated_at timestamp.")
    try:
        updated_at = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ReleaseStatusError(
            f"Release {tag!r} has an invalid updated_at timestamp."
        ) from error
    if updated_at.tzinfo is None:
        raise ReleaseStatusError(f"Release {tag!r} has a timezone-free updated_at timestamp.")
    return updated_at


def _release_for_component(
    component_id: str, release: dict[str, Any]
) -> tuple[ReleaseInfo, bool] | None:
    tag = release.get("tag_name")
    if not isinstance(tag, str):
        raise ReleaseStatusError("GitHub releases API returned a release without a tag_name.")
    prefix = f"{component_id}/v"
    if not tag.startswith(prefix) or release.get("draft") is True:
        return None
    is_prerelease = release.get("prerelease")
    if not isinstance(is_prerelease, bool):
        raise ReleaseStatusError(f"Release {tag!r} has no valid prerelease flag.")

    suffix = tag[len(prefix) :]
    candidate = CANDIDATE_SUFFIX_RE.fullmatch(suffix)
    if is_prerelease:
        if candidate is None or not deploy.VERSION_RE.fullmatch(candidate["version"]):
            return None
        info = ReleaseInfo(
            tag=tag,
            version=candidate["version"],
            updated_at=_parse_updated_at(release.get("updated_at"), tag),
            run_number=int(candidate["run"]),
            run_attempt=int(candidate["attempt"]),
        )
        return info, True

    if candidate is not None or not deploy.VERSION_RE.fullmatch(suffix):
        return None
    return (
        ReleaseInfo(
            tag=tag,
            version=suffix,
            updated_at=_parse_updated_at(release.get("updated_at"), tag),
        ),
        False,
    )


def _compare_releases(left: ReleaseInfo, right: ReleaseInfo, candidate: bool) -> int:
    version_order = deploy.compare_versions(left.version, right.version)
    if version_order:
        return version_order
    if candidate:
        left_run = (left.run_number, left.run_attempt)
        right_run = (right.run_number, right.run_attempt)
        if left_run != right_run:
            return 1 if left_run > right_run else -1
    if left.updated_at == right.updated_at:
        return 0
    return 1 if left.updated_at > right.updated_at else -1


def _release_cell(
    release: ReleaseInfo | None, repository: str, candidate: bool = False
) -> tuple[str, str]:
    if release is None:
        return "N/A", "N/A"
    label = release.version
    if candidate:
        label += f"-rc.{release.run_number}.{release.run_attempt}"
    url = f"https://github.com/{repository}/releases/tag/{quote(release.tag, safe='')}"
    return f"[{label}]({url})", release.updated_at.date().isoformat()


def render_table(
    components: list[dict[str, Any]],
    releases: list[dict[str, Any]],
    repository: str,
) -> str:
    lines = [
        "| Component | Stable version | Stable updated | Candidate version | Candidate updated |",
        "| --- | --- | --- | --- | --- |",
    ]
    for component in components:
        component_id = component["id"]
        stable: list[ReleaseInfo] = []
        candidates: list[ReleaseInfo] = []
        for release in releases:
            parsed = _release_for_component(component_id, release)
            if parsed is None:
                continue
            info, is_candidate = parsed
            (candidates if is_candidate else stable).append(info)

        latest_stable = (
            max(stable, key=cmp_to_key(lambda left, right: _compare_releases(left, right, False)))
            if stable
            else None
        )
        latest_candidate = (
            max(
                candidates,
                key=cmp_to_key(lambda left, right: _compare_releases(left, right, True)),
            )
            if candidates
            else None
        )
        stable_cell, stable_date = _release_cell(latest_stable, repository)
        candidate_cell, candidate_date = _release_cell(
            latest_candidate, repository, candidate=True
        )
        lines.append(
            f"| `{component_id}` | {stable_cell} | {stable_date} "
            f"| {candidate_cell} | {candidate_date} |"
        )
    return "\n".join(lines)


def update_document(path: Path, table: str) -> bool:
    document = path.read_text(encoding="utf-8")
    if document.count(START_MARKER) != 1 or document.count(END_MARKER) != 1:
        raise ReleaseStatusError(f"{path} must contain exactly one pair of release-status markers.")
    start = document.index(START_MARKER)
    end = document.index(END_MARKER)
    if end < start:
        raise ReleaseStatusError(f"{path} has release-status markers in the wrong order.")
    replacement = f"{START_MARKER}\n{table}\n{END_MARKER}"
    updated = document[:start] + replacement + document[end + len(END_MARKER) :]
    if updated == document:
        return False
    path.write_text(updated, encoding="utf-8")
    return True


def main() -> int:
    try:
        registry = deploy.load_registry()
        releases = fetch_releases(registry["repository"], os.environ.get("GH_TOKEN"))
        changed = update_document(
            DOCUMENT_PATH,
            render_table(registry["components"], releases, registry["repository"]),
        )
    except (
        OSError,
        json.JSONDecodeError,
        urllib.error.URLError,
        deploy.ReleaseError,
        ReleaseStatusError,
    ) as error:
        print(f"Release status update failed: {error}", file=sys.stderr)
        return 1
    print("Updated the component release summary." if changed else "Release summary is already current.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
