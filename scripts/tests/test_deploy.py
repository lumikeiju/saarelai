import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import deploy


class RegistryTests(unittest.TestCase):
    def test_registry_contains_only_the_approved_components(self):
        registry = deploy.load_registry()
        self.assertEqual(registry["stableBranch"], "stable")
        self.assertEqual(
            registry["stableRawBaseUrl"],
            "https://raw.githubusercontent.com/lumikeiju/saarelai/stable",
        )
        self.assertEqual(len(registry["components"]), 20)
        self.assertEqual(
            sum(component["kind"] == "plugin" for component in registry["components"]), 3
        )
        self.assertEqual(
            sum(component["kind"] == "preset" for component in registry["components"]), 10
        )
        self.assertEqual(
            sum(component["kind"] == "style" for component in registry["components"]), 1
        )
        self.assertEqual(
            sum(component["kind"] == "validator" for component in registry["components"]), 6
        )
        tiger_stripes = deploy.get_component(registry, "styles/tiger-stripes")
        self.assertEqual(
            tiger_stripes["stableFiles"][1]["introducedInVersion"], "1.0.1"
        )
        improve_node = deploy.get_component(registry, "plugins/improve-node")
        self.assertEqual(
            improve_node["retiredStablePaths"],
            ["plugins/improve-node/ImproveNode.jar"],
        )
        self.assertNotIn(
            "plugins/improve-node/ImproveNode.jar",
            [item["path"] for item in improve_node["stableFiles"]],
        )

    def test_registry_rejects_invalid_stable_file_introduction_version(self):
        registry = deploy.load_registry()
        deploy.get_component(registry, "styles/tiger-stripes")["stableFiles"][1][
            "introducedInVersion"
        ] = "not-a-version"
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "components.json"
            path.write_text(json.dumps(registry), encoding="utf-8")
            with self.assertRaisesRegex(deploy.ReleaseError, "introducedInVersion"):
                deploy.load_registry(path)

    def test_plugin_artifacts_use_kebab_case_and_display_names(self):
        registry = deploy.load_registry()
        expected = {
            "plugins/easy-pan": (
                "Easy Pan",
                "build/dist/easy-pan.jar",
                ["plugins/easy-pan/easy-pan.jar"],
            ),
            "plugins/arkki-snappi": (
                "Arkki Snappi",
                "build/dist/arkki-snappi.jar",
                ["plugins/arkki-snappi/arkki-snappi.jar"],
            ),
            "plugins/improve-node": (
                "Improve Node",
                "build/dist/improve-node.jar",
                ["plugins/improve-node/improve-node.jar"],
            ),
        }
        for component_id, (plugin_name, artifact_path, expected_paths) in expected.items():
            with self.subTest(component=component_id):
                component = deploy.get_component(registry, component_id)
                self.assertEqual(component["pluginName"], plugin_name)
                self.assertEqual(component["artifactPath"], artifact_path)
                artifact_entries = [
                    item
                    for item in component["stableFiles"]
                    if item.get("sourcePath") == artifact_path
                ]
                self.assertEqual([item["path"] for item in artifact_entries], expected_paths)
                self.assertEqual(component["stableFiles"][-1]["path"], f"{component_id}/list")
        improve_node = deploy.get_component(registry, "plugins/improve-node")
        self.assertEqual(improve_node["stableFiles"][0]["introducedInVersion"], "1.0.1")

    def test_plugin_list_uses_display_name_with_kebab_artifact_path(self):
        registry = deploy.load_registry()
        component = deploy.get_component(registry, "plugins/easy-pan")
        with tempfile.TemporaryDirectory() as temporary:
            jar_path = Path(temporary) / "easy-pan.jar"
            with zipfile.ZipFile(jar_path, "w") as jar:
                jar.writestr(
                    "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\r\n"
                    "Plugin-Class: org.openstreetmap.josm.plugins.easypan.EasyPanPlugin\r\n"
                    "Plugin-Version: 1.0.0\r\n\r\n",
                )
            generated = deploy.make_plugin_list(
                component, registry, jar_path, "plugins/easy-pan/easy-pan.jar"
            )

        self.assertEqual(
            generated.decode("utf-8").splitlines()[0],
            "Easy Pan.jar;https://raw.githubusercontent.com/lumikeiju/saarelai/"
            "stable/plugins/easy-pan/easy-pan.jar",
        )

    def test_component_release_version_must_reach_new_stable_file_version(self):
        registry = deploy.load_registry()
        component = deploy.get_component(registry, "styles/tiger-stripes")
        component["stableFiles"][1]["introducedInVersion"] = "1.0.2"
        with patch.object(deploy, "get_component_version", return_value="1.0.1"):
            with self.assertRaisesRegex(deploy.ReleaseError, "predates stable file"):
                deploy.validate_component(
                    component,
                    registry,
                    require_release_version=True,
                    run_josm_parser=False,
                )

    def test_rejects_paths_that_could_escape_component_or_feed(self):
        for value in ("../secret", "/absolute", "C:/absolute", "plugins/../other", "a\\b"):
            with self.subTest(value=value):
                with self.assertRaises(deploy.ReleaseError):
                    deploy.safe_relative_path(value)

    def test_version_ordering_handles_semver_and_josm_date_versions(self):
        self.assertEqual(deploy.compare_versions("0.2.0", "0.1.0"), 1)
        self.assertEqual(deploy.compare_versions("1.0_2026-10-04", "1.0_2025-06-29"), 1)
        self.assertEqual(deploy.compare_versions("1.0.0", "1.0"), 0)

    def test_changed_component_detection_is_scoped_and_shared_changes_fan_out(self):
        registry = deploy.load_registry()
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            subprocess.run(["git", "-C", str(repo), "init", "-q", "-b", "main"], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "Saarelai test"], check=True)
            subprocess.run(
                ["git", "-C", str(repo), "config", "user.email", "saarelai-test@example.invalid"],
                check=True,
            )
            subprocess.run(["git", "-C", str(repo), "config", "commit.gpgsign", "false"], check=True)
            (repo / "README.md").write_text("base\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(repo), "add", "README.md"], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-qm", "base"], check=True)
            base = subprocess.run(
                ["git", "-C", str(repo), "rev-parse", "HEAD"],
                check=True,
                text=True,
                capture_output=True,
            ).stdout.strip()

            component_file = repo / "plugins" / "easy-pan" / "src" / "Example.java"
            component_file.parent.mkdir(parents=True)
            component_file.write_text("class Example {}\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-qm", "plugin change"], check=True)
            head = subprocess.run(
                ["git", "-C", str(repo), "rev-parse", "HEAD"],
                check=True,
                text=True,
                capture_output=True,
            ).stdout.strip()
            self.assertEqual(
                deploy.changed_components(repo, registry, base, head, "push"),
                ["plugins/easy-pan"],
            )

            shared_file = repo / "scripts" / "deploy.py"
            shared_file.parent.mkdir()
            shared_file.write_text("print('shared')\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-qm", "shared release change"], check=True)
            shared_head = subprocess.run(
                ["git", "-C", str(repo), "rev-parse", "HEAD"],
                check=True,
                text=True,
                capture_output=True,
            ).stdout.strip()
            self.assertCountEqual(
                deploy.changed_components(repo, registry, head, shared_head, "push"),
                [component["id"] for component in registry["components"]],
            )

    def test_preset_without_version_is_valid_for_checks_but_not_release(self):
        registry = deploy.load_registry()
        component = deploy.get_component(registry, "presets/3d-building-details")
        with tempfile.TemporaryDirectory() as temporary:
            repo_root = Path(temporary)
            source_file = repo_root / component["path"] / component["sourceFile"]
            source_file.parent.mkdir(parents=True)
            source_file.write_text(
                '<presets xmlns="http://josm.openstreetmap.de/tagging-preset-1.0">\n'
                '  <group name="Fixture" />\n'
                '</presets>\n',
                encoding="utf-8",
            )
            self.assertIsNone(
                deploy.get_component_version(
                    component, repo_root=repo_root, require_release_version=False
                )
            )
            with self.assertRaisesRegex(deploy.ReleaseError, "add the preset root version"):
                deploy.get_component_version(
                    component, repo_root=repo_root, require_release_version=True
                )

    def test_josm_parser_stdout_does_not_pollute_cli_json(self):
        registry = deploy.load_registry()
        component = deploy.get_component(registry, "styles/tiger-stripes")
        completed = subprocess.CompletedProcess(args=[], returncode=0)
        with patch.object(deploy, "java_runtime_version", return_value=25), patch.object(
            deploy, "ensure_josm_core", return_value=Path("josm-core.jar")
        ), patch.object(deploy.subprocess, "run", return_value=completed) as run:
            deploy.validate_with_josm(component, Path("tiger-stripes.mapcss"), registry)

        self.assertIs(run.call_args.kwargs["stdout"], sys.stderr)


