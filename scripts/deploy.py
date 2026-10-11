#!/usr/bin/env python3
"""Build, validate, package, and promote individual Saarelai components."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
REGISTRY_PATH = ROOT / "catalog" / "components.json"
JOSM_VALIDATOR = ROOT / "scripts" / "java" / "JosmResourceValidator.java"
VERSION_RE = re.compile(r"^[0-9]+(?:\.[0-9]+)*(?:_[0-9]{4}-[0-9]{2}-[0-9]{2})?$")
PRESET_VERSION_RE = re.compile(r"^[0-9]+(?:\.[0-9]+)*_[0-9]{4}-[0-9]{2}-[0-9]{2}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
GIT_SHA_RE = re.compile(r"^[0-9a-f]{40}$")
MAX_RELEASE_FILE_BYTES = 64 * 1024 * 1024
MAX_RELEASE_PACKAGE_BYTES = 200 * 1024 * 1024


class ReleaseError(Exception):
    """An actionable release validation or deployment error."""


def safe_relative_path(value: str) -> PurePosixPath:
    if not isinstance(value, str) or not value or "\\" in value or ":" in value:
        raise ReleaseError(f"Invalid repository-relative path: {value!r}")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ("", ".", "..") for part in path.parts):
        raise ReleaseError(f"Unsafe repository-relative path: {value!r}")
    return path


def contained_path(root: Path, value: str) -> Path:
    relative = safe_relative_path(value)
    base = root.resolve()
    lexical = base
    for part in relative.parts:
        lexical = lexical / part
        if lexical.is_symlink():
            raise ReleaseError(f"Paths through symbolic links are not allowed: {value}")
    result = base.joinpath(*relative.parts).resolve()
    if not result.is_relative_to(base):
        raise ReleaseError(f"Path escapes its allowed root: {value}")
    return result


def run_git(root: Path, *args: str, input_text: str | None = None, check: bool = True) -> str:
    process = subprocess.run(
        ["git", "-C", str(root), *args],
        input=input_text,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check and process.returncode != 0:
        detail = process.stderr.strip() or process.stdout.strip()
        raise ReleaseError(f"git {' '.join(args)} failed: {detail}")
    if not check and process.returncode != 0:
        return ""
    return process.stdout.strip()


def load_registry(path: Path = REGISTRY_PATH) -> dict[str, Any]:
    try:
        registry = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReleaseError(f"Cannot read component registry {path}: {error}") from error

    if registry.get("schemaVersion") != 1:
        raise ReleaseError("Unsupported component registry schemaVersion.")
    if registry.get("buildJavaVersion") != 21 or registry.get("targetJosmJavaVersion") != 25:
        raise ReleaseError("Registry Java policy must keep Gradle on Java 21 and target JOSM on Java 25.")
    stable_branch = registry.get("stableBranch")
    if not isinstance(stable_branch, str) or not re.fullmatch(r"[A-Za-z0-9._-]+", stable_branch):
        raise ReleaseError("Registry stableBranch is invalid.")
    raw_base = registry.get("stableRawBaseUrl", "")
    if not raw_base.startswith("https://raw.githubusercontent.com/"):
        raise ReleaseError("stableRawBaseUrl must be a public raw.githubusercontent.com URL.")

    josm_validation = registry.get("josmValidation", {})
    if (
        not isinstance(josm_validation.get("version"), int)
        or not SHA256_RE.fullmatch(josm_validation.get("sha256", ""))
        or not josm_validation.get("artifactUrl", "").startswith("https://")
    ):
        raise ReleaseError("Registry JOSM validation artifact metadata is invalid.")

    components = registry.get("components")
    if not isinstance(components, list) or not components:
        raise ReleaseError("Registry components must be a non-empty array.")

    ids: set[str] = set()
    stable_paths: set[str] = set()
    for component in components:
        component_id = component.get("id", "")
        path = component.get("path", "")
        if component_id in ids:
            raise ReleaseError(f"Duplicate component id: {component_id}")
        ids.add(component_id)
        if component_id != path:
            raise ReleaseError(f"Component path must match its category-qualified id: {component_id}")
        safe_relative_path(path)
        category, separator, _ = component_id.partition("/")
        if not separator or category not in {"plugins", "presets", "styles", "validators"}:
            raise ReleaseError(f"Invalid category-qualified component id: {component_id}")
        if component.get("kind") != category.rstrip("s") and not (
            category == "plugins" and component.get("kind") == "plugin"
        ):
            raise ReleaseError(f"Component kind/category mismatch: {component_id}")

        files = component.get("stableFiles")
        if not isinstance(files, list) or not files:
            raise ReleaseError(f"Component has no stableFiles entries: {component_id}")
        local_paths: set[str] = set()
        for item in files:
            feed_path = safe_relative_path(item.get("path", "")).as_posix()
            if not feed_path.startswith(component_id + "/"):
                raise ReleaseError(f"Stable path is outside its component: {feed_path}")
            if feed_path in stable_paths or feed_path in local_paths:
                raise ReleaseError(f"Duplicate stable path: {feed_path}")
            stable_paths.add(feed_path)
            local_paths.add(feed_path)
            source_kind = item.get("sourceKind")
            if source_kind not in {"file", "plugin-list"}:
                raise ReleaseError(f"Invalid stable file sourceKind in {component_id}: {source_kind}")
            if source_kind == "file":
                safe_relative_path(item.get("sourcePath", ""))
        if component["kind"] == "plugin":
            build = component.get("build", {})
            if build.get("tool") != "gradle-wrapper" or build.get("tasks") != ["clean", "build"]:
                raise ReleaseError(f"Unsupported plugin build declaration: {component_id}")
            if component.get("validation") != "plugin-jar-manifest":
                raise ReleaseError(f"Unsupported plugin validator declaration: {component_id}")
            safe_relative_path(component.get("artifactPath", ""))
            if not component.get("pluginMainClass"):
                raise ReleaseError(f"Plugin main class is missing in registry: {component_id}")
            if sum(item.get("sourceKind") == "plugin-list" for item in files) != 1:
                raise ReleaseError(f"Plugin must declare exactly one generated update-site list: {component_id}")
        else:
            safe_relative_path(component.get("sourceFile", ""))
            expected_validation = {
                "preset": "josm-preset",
                "style": "josm-mapcss-style",
                "validator": "josm-mapcss-validator",
            }[component["kind"]]
            if component.get("validation") != expected_validation:
                raise ReleaseError(f"Unsupported resource validator declaration: {component_id}")
            if component.get("versionSource", {}).get("kind") not in {
                "preset-root-attribute",
                "mapcss-meta",
            }:
                raise ReleaseError(f"Invalid resource version source: {component_id}")

    return registry


def get_component(registry: dict[str, Any], component_id: str) -> dict[str, Any]:
    matches = [component for component in registry["components"] if component["id"] == component_id]
    if len(matches) != 1:
        raise ReleaseError(f"Unknown component id: {component_id}")
    return matches[0]


def component_root(component: dict[str, Any], root: Path | None = None) -> Path:
    return contained_path(root or ROOT, component["path"])


def parse_plugin_manifest(content: bytes) -> dict[str, str]:
    try:
        lines = content.decode("utf-8").replace("\r\n", "\n").split("\n")
    except UnicodeDecodeError as error:
        raise ReleaseError("Plugin JAR manifest is not UTF-8.") from error
    attributes: dict[str, str] = {}
    current_key: str | None = None
    for line in lines:
        if not line:
            break
        if line.startswith(" "):
            if current_key is None:
                raise ReleaseError("Plugin manifest starts with an invalid continuation line.")
            attributes[current_key] += line[1:]
            continue
        if ": " not in line:
            raise ReleaseError(f"Invalid plugin manifest line: {line!r}")
        key, value = line.split(": ", 1)
        current_key = key
        attributes[key] = value
    return attributes


def read_jar_manifest(jar_path: Path) -> tuple[dict[str, str], bytes]:
    try:
        with zipfile.ZipFile(jar_path) as archive:
            content = archive.read("META-INF/MANIFEST.MF")
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        raise ReleaseError(f"Cannot read JOSM plugin JAR manifest {jar_path}: {error}") from error
    return parse_plugin_manifest(content), content


def class_file_major_versions(jar_path: Path) -> list[int]:
    versions: list[int] = []
    try:
        with zipfile.ZipFile(jar_path) as archive:
            for name in archive.namelist():
                if not name.endswith(".class"):
                    continue
                header = archive.read(name)[:8]
                if len(header) != 8 or header[:4] != b"\xca\xfe\xba\xbe":
                    raise ReleaseError(f"Malformed Java class file in plugin JAR: {name}")
                versions.append(int.from_bytes(header[6:8], "big"))
    except (OSError, zipfile.BadZipFile) as error:
        raise ReleaseError(f"Cannot inspect Java classes in {jar_path}: {error}") from error
    if not versions:
        raise ReleaseError(f"Plugin JAR contains no class files: {jar_path}")
    return versions


def _version_is_valid(version: str) -> bool:
    if len(version) > 64 or not VERSION_RE.fullmatch(version):
        return False
    if "_" in version:
        try:
            dt.date.fromisoformat(version.split("_", 1)[1])
        except ValueError:
            return False
    return True


def version_from_bytes(component: dict[str, Any], data: bytes) -> str:
    source = component["versionSource"]
    if source["kind"] == "preset-root-attribute":
        try:
            root = ElementTree.fromstring(data)
        except ElementTree.ParseError as error:
            raise ReleaseError(f"Preset XML is invalid: {error}") from error
        if root.tag.rsplit("}", 1)[-1] != "presets":
            raise ReleaseError("Preset XML root element must be <presets>.")
        version = root.attrib.get(source.get("attribute", "version"), "")
    elif source["kind"] == "mapcss-meta":
        text = data.decode("utf-8-sig")
        match = re.search(r"(?ms)^\s*meta\s*\{([^}]*)\}", text)
        if not match:
            raise ReleaseError("MapCSS file has no meta block.")
        field = re.escape(source.get("field", "version"))
        field_match = re.search(rf'(?m)^\s*{field}\s*:\s*"([^"]+)"\s*;', match.group(1))
        if not field_match:
            raise ReleaseError("MapCSS meta block has no quoted version field.")
        version = field_match.group(1)
        if not re.search(r"(?m)^\s*min-josm-version\s*:\s*\"?[0-9]+", match.group(1)):
            raise ReleaseError("MapCSS meta block has no numeric min-josm-version.")
    else:
        raise ReleaseError(f"Version source cannot be read from bytes: {source['kind']}")

    if not version:
        raise ReleaseError("Component version metadata is missing.")
    if not _version_is_valid(version):
        raise ReleaseError(f"Unsupported component version format: {version}")
    if component["kind"] == "preset" and not PRESET_VERSION_RE.fullmatch(version):
        raise ReleaseError(
            "Preset versions must use the JOSM update-detection format "
            "1.0_YYYY-MM-DD (or a later numeric version with a publication date)."
        )
    return version


def get_component_version(
    component: dict[str, Any], repo_root: Path | None = None, require_release_version: bool = True
) -> str | None:
    repo_root = repo_root or ROOT
    if component["kind"] == "plugin":
        property_file = contained_path(component_root(component, repo_root), component["versionSource"]["file"])
        try:
            lines = property_file.read_text(encoding="utf-8").splitlines()
        except OSError as error:
            raise ReleaseError(f"Cannot read plugin version file {property_file}: {error}") from error
        key = component["versionSource"]["key"]
        values = [line.split("=", 1)[1].strip() for line in lines if line.startswith(key + "=")]
        if len(values) != 1 or not _version_is_valid(values[0]):
            raise ReleaseError(f"Expected one valid {key}= version in {property_file}.")
        return values[0]

    source_file = contained_path(component_root(component, repo_root), component["sourceFile"])
    try:
        data = source_file.read_bytes()
    except OSError as error:
        raise ReleaseError(f"Cannot read component source file {source_file}: {error}") from error
    if component["kind"] == "preset":
        try:
            root = ElementTree.fromstring(data)
        except ElementTree.ParseError as error:
            raise ReleaseError(f"Preset XML is invalid: {error}") from error
        if root.tag.rsplit("}", 1)[-1] != "presets":
            raise ReleaseError("Preset XML root element must be <presets>.")
        version = root.attrib.get(component["versionSource"].get("attribute", "version"))
        if not version and not require_release_version:
            return None
        if not version:
            raise ReleaseError(
                f"{component['id']} is not release-ready: add the preset root version "
                "to the source and commit it before creating a candidate."
            )
    else:
        version = version_from_bytes(component, data)
    if not _version_is_valid(version):
        raise ReleaseError(f"Unsupported component version format in {source_file}: {version}")
    if component["kind"] == "preset" and not PRESET_VERSION_RE.fullmatch(version):
        raise ReleaseError(
            "Preset versions must use the JOSM update-detection format "
            "1.0_YYYY-MM-DD (or a later numeric version with a publication date)."
        )
    return version


def validate_plugin_artifact(
    component: dict[str, Any], jar_path: Path, registry: dict[str, Any], expected_version: str | None = None
) -> dict[str, str]:
    if not jar_path.is_file():
        raise ReleaseError(f"Plugin artifact is missing: {jar_path}; build the selected plugin first.")
    manifest, _ = read_jar_manifest(jar_path)
    version = manifest.get("Plugin-Version")
    if not version or (expected_version is not None and version != expected_version):
        raise ReleaseError(
            f"Plugin-Version in {jar_path.name} does not match component version "
            f"{expected_version!r}: {version!r}"
        )
    if manifest.get("Plugin-Class") != component["pluginMainClass"]:
        raise ReleaseError(
            f"Plugin-Class mismatch in {jar_path.name}: {manifest.get('Plugin-Class')!r}"
        )
    try:
        int(manifest["Plugin-Mainversion"])
    except (KeyError, ValueError) as error:
        raise ReleaseError(f"Plugin-Mainversion is missing or invalid in {jar_path.name}.") from error
    target_java = str(registry["targetJosmJavaVersion"])
    if manifest.get("Plugin-Minimum-Java-Version") != target_java:
        raise ReleaseError(
            f"{jar_path.name} must declare Plugin-Minimum-Java-Version: {target_java}."
        )
    max_class_major = registry["buildJavaVersion"] + 44
    actual_majors = class_file_major_versions(jar_path)
    if max(actual_majors) > max_class_major:
        raise ReleaseError(
            f"{jar_path.name} contains Java class major {max(actual_majors)}; "
            f"the configured Java {registry['buildJavaVersion']} build runtime supports at most "
            f"{max_class_major}."
        )
    return manifest


def josm_cache_path(registry: dict[str, Any], root: Path = ROOT) -> Path:
    override = os.environ.get("SAARELAI_CACHE_DIR")
    cache_root = Path(override) if override else root / ".cache" / "saarelai"
    cache_root.mkdir(parents=True, exist_ok=True)
    return cache_root / f"josm-{registry['josmValidation']['version']}.jar"


def ensure_josm_core(registry: dict[str, Any], root: Path = ROOT) -> Path:
    artifact = registry["josmValidation"]
    path = josm_cache_path(registry, root)
    expected = artifact["sha256"]
    if path.is_file() and sha256_file(path) == expected:
        return path
    partial = path.with_suffix(path.suffix + ".download")
    try:
        request = urllib.request.Request(
            artifact["artifactUrl"],
            headers={"User-Agent": "Saarelai-component-validator/1.0"},
        )
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output)
    except (OSError, urllib.error.URLError) as error:
        partial.unlink(missing_ok=True)
        raise ReleaseError(f"Could not download pinned JOSM core {artifact['version']}: {error}") from error
    actual = sha256_file(partial)
    if actual != expected:
        partial.unlink(missing_ok=True)
        raise ReleaseError(
            f"JOSM core {artifact['version']} checksum mismatch: expected {expected}, got {actual}."
        )
    os.replace(partial, path)
    return path


def validate_with_josm(
    component: dict[str, Any],
    source_file: Path,
    registry: dict[str, Any],
    *,
    parser_kind: str | None = None,
) -> None:
    version = java_runtime_version()
    target = registry["targetJosmJavaVersion"]
    if version < target:
        raise ReleaseError(
            f"Resource validation targets JOSM Java {target}; run this check with Java {target} or newer "
            f"(found Java {version})."
        )
    josm_jar = ensure_josm_core(registry)
    command = [
        "java",
        "--class-path",
        str(josm_jar),
        str(JOSM_VALIDATOR),
        parser_kind or component["kind"],
        str(source_file),
    ]
    process = subprocess.run(command, cwd=ROOT, check=False)
    if process.returncode != 0:
        raise ReleaseError(f"JOSM rejected {component['id']} source: {source_file.name}.")


def java_runtime_version() -> int:
    process = subprocess.run(
        ["java", "-version"], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False
    )
    if process.returncode != 0:
        raise ReleaseError(f"Could not run java -version: {process.stdout.strip()}")
    match = re.search(r'version "([0-9]+)(?:\.|")', process.stdout)
    if not match:
        raise ReleaseError(f"Could not parse Java runtime version: {process.stdout.strip()}")
    return int(match.group(1))


def validate_component(
    component: dict[str, Any],
    registry: dict[str, Any],
    *,
    require_release_version: bool = False,
    run_josm_parser: bool = True,
    repo_root: Path | None = None,
) -> str | None:
    repo_root = repo_root or ROOT
    comp_root = component_root(component, repo_root)
    if not comp_root.is_dir():
        raise ReleaseError(f"Component directory does not exist: {component['path']}")
    changelog = contained_path(comp_root, "CHANGELOG.md")
    if not changelog.is_file():
        raise ReleaseError(f"Component changelog is missing: {component['id']}")

    version = get_component_version(component, repo_root, require_release_version)
    if component["kind"] == "plugin":
        jar_path = contained_path(comp_root, component["artifactPath"])
        expected = version if version is not None else None
        validate_plugin_artifact(component, jar_path, registry, expected)
        if run_josm_parser:
            jar_entry = next(
                item
                for item in component["stableFiles"]
                if item.get("sourcePath") == component["artifactPath"]
            )
            list_entry = next(
                item for item in component["stableFiles"] if item["sourceKind"] == "plugin-list"
            )
            with tempfile.TemporaryDirectory(prefix="saarelai-plugin-list-") as temporary:
                list_path = Path(temporary) / "list"
                list_path.write_bytes(
                    make_plugin_list(component, registry, jar_path, jar_entry["path"])
                )
                validate_with_josm(
                    component, list_path, registry, parser_kind="plugin-list"
                )
    else:
        source_file = contained_path(comp_root, component["sourceFile"])
        if not source_file.is_file():
            raise ReleaseError(f"Component source file is missing: {component['id']}")
        if run_josm_parser:
            validate_with_josm(component, source_file, registry)
    return version


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def manifest_for_jar_bytes(data: bytes) -> dict[str, str]:
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            return parse_plugin_manifest(archive.read("META-INF/MANIFEST.MF"))
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        raise ReleaseError(f"Candidate plugin JAR is invalid: {error}") from error


def make_plugin_list(
    component: dict[str, Any], registry: dict[str, Any], jar_path: Path, stable_jar_path: str
) -> bytes:
    _, manifest_bytes = read_jar_manifest(jar_path)
    manifest_lines = manifest_bytes.decode("utf-8").replace("\r\n", "\n").split("\n")
    main_lines: list[str] = []
    for line in manifest_lines:
        if not line:
            break
        main_lines.append(line)
    download_url = registry["stableRawBaseUrl"].rstrip("/") + "/" + safe_relative_path(
        stable_jar_path
    ).as_posix()
    lines = [f"{Path(stable_jar_path).name};{download_url}"]
    lines.extend("\t" + line for line in main_lines)
    return ("\n".join(lines) + "\n").encode("utf-8")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git_source_state(repo_root: Path) -> tuple[str, str]:
    source_commit = run_git(repo_root, "rev-parse", "HEAD")
    status = run_git(repo_root, "status", "--porcelain", "--untracked-files=all")
    if status:
        raise ReleaseError(
            "Candidate releases require a clean source checkout. Commit the component and release "
            "metadata first; generated build outputs are ignored."
        )
    source_ref = os.environ.get("GITHUB_REF_NAME") or run_git(repo_root, "branch", "--show-current")
    if not source_ref:
        source_ref = "detached"
    return source_commit, source_ref


def prepare_candidate(
    component: dict[str, Any],
    registry: dict[str, Any],
    output: Path,
    run_number: str,
    run_attempt: str,
    source_ref: str | None = None,
    source_commit: str | None = None,
    stable_date: str | None = None,
    repo_root: Path | None = None,
) -> dict[str, Any]:
    repo_root = repo_root or ROOT
    version = validate_component(
        component,
        registry,
        require_release_version=True,
        run_josm_parser=False,
        repo_root=repo_root,
    )
    if not version:
        raise ReleaseError(f"Could not determine release version for {component['id']}.")
    if not run_number.isdigit() or not run_attempt.isdigit():
        raise ReleaseError("Candidate run number and attempt must be numeric.")
    planned_stable_date: str | None = None
    if component["kind"] == "preset":
        if not stable_date:
            raise ReleaseError(
                "Preset candidate creation requires --stable-date matching its root version."
            )
        try:
            planned_stable_date = dt.date.fromisoformat(stable_date).isoformat()
        except ValueError as error:
            raise ReleaseError("--stable-date must use YYYY-MM-DD.") from error
        if version.split("_", 1)[1] != planned_stable_date:
            raise ReleaseError(
                f"Preset root version date {version.split('_', 1)[1]} does not equal "
                f"the planned stable date {planned_stable_date}."
            )
    actual_commit, actual_ref = git_source_state(repo_root)
    if source_commit and source_commit != actual_commit:
        raise ReleaseError("Requested source commit does not match the checked-out source.")
    if source_ref and actual_ref not in (source_ref, "detached"):
        raise ReleaseError("Requested source ref does not match the checked-out source.")
    source_commit = actual_commit
    source_ref = source_ref or actual_ref

    candidate_tag = f"{component['id']}/v{version}-rc.{run_number}.{run_attempt}"
    safe_relative_path(candidate_tag)
    if output.exists():
        raise ReleaseError(f"Candidate output path already exists: {output}")
    output.mkdir(parents=True)
    payload_root = output / "payload"
    payload_root.mkdir()
    comp_root = component_root(component, repo_root)
    staged: list[dict[str, Any]] = []
    plugin_jar_path: Path | None = None
    for item in component["stableFiles"]:
        stable_path = safe_relative_path(item["path"]).as_posix()
        if item["sourceKind"] == "file":
            source = contained_path(comp_root, item["sourcePath"])
            if not source.is_file():
                raise ReleaseError(f"Release payload source is missing: {source}")
            if source.stat().st_size > MAX_RELEASE_FILE_BYTES:
                raise ReleaseError(f"Release payload file is too large: {source}")
            data = source.read_bytes()
            if component["kind"] == "plugin" and item["sourcePath"] == component["artifactPath"]:
                plugin_jar_path = source
        else:
            if plugin_jar_path is None:
                artifact = contained_path(comp_root, component["artifactPath"])
                if not artifact.is_file():
                    raise ReleaseError(f"Plugin artifact is missing: {artifact}; build it first.")
                plugin_jar_path = artifact
            jar_item = next(
                (
                    entry
                    for entry in component["stableFiles"]
                    if entry["sourceKind"] == "file"
                    and entry["sourcePath"] == component["artifactPath"]
                ),
                None,
            )
            if jar_item is None:
                raise ReleaseError(f"Plugin JAR stable path is missing: {component['id']}")
            data = make_plugin_list(component, registry, plugin_jar_path, jar_item["path"])
        target = contained_path(payload_root, stable_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        staged.append({"path": stable_path, "sha256": sha256_bytes(data), "size": len(data)})

    archive_name = f"saarelai-{component['id'].replace('/', '-')}-v{version}-candidate.zip"
    package_path = output / archive_name
    with zipfile.ZipFile(package_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(payload_root.rglob("*")):
            if file.is_file():
                archive.write(file, file.relative_to(payload_root).as_posix())
    package_hash = sha256_file(package_path)
    provenance = {
        "schemaVersion": 1,
        "repository": registry["repository"],
        "componentId": component["id"],
        "kind": component["kind"],
        "version": version,
        "candidateTag": candidate_tag,
        "sourceCommit": source_commit,
        "sourceRef": source_ref,
        "runNumber": int(run_number),
        "runAttempt": int(run_attempt),
        "plannedStableDate": planned_stable_date,
        "createdAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "buildJavaVersion": registry["buildJavaVersion"],
        "targetJosmJavaVersion": registry["targetJosmJavaVersion"],
        "stableBranch": registry["stableBranch"],
        "files": staged,
        "packageFile": archive_name,
        "packageSha256": package_hash,
    }
    provenance_path = output / "provenance.json"
    provenance_path.write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    checksum_path = output / f"{archive_name}.sha256"
    checksum_path.write_text(f"{package_hash}  {archive_name}\n", encoding="ascii")
    notes_path = output / "release-notes.md"
    notes_path.write_text(
        f"Candidate for `{component['id']}` version `{version}`.\n\n"
        f"- Source commit: `{source_commit}`\n"
        f"- Candidate tag: `{candidate_tag}`\n"
        f"- Component changelog: "
        f"[`CHANGELOG.md`](https://github.com/{registry['repository']}/blob/"
        f"{source_commit}/{component['path']}/CHANGELOG.md)\n"
        "- Candidate releases do not update the `stable` branch or any stable URL.\n",
        encoding="utf-8",
    )
    result = {
        "componentId": component["id"],
        "version": version,
        "candidateTag": candidate_tag,
        "sourceCommit": source_commit,
        "packagePath": str(package_path),
        "packageFile": archive_name,
        "packageSha256": package_hash,
        "provenancePath": str(provenance_path),
        "checksumPath": str(checksum_path),
        "releaseNotesPath": str(notes_path),
    }
    verify_candidate(
        component,
        registry,
        candidate_tag,
        package_path,
        provenance_path,
        checksum_path,
        stable_date=planned_stable_date,
        run_josm_parser=False,
    )
    return result


def parse_version_key(version: str) -> tuple[tuple[int, ...], dt.date | None]:
    if not _version_is_valid(version):
        raise ReleaseError(f"Cannot compare unsupported component version: {version}")
    if "_" in version:
        base, date_text = version.split("_", 1)
        date = dt.date.fromisoformat(date_text)
    else:
        base, date = version, None
    return tuple(int(part) for part in base.split(".")), date


def compare_versions(left: str, right: str) -> int:
    left_parts, left_date = parse_version_key(left)
    right_parts, right_date = parse_version_key(right)
    width = max(len(left_parts), len(right_parts))
    left_key = left_parts + (0,) * (width - len(left_parts))
    right_key = right_parts + (0,) * (width - len(right_parts))
    if left_key != right_key:
        return 1 if left_key > right_key else -1
    left_date_key = left_date or dt.date.min
    right_date_key = right_date or dt.date.min
    if left_date_key == right_date_key:
        return 0
    return 1 if left_date_key > right_date_key else -1


def _expected_stable_paths(component: dict[str, Any]) -> list[str]:
    return sorted(safe_relative_path(item["path"]).as_posix() for item in component["stableFiles"])


def _validate_manifest_shape(
    component: dict[str, Any], registry: dict[str, Any], provenance: dict[str, Any], candidate_tag: str
) -> None:
    if provenance.get("schemaVersion") != 1:
        raise ReleaseError("Unsupported candidate provenance schema.")
    if provenance.get("repository") != registry["repository"]:
        raise ReleaseError("Candidate provenance repository does not match this repository.")
    if provenance.get("componentId") != component["id"]:
        raise ReleaseError("Candidate provenance component does not match the selected component.")
    if provenance.get("kind") != component["kind"]:
        raise ReleaseError("Candidate provenance component kind does not match the registry.")
    version = provenance.get("version", "")
    if not _version_is_valid(version):
        raise ReleaseError(f"Candidate version is invalid: {version!r}")
    expected_tag = (
        f"{component['id']}/v{version}-rc."
        f"{provenance.get('runNumber')}.{provenance.get('runAttempt')}"
    )
    if candidate_tag != expected_tag or provenance.get("candidateTag") != expected_tag:
        raise ReleaseError("Candidate tag does not match its component, version, and run metadata.")
    if not GIT_SHA_RE.fullmatch(provenance.get("sourceCommit", "")):
        raise ReleaseError("Candidate sourceCommit is not a full Git commit id.")
    if provenance.get("stableBranch") != registry["stableBranch"]:
        raise ReleaseError("Candidate stable branch does not match the current registry.")
    if provenance.get("targetJosmJavaVersion") != registry["targetJosmJavaVersion"]:
        raise ReleaseError("Candidate Java runtime target does not match the current registry.")
    if provenance.get("files") is None:
        raise ReleaseError("Candidate provenance contains no stable file manifest.")
    manifest_paths = sorted(safe_relative_path(item["path"]).as_posix() for item in provenance["files"])
    if manifest_paths != _expected_stable_paths(component):
        raise ReleaseError("Candidate stable file list does not exactly match the component registry.")
    for item in provenance["files"]:
        if (
            not SHA256_RE.fullmatch(item.get("sha256", ""))
            or not isinstance(item.get("size"), int)
            or item["size"] < 0
            or item["size"] > MAX_RELEASE_FILE_BYTES
        ):
            raise ReleaseError(f"Invalid file checksum/size in candidate provenance: {item.get('path')}")
    if (
        not isinstance(provenance.get("runNumber"), int)
        or provenance["runNumber"] < 1
        or not isinstance(provenance.get("runAttempt"), int)
        or provenance["runAttempt"] < 1
        or not isinstance(provenance.get("sourceRef"), str)
        or not provenance["sourceRef"]
    ):
        raise ReleaseError("Candidate run/source-ref provenance fields are invalid.")


def verify_candidate(
    component: dict[str, Any],
    registry: dict[str, Any],
    candidate_tag: str,
    package_path: Path,
    provenance_path: Path,
    checksum_path: Path,
    *,
    stable_date: str | None = None,
    run_josm_parser: bool = True,
) -> tuple[dict[str, Any], dict[str, bytes]]:
    try:
        provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReleaseError(f"Cannot read candidate provenance: {error}") from error
    _validate_manifest_shape(component, registry, provenance, candidate_tag)
    checked_out_commit = run_git(ROOT, "rev-parse", "HEAD")
    if checked_out_commit != provenance["sourceCommit"]:
        raise ReleaseError(
            "Candidate source commit is not checked out. Check out the candidate tag before promotion."
        )
    if not package_path.is_file() or package_path.name != provenance.get("packageFile"):
        raise ReleaseError("Candidate payload ZIP is missing or has an unexpected filename.")
    if package_path.stat().st_size > MAX_RELEASE_PACKAGE_BYTES:
        raise ReleaseError("Candidate payload ZIP exceeds the maximum allowed size.")
    if sha256_file(package_path) != provenance.get("packageSha256"):
        raise ReleaseError("Candidate payload ZIP checksum does not match its provenance.")
    try:
        checksum_line = checksum_path.read_text(encoding="ascii").strip().split()
    except OSError as error:
        raise ReleaseError(f"Candidate checksum file is missing: {error}") from error
    if len(checksum_line) != 2 or checksum_line[0] != provenance["packageSha256"]:
        raise ReleaseError("Candidate checksum sidecar does not match the payload ZIP.")
    if checksum_line[1].lstrip("*") != package_path.name:
        raise ReleaseError("Candidate checksum sidecar names a different payload ZIP.")

    expected = {item["path"]: item for item in provenance["files"]}
    payload: dict[str, bytes] = {}
    try:
        with zipfile.ZipFile(package_path) as archive:
            if sum(item.file_size for item in archive.infolist()) > MAX_RELEASE_PACKAGE_BYTES:
                raise ReleaseError("Uncompressed candidate payload exceeds the maximum allowed size.")
            if any(item.file_size > MAX_RELEASE_FILE_BYTES for item in archive.infolist()):
                raise ReleaseError("A candidate payload file exceeds the maximum allowed size.")
            names = [safe_relative_path(name).as_posix() for name in archive.namelist()]
            if sorted(names) != sorted(expected):
                raise ReleaseError("Candidate ZIP entries do not exactly match the provenance file list.")
            for name in names:
                data = archive.read(name)
                item = expected[name]
                if len(data) != item["size"] or sha256_bytes(data) != item["sha256"]:
                    raise ReleaseError(f"Candidate payload checksum mismatch: {name}")
                payload[name] = data
    except (OSError, zipfile.BadZipFile) as error:
        raise ReleaseError(f"Candidate payload ZIP is invalid: {error}") from error

    version = provenance["version"]
    if component["kind"] == "plugin":
        artifact_entry = next(
            item for item in component["stableFiles"] if item.get("sourcePath") == component["artifactPath"]
        )
        manifest = manifest_for_jar_bytes(payload[artifact_entry["path"]])
        if manifest.get("Plugin-Version") != version:
            raise ReleaseError("Candidate JAR manifest version does not match provenance.")
        if manifest.get("Plugin-Class") != component["pluginMainClass"]:
            raise ReleaseError("Candidate JAR plugin class does not match the registry.")
        if manifest.get("Plugin-Minimum-Java-Version") != str(registry["targetJosmJavaVersion"]):
            raise ReleaseError("Candidate JAR does not declare the target Java runtime minimum.")
        class_majors = class_file_major_versions_from_bytes(payload[artifact_entry["path"]])
        if max(class_majors) > registry["buildJavaVersion"] + 44:
            raise ReleaseError("Candidate JAR contains bytecode newer than the configured build target.")
        list_entry = next(item for item in component["stableFiles"] if item["sourceKind"] == "plugin-list")
        plugin_list_bytes = payload[list_entry["path"]]
        first_line = plugin_list_bytes.decode("utf-8").splitlines()[0]
        jar_url = registry["stableRawBaseUrl"].rstrip("/") + "/" + artifact_entry["path"]
        if first_line != f"{Path(artifact_entry['path']).name};{jar_url}":
            raise ReleaseError("Candidate JOSM plugin update-site list has an unexpected stable URL.")
        if run_josm_parser:
            with tempfile.TemporaryDirectory(prefix="saarelai-plugin-list-") as temporary:
                list_path = Path(temporary) / "list"
                list_path.write_bytes(plugin_list_bytes)
                validate_with_josm(
                    component, list_path, registry, parser_kind="plugin-list"
                )
    else:
        file_entry = component["stableFiles"][0]
        actual_version = version_from_bytes(component, payload[file_entry["path"]])
        if actual_version != version:
            raise ReleaseError("Candidate resource metadata version does not match provenance.")
        source_file = contained_path(component_root(component), component["sourceFile"])
        if sha256_file(source_file) != sha256_bytes(payload[file_entry["path"]]):
            raise ReleaseError(
                "Candidate resource bytes do not match the checked-out source commit."
            )
        if run_josm_parser:
            with tempfile.TemporaryDirectory(prefix="saarelai-release-verify-") as temporary:
                path = Path(temporary) / Path(file_entry["path"]).name
                path.write_bytes(payload[file_entry["path"]])
                validate_with_josm(component, path, registry)

    if component["kind"] == "preset":
        planned_date = provenance.get("plannedStableDate")
        if not stable_date or not planned_date:
            raise ReleaseError(
                "Preset candidate/promotion requires --stable-date with the planned or actual publication date."
            )
        try:
            supplied_date = dt.date.fromisoformat(stable_date)
            recorded_date = dt.date.fromisoformat(planned_date)
        except ValueError as error:
            raise ReleaseError("--stable-date must use YYYY-MM-DD.") from error
        version_date = dt.date.fromisoformat(version.split("_", 1)[1])
        if supplied_date != version_date or supplied_date != recorded_date:
            raise ReleaseError(
                f"Preset version/planned date {version_date.isoformat()}/{recorded_date.isoformat()} "
                f"does not equal the declared stable publication date {supplied_date.isoformat()}."
            )
    elif provenance.get("plannedStableDate") is not None:
        raise ReleaseError("Non-preset candidate contains an unexpected plannedStableDate.")
    return provenance, payload


def class_file_major_versions_from_bytes(jar_data: bytes) -> list[int]:
    versions: list[int] = []
    try:
        with zipfile.ZipFile(io.BytesIO(jar_data)) as archive:
            for name in archive.namelist():
                if name.endswith(".class"):
                    header = archive.read(name)[:8]
                    if len(header) != 8 or header[:4] != b"\xca\xfe\xba\xbe":
                        raise ReleaseError(f"Malformed class file in candidate JAR: {name}")
                    versions.append(int.from_bytes(header[6:8], "big"))
    except (OSError, zipfile.BadZipFile) as error:
        raise ReleaseError(f"Candidate JAR is invalid: {error}") from error
    if not versions:
        raise ReleaseError("Candidate JAR contains no Java class files.")
    return versions


def version_from_file(component: dict[str, Any], path: Path) -> str:
    if component["kind"] == "plugin":
        manifest, _ = read_jar_manifest(path)
        version = manifest.get("Plugin-Version", "")
        if not _version_is_valid(version):
            raise ReleaseError(f"Stable plugin JAR has an invalid Plugin-Version: {version!r}")
        return version
    return version_from_bytes(component, path.read_bytes())


def prepare_feed_worktree(repo_root: Path, feed_root: Path, stable_branch: str) -> None:
    if feed_root.exists():
        raise ReleaseError(f"Stable feed worktree path already exists: {feed_root}")
    feed_root.parent.mkdir(parents=True, exist_ok=True)
    # Stable URLs serve artifact bytes directly; Windows checkout conversion must not alter them.
    run_git(repo_root, "config", "core.autocrlf", "false")
    remote_ref = run_git(repo_root, "ls-remote", "--heads", "origin", f"refs/heads/{stable_branch}")
    if remote_ref:
        run_git(
            repo_root,
            "fetch",
            "origin",
            f"refs/heads/{stable_branch}:refs/remotes/origin/{stable_branch}",
        )
        run_git(
            repo_root,
            "worktree",
            "add",
            "--detach",
            str(feed_root),
            f"refs/remotes/origin/{stable_branch}",
        )
        return

    local_ref = run_git(
        repo_root,
        "show-ref",
        "--verify",
        f"refs/heads/{stable_branch}",
        check=False,
    )
    if local_ref:
        raise ReleaseError(
            f"Local branch {stable_branch} exists but origin has no matching stable feed branch."
        )
    empty_tree = run_git(repo_root, "mktree", input_text="")
    initial_commit = run_git(
        repo_root,
        "-c",
        "user.name=github-actions[bot]",
        "-c",
        "user.email=41898282+github-actions[bot]@users.noreply.github.com",
        "-c",
        "commit.gpgsign=false",
        "commit-tree",
        empty_tree,
        "-m",
        "Initialize JOSM stable feed",
    )
    run_git(repo_root, "update-ref", f"refs/heads/{stable_branch}", initial_commit)
    run_git(repo_root, "worktree", "add", "--detach", str(feed_root), initial_commit)


def _ensure_feed_target(feed_root: Path, relative: str) -> Path:
    target = contained_path(feed_root, relative)
    parent = target.parent
    parent.mkdir(parents=True, exist_ok=True)
    if target.is_symlink():
        raise ReleaseError(f"Stable feed target must not be a symbolic link: {relative}")
    return target


def stage_candidate(
    component: dict[str, Any],
    registry: dict[str, Any],
    candidate_tag: str,
    package_path: Path,
    provenance_path: Path,
    checksum_path: Path,
    feed_root: Path,
    *,
    stable_date: str | None = None,
    commit_and_push: bool = False,
    run_josm_parser: bool = True,
    repo_root: Path | None = None,
) -> dict[str, Any]:
    repo_root = repo_root or ROOT
    provenance, payload = verify_candidate(
        component,
        registry,
        candidate_tag,
        package_path,
        provenance_path,
        checksum_path,
        stable_date=stable_date,
        run_josm_parser=run_josm_parser,
    )
    feed_root = feed_root.resolve()
    if feed_root == repo_root.resolve() or feed_root.is_relative_to(repo_root.resolve()):
        raise ReleaseError("Stable feed worktree must be outside the source checkout.")
    initial_status = run_git(feed_root, "status", "--porcelain", "--untracked-files=all")
    if initial_status:
        raise ReleaseError("Stable feed worktree must be clean before staging a component.")
    allowed_paths = set(_expected_stable_paths(component))
    changed_paths: list[str] = []

    first_component_file = next(
        (
            item["path"]
            for item in component["stableFiles"]
            if item["sourceKind"] == "file"
        ),
        None,
    )
    if first_component_file is None:
        raise ReleaseError(f"No source artifact is declared for {component['id']}.")
    current_component_files = [
        contained_path(feed_root, item["path"])
        for item in component["stableFiles"]
    ]
    existing_component_files = [path.is_file() for path in current_component_files]
    if any(existing_component_files) and not all(existing_component_files):
        raise ReleaseError(
            f"Stable feed for {component['id']} is incomplete; refusing to overwrite a partial install."
        )
    current_path = contained_path(feed_root, first_component_file)
    current_version: str | None = None
    if current_path.is_file():
        current_version = version_from_file(component, current_path)
        order = compare_versions(provenance["version"], current_version)
        if order < 0:
            raise ReleaseError(
                f"Candidate {provenance['version']} is older than stable {current_version}."
            )
        if order == 0:
            for item in provenance["files"]:
                stable_path = contained_path(feed_root, item["path"])
                if not stable_path.is_file() or sha256_file(stable_path) != item["sha256"]:
                    raise ReleaseError(
                        f"Stable version {current_version} already exists with different bytes; "
                        "bump the component version before publishing changed content."
                    )
            return {
                "componentId": component["id"],
                "version": provenance["version"],
                "stableBranch": registry["stableBranch"],
                "changedPaths": [],
                "alreadyPublished": True,
                "commit": run_git(feed_root, "rev-parse", "HEAD"),
            }

    for relative in sorted(allowed_paths):
        target = contained_path(feed_root, relative)
        data = payload[relative]
        if not target.is_file() or sha256_file(target) != sha256_bytes(data):
            changed_paths.append(relative)

    if not changed_paths:
        return {
            "componentId": component["id"],
            "version": provenance["version"],
            "stableBranch": registry["stableBranch"],
            "changedPaths": [],
            "alreadyPublished": True,
            "commit": None,
        }

    if not commit_and_push:
        return {
            "componentId": component["id"],
            "version": provenance["version"],
            "stableBranch": registry["stableBranch"],
            "changedPaths": sorted(changed_paths),
            "alreadyPublished": False,
            "commit": None,
        }

    for relative in changed_paths:
        target = _ensure_feed_target(feed_root, relative)
        target.write_bytes(payload[relative])

    run_git(feed_root, "add", "--", *sorted(changed_paths))
    staged_paths = {
        line.replace("\\", "/")
        for line in run_git(feed_root, "diff", "--cached", "--name-only").splitlines()
        if line
    }
    if staged_paths != set(changed_paths):
        raise ReleaseError(
            f"Refusing stable-feed commit; expected {sorted(changed_paths)}, staged {sorted(staged_paths)}."
        )
    if not staged_paths:
        return {
            "componentId": component["id"],
            "version": provenance["version"],
            "stableBranch": registry["stableBranch"],
            "changedPaths": [],
            "alreadyPublished": True,
            "commit": run_git(feed_root, "rev-parse", "HEAD"),
        }
    run_git(
        feed_root,
        "-c",
        "user.name=github-actions[bot]",
        "-c",
        "user.email=41898282+github-actions[bot]@users.noreply.github.com",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "-m",
        f"deploy({component['id']}): v{provenance['version']}",
    )
    commit = run_git(feed_root, "rev-parse", "HEAD")
    run_git(feed_root, "push", "origin", f"HEAD:refs/heads/{registry['stableBranch']}")
    return {
        "componentId": component["id"],
        "version": provenance["version"],
        "stableBranch": registry["stableBranch"],
        "changedPaths": sorted(staged_paths),
        "alreadyPublished": False,
        "commit": commit,
    }


def changed_components(
    repo_root: Path,
    registry: dict[str, Any],
    base_sha: str,
    head_sha: str,
    event_name: str,
) -> list[str]:
    if not base_sha or set(base_sha) == {"0"}:
        return sorted(component["id"] for component in registry["components"])
    if not GIT_SHA_RE.fullmatch(base_sha) or not GIT_SHA_RE.fullmatch(head_sha):
        raise ReleaseError("Changed-component detection requires full Git commit ids.")
    comparison = f"{base_sha}...{head_sha}" if event_name == "pull_request" else f"{base_sha}..{head_sha}"
    changed = run_git(repo_root, "diff", "--name-only", comparison).splitlines()
    if any(
        path.startswith(("catalog/", "scripts/", ".github/workflows/"))
        or path in {"AGENTS.md", "CONTRIBUTING.md", "LICENSES.md", ".gitignore"}
        for path in changed
    ):
        return sorted(component["id"] for component in registry["components"])

    known = {component["id"] for component in registry["components"]}
    selected: set[str] = set()
    for file_path in changed:
        parts = PurePosixPath(file_path).parts
        if len(parts) >= 2 and "/".join(parts[:2]) in known:
            selected.add("/".join(parts[:2]))
        elif parts and parts[0] in {"plugins", "presets", "styles", "validators"}:
            return sorted(component["id"] for component in registry["components"])
    return sorted(selected) if selected else ["__none__"]


def cmd_list(args: argparse.Namespace) -> None:
    registry = load_registry(args.registry)
    print(json.dumps([component["id"] for component in registry["components"]], indent=2))


def cmd_validate(args: argparse.Namespace) -> None:
    registry = load_registry(args.registry)
    component = get_component(registry, args.component)
    version = validate_component(
        component,
        registry,
        require_release_version=args.for_release,
        run_josm_parser=not args.skip_josm_parser,
    )
    print(json.dumps({"componentId": component["id"], "kind": component["kind"], "version": version}))


def cmd_candidate(args: argparse.Namespace) -> None:
    registry = load_registry(args.registry)
    component = get_component(registry, args.component)
    output = prepare_candidate(
        component,
        registry,
        Path(args.output).resolve(),
        args.run_number,
        args.run_attempt,
        args.source_ref,
        args.source_commit,
        args.stable_date,
    )
    print(json.dumps(output, indent=2))


def cmd_verify(args: argparse.Namespace) -> None:
    registry = load_registry(args.registry)
    component = get_component(registry, args.component)
    provenance, _ = verify_candidate(
        component,
        registry,
        args.candidate_tag,
        Path(args.package).resolve(),
        Path(args.provenance).resolve(),
        Path(args.checksum).resolve(),
        stable_date=args.stable_date,
    )
    print(json.dumps(provenance, indent=2))


def cmd_prepare_feed(args: argparse.Namespace) -> None:
    registry = load_registry(args.registry)
    prepare_feed_worktree(ROOT, Path(args.feed_root).resolve(), registry["stableBranch"])
    print(json.dumps({"feedRoot": str(Path(args.feed_root).resolve()), "branch": registry["stableBranch"]}))


def cmd_stage(args: argparse.Namespace) -> None:
    registry = load_registry(args.registry)
    component = get_component(registry, args.component)
    output = stage_candidate(
        component,
        registry,
        args.candidate_tag,
        Path(args.package).resolve(),
        Path(args.provenance).resolve(),
        Path(args.checksum).resolve(),
        Path(args.feed_root).resolve(),
        stable_date=args.stable_date,
        commit_and_push=args.commit_and_push,
        run_josm_parser=not args.skip_josm_parser,
    )
    print(json.dumps(output, indent=2))


def cmd_changed(args: argparse.Namespace) -> None:
    registry = load_registry(args.registry)
    result = changed_components(ROOT, registry, args.base_sha, args.head_sha, args.event)
    print(json.dumps(result))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path, default=REGISTRY_PATH)
    commands = parser.add_subparsers(required=True)

    list_parser = commands.add_parser("list-components")
    list_parser.set_defaults(func=cmd_list)

    validate_parser = commands.add_parser("validate")
    validate_parser.add_argument("--component", required=True)
    validate_parser.add_argument("--for-release", action="store_true")
    validate_parser.add_argument("--skip-josm-parser", action="store_true")
    validate_parser.set_defaults(func=cmd_validate)

    candidate_parser = commands.add_parser("prepare-candidate")
    candidate_parser.add_argument("--component", required=True)
    candidate_parser.add_argument("--run-number", required=True)
    candidate_parser.add_argument("--run-attempt", required=True)
    candidate_parser.add_argument("--source-ref", required=True)
    candidate_parser.add_argument("--source-commit", required=True)
    candidate_parser.add_argument("--output", required=True)
    candidate_parser.add_argument("--stable-date")
    candidate_parser.set_defaults(func=cmd_candidate)

    verify_parser = commands.add_parser("verify-candidate")
    verify_parser.add_argument("--component", required=True)
    verify_parser.add_argument("--candidate-tag", required=True)
    verify_parser.add_argument("--package", required=True)
    verify_parser.add_argument("--provenance", required=True)
    verify_parser.add_argument("--checksum", required=True)
    verify_parser.add_argument("--stable-date")
    verify_parser.set_defaults(func=cmd_verify)

    feed_parser = commands.add_parser("prepare-feed-worktree")
    feed_parser.add_argument("--feed-root", required=True)
    feed_parser.set_defaults(func=cmd_prepare_feed)

    stage_parser = commands.add_parser("stage-stable")
    stage_parser.add_argument("--component", required=True)
    stage_parser.add_argument("--candidate-tag", required=True)
    stage_parser.add_argument("--package", required=True)
    stage_parser.add_argument("--provenance", required=True)
    stage_parser.add_argument("--checksum", required=True)
    stage_parser.add_argument("--feed-root", required=True)
    stage_parser.add_argument("--stable-date")
    stage_parser.add_argument("--commit-and-push", action="store_true")
    stage_parser.add_argument("--skip-josm-parser", action="store_true")
    stage_parser.set_defaults(func=cmd_stage)

    changed_parser = commands.add_parser("changed-components")
    changed_parser.add_argument("--base-sha", required=True)
    changed_parser.add_argument("--head-sha", required=True)
    changed_parser.add_argument("--event", choices=("push", "pull_request"), required=True)
    changed_parser.set_defaults(func=cmd_changed)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        args.func(args)
    except ReleaseError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    except subprocess.CalledProcessError as error:
        detail = error.stderr if isinstance(error.stderr, str) else str(error.stderr)
        print(f"ERROR: command failed ({error.returncode}): {detail}", file=sys.stderr)
        return error.returncode or 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
