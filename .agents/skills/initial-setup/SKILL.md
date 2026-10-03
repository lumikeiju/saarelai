---
name: initial-setup
description: "Use when converting this template into a specific project: establish its identity, stack, tooling, repository configuration, and validation."
---

# Initial Setup

Use this skill once, immediately after creating a repository from this template. Its outcome is an initialized project with no template identity, placeholder metadata, or unused scaffolding.

Do not begin feature work until the repository reflects the project it will become.

## Placeholder inventory

Replace every placeholder before installing dependencies, publishing a package, enabling CI, or creating a release. The placeholders in `package.json` are intentionally invalid until replaced.

`{{project_name}}`, `{{package_name}}`, `{{project_description}}`, `{{project_status}}`, `{{project_version}}`, `{{repository_url}}`, `{{homepage_url}}`, `{{issue_tracker_url}}`, `{{maintainer_name}}`, `{{maintainer_contact}}`, `{{license}}`, `{{license_spdx_id}}`, `{{copyright_year}}`, `{{copyright_holder}}`, `{{release_date_yyyy_mm_dd}}`, `{{runtime}}`, `{{runtime_version}}`, `{{package_manager}}`, `{{source_directory}}`, `{{test_directory}}`, `{{validation_command}}`, `{{release_model}}`, `{{project_keyword}}`, `{{setup_instructions}}`, `{{configuration_instructions}}`, `{{development_instructions}}`, `{{release_and_deployment_instructions}}`, and `{{contributor_setup_instructions}}`. URL placeholders are host-and-path values without a protocol because `package.json` supplies `https://`.

## 1. Establish the project brief

Inspect the repository before editing, then collect or infer these decisions. Ask only for information that cannot be found in the repository or request.

- Display name, package slug, one-sentence purpose, owner, and canonical repository URL.
- Visibility, licensing and copyright holder, and whether the project is a published or versioned package.
- Technology stack, package manager, supported runtimes, source layout, and test strategy.
- Deployment model, CI requirements, environment variables, and any security or accessibility obligations.

Use safe defaults when appropriate: repositories should remain private until publication is intended, and no service or deployment integration should be added without a project need.

## 2. Replace the template identity

Tailor or replace the following files as applicable:

| File | Required outcome |
| --- | --- |
| `README.md` | Accurate overview, prerequisites, local development, validation, deployment or release information, and project links. |
| `package.json` and lockfile | Correct name, version, description, license, repository metadata, scripts, dependencies, and package-manager version. Remove them entirely if the chosen stack does not use them. Regenerate the lockfile after dependency changes. |
| `CHANGELOG.md` | Use the selected version and a real date; synchronize it with package metadata when both apply. |
| `LICENSE` | The selected license with the real copyright holder and year, or remove it only when the project is intentionally unlicensed. |
| `AGENTS.md` and `CONTRIBUTING.md` | Project-specific workflows, commands, architecture conventions, and contribution guidance. Retain relevant quality standards. |
| Workspace, links, badges, and metadata | Update repository URLs, issue links, social images, and badges. |

Search the complete repository for the template name and placeholders such as `project-name`, `Project Name`, `[Copyright holder]`, `YYYY-MM-DD`, and “Replace”. Remove every unintended match.

## 3. Select and configure the stack

- Keep Bun and Prettier only when they suit the project. Otherwise replace their configuration, scripts, dependencies, and lockfile with the selected tooling.
- Add the real application or library layout, configuration, dependencies, tests, and exact scripts for formatting, linting, type checking, testing, building, and running.
- Review `.gitignore`, `.prettierignore`, `.editorconfig`, and environment-file handling for the chosen stack. Add a documented `.env.example` whenever runtime configuration is required; never commit secrets.
- Add CI only after validation commands exist. Configure dependency updates only for ecosystems and workflows that the project actually uses.
- For user-facing interfaces, retain or add WCAG 2.2 AA requirements and test the relevant interaction, keyboard, contrast, and semantic behavior.

## 4. Remove unused template material

- Delete stale stack configuration, dependencies, scripts, examples, generated assets, and automation that do not support the selected project.
- Do not restore historical template files merely because they exist in Git history.
- Keep project conventions that remain applicable, but avoid rules that assume a language, package manager, release model, or deployment platform the project does not use.

## 5. Verify the initialized repository

- Run every configured formatter, linter, type check, test suite, build, and security check. Do not claim checks that the project does not provide.
- Check internal documentation links and repository metadata, then confirm that no template placeholders or prior-template names remain.
- Confirm the lockfile is current when a package manager is used.
- Confirm documentation describes the actual project and includes applicable security, accessibility, contribution, release, and deployment guidance.
- Search tracked documentation and configuration for unresolved `{{...}}` placeholders and stale identity before completing setup.