class StableFileTransitionTests(unittest.TestCase):
    def test_retired_plugin_path_supplies_version_for_a_new_stable_jar_path(self):
        component = {
            "id": "plugins/improve-node",
            "kind": "plugin",
            "artifactPath": "build/dist/improve-node.jar",
            "retiredStablePaths": ["plugins/improve-node/ImproveNode.jar"],
            "stableFiles": [
                {
                    "sourceKind": "file",
                    "sourcePath": "build/dist/improve-node.jar",
                    "path": "plugins/improve-node/improve-node.jar",
                    "introducedInVersion": "1.0.1",
                },
                {"sourceKind": "plugin-list", "path": "plugins/improve-node/list"},
            ],
        }
        with tempfile.TemporaryDirectory() as temporary:
            feed_root = Path(temporary)
            retired_jar = feed_root / "plugins" / "improve-node" / "ImproveNode.jar"
            retired_jar.parent.mkdir(parents=True)
            retired_jar.write_bytes(b"old stable JAR")
            (retired_jar.parent / "list").write_text("old list\n", encoding="utf-8")
            with patch.object(deploy, "version_from_file", return_value="1.0.0") as read_version:
                current_version = deploy._current_stable_version(component, feed_root)

            self.assertEqual(current_version, "1.0.0")
            read_version.assert_called_once_with(component, retired_jar)
            deploy._check_existing_stable_files(
                component, [False, True], current_version
            )

    def test_stable_promotion_removes_retired_plugin_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo = root / "source"
            repo.mkdir()
            remote = root / "remote.git"
            subprocess.run(["git", "init", "--bare", "-q", str(remote)], check=True)
            feed_root = root / "feed"
            feed_root.mkdir()
            subprocess.run(["git", "-C", str(feed_root), "init", "-q", "-b", "stable"], check=True)
            subprocess.run(["git", "-C", str(feed_root), "config", "user.name", "Feed test"], check=True)
            subprocess.run(
                ["git", "-C", str(feed_root), "config", "user.email", "feed-test@example.invalid"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(feed_root), "config", "commit.gpgsign", "false"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(feed_root), "remote", "add", "origin", str(remote)],
                check=True,
            )
            retired_path = feed_root / "plugins" / "improve-node" / "ImproveNode.jar"
            retired_path.parent.mkdir(parents=True)
            retired_path.write_bytes(b"old JAR")
            (retired_path.parent / "list").write_text("old list\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(feed_root), "add", "."], check=True)
            subprocess.run(["git", "-C", str(feed_root), "commit", "-qm", "old plugin feed"], check=True)

            component = {
                "id": "plugins/improve-node",
                "kind": "plugin",
                "artifactPath": "build/dist/improve-node.jar",
                "pluginMainClass": "org.openstreetmap.josm.plugins.improvenode.ImproveNodePlugin",
                "retiredStablePaths": ["plugins/improve-node/ImproveNode.jar"],
                "stableFiles": [
                    {
                        "sourceKind": "file",
                        "sourcePath": "build/dist/improve-node.jar",
                        "path": "plugins/improve-node/improve-node.jar",
                        "introducedInVersion": "1.0.1",
                    },
                    {"sourceKind": "plugin-list", "path": "plugins/improve-node/list"},
                ],
            }
            registry = {"stableBranch": "stable"}
            payload = {
                "plugins/improve-node/improve-node.jar": b"new JAR",
                "plugins/improve-node/list": b"new list\n",
            }
            with (
                patch.object(
                    deploy,
                    "verify_candidate",
                    return_value=({"version": "1.0.1"}, payload),
                ),
                patch.object(deploy, "version_from_file", return_value="1.0.0"),
            ):
                result = deploy.stage_candidate(
                    component,
                    registry,
                    "plugins/improve-node/v1.0.1-rc.1.1",
                    root / "candidate.zip",
                    root / "provenance.json",
                    root / "candidate.zip.sha256",
                    feed_root,
                    commit_and_push=True,
                    run_josm_parser=False,
                    repo_root=repo,
                )

            self.assertFalse(retired_path.exists())
            self.assertTrue((retired_path.parent / "improve-node.jar").is_file())
            self.assertEqual(
                result["changedPaths"],
                [
                    "plugins/improve-node/ImproveNode.jar",
                    "plugins/improve-node/improve-node.jar",
                    "plugins/improve-node/list",
                ],
            )
            committed_paths = subprocess.run(
                ["git", "--git-dir", str(remote), "ls-tree", "-r", "--name-only", "stable"],
                check=True,
                text=True,
                capture_output=True,
            ).stdout.splitlines()
            self.assertNotIn("plugins/improve-node/ImproveNode.jar", committed_paths)

    def test_new_stable_file_is_allowed_when_current_version_predates_it(self):
        component = {
            "id": "styles/demo",
            "stableFiles": [
                {"path": "styles/demo/demo.mapcss"},
                {
                    "path": "styles/demo/icons/demo.png",
                    "introducedInVersion": "1.0.1",
                },
            ],
        }
        deploy._check_existing_stable_files(component, [True, False], "1.0.0")

    def test_missing_existing_stable_file_is_still_rejected(self):
        component = {
            "id": "styles/demo",
            "stableFiles": [
                {"path": "styles/demo/demo.mapcss"},
                {
                    "path": "styles/demo/icons/demo.png",
                    "introducedInVersion": "1.0.1",
                },
            ],
        }
        with self.assertRaisesRegex(deploy.ReleaseError, "partial install"):
            deploy._check_existing_stable_files(component, [True, False], "1.0.1")
        with self.assertRaisesRegex(deploy.ReleaseError, "partial install"):
            deploy._check_existing_stable_files(component, [False, True], None)

        component["stableFiles"][1].pop("introducedInVersion")
        with self.assertRaisesRegex(deploy.ReleaseError, "partial install"):
            deploy._check_existing_stable_files(component, [True, False], "1.0.0")

    def test_candidate_adds_new_resource_to_existing_stable_feed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo = root / "source"
            repo.mkdir()
            subprocess.run(["git", "-C", str(repo), "init", "-q", "-b", "main"], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "Saarelai test"], check=True)
            subprocess.run(
                ["git", "-C", str(repo), "config", "user.email", "saarelai-test@example.invalid"],
                check=True,
            )
            subprocess.run(["git", "-C", str(repo), "config", "commit.gpgsign", "false"], check=True)

            component_root = repo / "styles" / "demo"
            (component_root / "icons").mkdir(parents=True)
            (component_root / "CHANGELOG.md").write_text(
                "# Changelog\n\n## v1.0.1 (2026-10-11)\n- Add style icon.\n",
                encoding="utf-8",
            )
            (component_root / "demo.mapcss").write_text(
                'meta {\n  title: "Demo";\n  version: "1.0.1";\n  date: "2026-10-11";\n'
                "  min-josm-version: 19418;\n}\n",
                encoding="utf-8",
            )
            (component_root / "icons" / "demo.png").write_bytes(b"test icon")
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-qm", "style candidate"], check=True)

            component = {
                "id": "styles/demo",
                "kind": "style",
                "path": "styles/demo",
                "sourceFile": "demo.mapcss",
                "versionSource": {"kind": "mapcss-meta", "field": "version"},
                "stableFiles": [
                    {
                        "sourceKind": "file",
                        "sourcePath": "demo.mapcss",
                        "path": "styles/demo/demo.mapcss",
                    },
                    {
                        "sourceKind": "file",
                        "sourcePath": "icons/demo.png",
                        "path": "styles/demo/icons/demo.png",
                        "introducedInVersion": "1.0.1",
                    },
                ],
            }
            registry = {
                "repository": "lumikeiju/saarelai",
                "stableBranch": "stable",
                "stableRawBaseUrl": "https://raw.githubusercontent.com/lumikeiju/saarelai/stable",
                "buildJavaVersion": 21,
                "targetJosmJavaVersion": 25,
            }
            candidate_output = root / "candidate"
            with patch.object(deploy, "ROOT", repo), patch.dict(
                deploy.os.environ, {"GITHUB_REF_NAME": ""}
            ):
                candidate = deploy.prepare_candidate(
                    component,
                    registry,
                    candidate_output,
                    run_number="8",
                    run_attempt="1",
                    source_ref="main",
                    repo_root=repo,
                )

            feed = root / "feed"
            feed.mkdir()
            subprocess.run(["git", "-C", str(feed), "init", "-q", "-b", "stable"], check=True)
            subprocess.run(["git", "-C", str(feed), "config", "user.name", "Saarelai test"], check=True)
            subprocess.run(
                ["git", "-C", str(feed), "config", "user.email", "saarelai-test@example.invalid"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(feed), "config", "commit.gpgsign", "false"],
                check=True,
            )
            old_style = feed / "styles" / "demo" / "demo.mapcss"
            old_style.parent.mkdir(parents=True)
            old_style.write_text(
                'meta {\n  title: "Demo";\n  version: "1.0.0";\n  date: "2025-01-01";\n'
                "  min-josm-version: 19418;\n}\n",
                encoding="utf-8",
            )
            subprocess.run(["git", "-C", str(feed), "add", "."], check=True)
            subprocess.run(["git", "-C", str(feed), "commit", "-qm", "previous stable style"], check=True)

            with patch.object(deploy, "ROOT", repo):
                staged = deploy.stage_candidate(
                    component,
                    registry,
                    candidate["candidateTag"],
                    Path(candidate["packagePath"]),
                    Path(candidate["provenancePath"]),
                    Path(candidate["checksumPath"]),
                    feed,
                    run_josm_parser=False,
                    repo_root=repo,
                )

            self.assertCountEqual(
                staged["changedPaths"],
                ["styles/demo/demo.mapcss", "styles/demo/icons/demo.png"],
            )
            self.assertFalse((feed / "styles" / "demo" / "icons" / "demo.png").exists())


