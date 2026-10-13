import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_status


class ReleaseStatusTests(unittest.TestCase):
    def test_table_has_a_row_for_each_registered_component(self):
        registry = release_status.deploy.load_registry()

        table = release_status.render_table(
            registry["components"], [], registry["repository"]
        )

        self.assertEqual(table.count("\n| `"), len(registry["components"]))
        self.assertTrue(all("| N/A | N/A | N/A | N/A |" in line for line in table.splitlines()[2:]))

    def test_table_uses_latest_versions_and_links_encoded_release_tags(self):
        components = [{"id": "styles/tiger-stripes"}]
        releases = [
            {
                "tag_name": "styles/tiger-stripes/v1.0.2",
                "draft": False,
                "prerelease": False,
                "updated_at": "2026-10-12T12:00:00Z",
            },
            {
                "tag_name": "styles/tiger-stripes/v1.0.1",
                "draft": False,
                "prerelease": False,
                "updated_at": "2026-10-13T12:00:00Z",
            },
            {
                "tag_name": "styles/tiger-stripes/v1.0.3-rc.41.1",
                "draft": False,
                "prerelease": True,
                "updated_at": "2026-10-12T15:30:00Z",
            },
            {
                "tag_name": "styles/tiger-stripes/v1.0.3-rc.42.1",
                "draft": False,
                "prerelease": True,
                "updated_at": "2026-10-12T16:30:00Z",
            },
        ]

        table = release_status.render_table(
            components, releases, "lumikeiju/saarelai"
        )

        self.assertIn(
            "[1.0.2](https://github.com/lumikeiju/saarelai/releases/tag/"
            "styles%2Ftiger-stripes%2Fv1.0.2) | 2026-10-12",
            table,
        )
        self.assertIn(
            "[1.0.3-rc.42.1](https://github.com/lumikeiju/saarelai/releases/tag/"
            "styles%2Ftiger-stripes%2Fv1.0.3-rc.42.1) | 2026-10-12",
            table,
        )

    def test_missing_releases_display_na_for_each_level(self):
        table = release_status.render_table(
            [{"id": "plugins/not-released"}], [], "lumikeiju/saarelai"
        )

        self.assertIn("| `plugins/not-released` | N/A | N/A | N/A | N/A |", table)

    def test_failed_promotion_draft_is_not_reported_as_a_stable_release(self):
        releases = [
            {
                "tag_name": "plugins/example/v1.0.2",
                "draft": True,
                "prerelease": False,
                "updated_at": "2026-10-12T16:30:00Z",
            },
            {
                "tag_name": "plugins/example/v1.0.2-rc.42.1",
                "draft": False,
                "prerelease": True,
                "updated_at": "2026-10-12T15:30:00Z",
            },
        ]

        table = release_status.render_table(
            [{"id": "plugins/example"}], releases, "lumikeiju/saarelai"
        )

        self.assertIn(
            "| `plugins/example` | N/A | N/A | "
            "[1.0.2-rc.42.1](https://github.com/lumikeiju/saarelai/releases/tag/"
            "plugins%2Fexample%2Fv1.0.2-rc.42.1) | 2026-10-12 |",
            table,
        )

    def test_drafts_and_release_types_for_other_components_are_ignored(self):
        components = [{"id": "plugins/example"}]
        releases = [
            {
                "tag_name": "plugins/example/v1.0.0",
                "draft": True,
                "prerelease": False,
                "updated_at": "2026-10-12T12:00:00Z",
            },
            {
                "tag_name": "plugins/example/v1.0.1-rc.3.1",
                "draft": False,
                "prerelease": False,
                "updated_at": "2026-10-12T12:00:00Z",
            },
            {
                "tag_name": "plugins/other/v1.0.0",
                "draft": False,
                "prerelease": False,
                "updated_at": "2026-10-12T12:00:00Z",
            },
        ]

        table = release_status.render_table(components, releases, "lumikeiju/saarelai")

        self.assertIn("| `plugins/example` | N/A | N/A | N/A | N/A |", table)

    def test_document_update_replaces_only_the_marked_section(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "README.md"
            path.write_text(
                "# Catalog\n\n"
                f"{release_status.START_MARKER}\nold table\n{release_status.END_MARKER}\n"
                "\nKeep this text.\n",
                encoding="utf-8",
            )

            changed = release_status.update_document(path, "| generated |")

            self.assertTrue(changed)
            self.assertEqual(
                path.read_text(encoding="utf-8"),
                "# Catalog\n\n"
                f"{release_status.START_MARKER}\n| generated |\n{release_status.END_MARKER}\n"
                "\nKeep this text.\n",
            )
            self.assertFalse(release_status.update_document(path, "| generated |"))

    def test_release_without_updated_timestamp_fails_explicitly(self):
        release = {
            "tag_name": "plugins/example/v1.0.0",
            "draft": False,
            "prerelease": False,
        }

        with self.assertRaisesRegex(release_status.ReleaseStatusError, "updated_at"):
            release_status.render_table(
                [{"id": "plugins/example"}], [release], "lumikeiju/saarelai"
            )


if __name__ == "__main__":
    unittest.main()