class CandidatePackageTests(unittest.TestCase):
    def _fixture(self, root: Path):
        repo = root / "source"
        repo.mkdir()
        subprocess.run(["git", "-C", str(repo), "init", "-q", "-b", "main"], check=True)
        subprocess.run(
            ["git", "-C", str(repo), "config", "user.name", "Saarelai test"],
            check=True,
        )
        subprocess.run(
            ["git", "-C", str(repo), "config", "user.email", "saarelai-test@example.invalid"],
            check=True,
        )
        subprocess.run(["git", "-C", str(repo), "config", "commit.gpgsign", "false"], check=True)
        (repo / "README.md").write_text("fixture\n", encoding="utf-8")
        component_root = repo / "plugins" / "demo"
        component_root.mkdir(parents=True)
        (repo / ".gitignore").write_text("plugins/demo/build/\n", encoding="utf-8")
        (component_root / "gradle.properties").write_text("version=1.0.0\n", encoding="utf-8")
        (component_root / "CHANGELOG.md").write_text(
            "# Changelog\n\n## Unreleased\n", encoding="utf-8"
        )
        subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
        subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)
        source_commit = subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "HEAD"],
            check=True,
            text=True,
            capture_output=True,
        ).stdout.strip()

        jar_path = component_root / "build" / "dist" / "Demo.jar"
        jar_path.parent.mkdir(parents=True)
        manifest = (
            "Manifest-Version: 1.0\r\n"
            "Plugin-Version: 1.0.0\r\n"
            "Plugin-Class: org.example.DemoPlugin\r\n"
            "Plugin-Mainversion: 19439\r\n"
            "Plugin-Minimum-Java-Version: 25\r\n\r\n"
        ).encode("utf-8")
        class_file = b"\xca\xfe\xba\xbe\x00\x00\x00\x41"
        with zipfile.ZipFile(jar_path, "w", zipfile.ZIP_DEFLATED) as jar:
            jar.writestr("META-INF/MANIFEST.MF", manifest)
            jar.writestr("org/example/DemoPlugin.class", class_file)

        stable_jar = "plugins/demo/Demo.jar"
        stable_list = "plugins/demo/list"
        plugin_list = (
            f"Demo.jar;https://raw.githubusercontent.com/lumikeiju/saarelai/"
            f"stable/{stable_jar}\n"
        ).encode("utf-8")
        files = [
            {"path": stable_jar, "sha256": deploy.sha256_file(jar_path), "size": jar_path.stat().st_size},
            {"path": stable_list, "sha256": deploy.sha256_bytes(plugin_list), "size": len(plugin_list)},
        ]
        output = root / "candidate"
        output.mkdir()
        package_path = output / "payload.zip"
        with zipfile.ZipFile(package_path, "w", zipfile.ZIP_DEFLATED) as package:
            package.write(jar_path, stable_jar)
            package.writestr(stable_list, plugin_list)
        candidate_tag = "plugins/demo/v1.0.0-rc.2.1"
        provenance = {
            "schemaVersion": 1,
            "repository": "lumikeiju/saarelai",
            "componentId": "plugins/demo",
            "kind": "plugin",
            "version": "1.0.0",
            "candidateTag": candidate_tag,
            "sourceCommit": source_commit,
            "sourceRef": "main",
            "runNumber": 2,
            "runAttempt": 1,
            "createdAtUtc": "2026-10-04T00:00:00+00:00",
            "buildJavaVersion": 21,
            "targetJosmJavaVersion": 25,
            "stableBranch": "stable",
            "files": files,
            "packageFile": package_path.name,
            "packageSha256": deploy.sha256_file(package_path),
        }
        provenance_path = output / "provenance.json"
        provenance_path.write_text(json.dumps(provenance), encoding="utf-8")
        checksum_path = output / "payload.zip.sha256"
        checksum_path.write_text(
            f"{provenance['packageSha256']}  {package_path.name}\n", encoding="ascii"
        )
        component = {
            "id": "plugins/demo",
            "kind": "plugin",
            "path": "plugins/demo",
            "artifactPath": "build/dist/Demo.jar",
            "pluginName": "Demo",
            "pluginMainClass": "org.example.DemoPlugin",
            "versionSource": {"kind": "gradle-property", "file": "gradle.properties", "key": "version"},
            "stableFiles": [
                {
                    "sourceKind": "file",
                    "sourcePath": "build/dist/Demo.jar",
                    "path": stable_jar,
                },
                {"sourceKind": "plugin-list", "path": stable_list},
            ],
        }
        registry = {
            "schemaVersion": 1,
            "repository": "lumikeiju/saarelai",
            "stableBranch": "stable",
            "stableRawBaseUrl": "https://raw.githubusercontent.com/lumikeiju/saarelai/stable",
            "buildJavaVersion": 21,
            "targetJosmJavaVersion": 25,
        }
        return repo, component, registry, candidate_tag, package_path, provenance_path, checksum_path

    def test_candidate_verification_and_feed_staging_are_component_scoped(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo, component, registry, tag, package, provenance, checksum = self._fixture(root)
            feed = root / "feed"
            feed.mkdir()
            subprocess.run(["git", "-C", str(feed), "init", "-q"], check=True)
            subprocess.run(["git", "-C", str(feed), "config", "user.name", "Feed test"], check=True)
            subprocess.run(
                ["git", "-C", str(feed), "config", "user.email", "feed-test@example.invalid"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(feed), "config", "commit.gpgsign", "false"],
                check=True,
            )
            (feed / "README.md").write_text("stable feed\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(feed), "add", "README.md"], check=True)
            subprocess.run(["git", "-C", str(feed), "commit", "-qm", "fixture"], check=True)

            with patch.object(deploy, "ROOT", repo):
                manifest, _ = deploy.verify_candidate(
                    component,
                    registry,
                    tag,
                    package,
                    provenance,
                    checksum,
                    run_josm_parser=False,
                )
                self.assertEqual(manifest["componentId"], "plugins/demo")
                result = deploy.stage_candidate(
                    component,
                    registry,
                    tag,
                    package,
                    provenance,
                    checksum,
                    feed,
                    run_josm_parser=False,
                    repo_root=repo,
                )
                self.assertCountEqual(
                    result["changedPaths"], ["plugins/demo/Demo.jar", "plugins/demo/list"]
                )
                self.assertFalse((feed / "plugins/demo/Demo.jar").exists())
                self.assertFalse((feed / "plugins/demo/list").exists())
                self.assertFalse((feed / "plugins/easy-pan").exists())

    def test_candidate_version_must_match_namespaced_tag(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo, component, registry, tag, package, provenance, checksum = self._fixture(root)
            provenance_data = json.loads(provenance.read_text(encoding="utf-8"))
            provenance_data["version"] = "2.0.0"
            provenance.write_text(json.dumps(provenance_data), encoding="utf-8")
            with patch.object(deploy, "ROOT", repo):
                with self.assertRaisesRegex(deploy.ReleaseError, "Candidate tag does not match"):
                    deploy.verify_candidate(
                        component,
                        registry,
                        tag,
                        package,
                        provenance,
                        checksum,
                        run_josm_parser=False,
                    )

    def test_candidate_preparation_packages_the_checked_out_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo, component, registry, _, _, _, _ = self._fixture(root)
            output = root / "candidate-output"
            with patch.object(deploy, "ROOT", repo), patch.dict(
                deploy.os.environ, {"GITHUB_REF_NAME": ""}
            ):
                result = deploy.prepare_candidate(
                    component,
                    registry,
                    output,
                    run_number="42",
                    run_attempt="1",
                    source_ref="main",
                )
            self.assertEqual(result["candidateTag"], "plugins/demo/v1.0.0-rc.42.1")
            self.assertTrue(Path(result["packagePath"]).is_file())
            self.assertEqual(
                deploy.sha256_file(Path(result["packagePath"])), result["packageSha256"]
            )
            release_notes = Path(result["releaseNotesPath"]).read_text(encoding="utf-8")
            self.assertNotIn(
                "Candidate releases do not update the `stable` branch or any stable URL.",
                release_notes,
            )

    def test_first_stable_promotion_creates_feed_branch_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo, component, registry, tag, package, provenance, checksum = self._fixture(root)
            remote = root / "remote.git"
            subprocess.run(["git", "init", "--bare", "-q", str(remote)], check=True)
            subprocess.run(
                ["git", "-C", str(repo), "remote", "add", "origin", str(remote)], check=True
            )
            first_feed = root / "feed-first"
            second_feed = root / "feed-second"

            with patch.object(deploy, "ROOT", repo):
                deploy.prepare_feed_worktree(repo, first_feed, "stable")
                first = deploy.stage_candidate(
                    component,
                    registry,
                    tag,
                    package,
                    provenance,
                    checksum,
                    first_feed,
                    run_josm_parser=False,
                    commit_and_push=True,
                    repo_root=repo,
                )
                self.assertFalse(first["alreadyPublished"])
                remote_commit = subprocess.run(
                    ["git", "--git-dir", str(remote), "rev-parse", "refs/heads/stable"],
                    check=True,
                    text=True,
                    capture_output=True,
                ).stdout.strip()
                self.assertEqual(first["commit"], remote_commit)
                committed_paths = subprocess.run(
                    [
                        "git",
                        "--git-dir",
                        str(remote),
                        "show",
                        "--pretty=format:",
                        "--name-only",
                        "refs/heads/stable",
                    ],
                    check=True,
                    text=True,
                    capture_output=True,
                ).stdout.splitlines()
                self.assertCountEqual(
                    committed_paths, ["plugins/demo/Demo.jar", "plugins/demo/list"]
                )

                deploy.prepare_feed_worktree(repo, second_feed, "stable")
                repeated = deploy.stage_candidate(
                    component,
                    registry,
                    tag,
                    package,
                    provenance,
                    checksum,
                    second_feed,
                    run_josm_parser=False,
                    commit_and_push=True,
                    repo_root=repo,
                )
                self.assertTrue(repeated["alreadyPublished"])
                self.assertEqual(repeated["commit"], remote_commit)


if __name__ == "__main__":
    unittest.main()
