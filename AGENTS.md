<!-- @format -->

# {{project_name}} conventions

Read this file before editing.

- Use [Conventional Commits](https://www.conventionalcommits.org/) (`type(scope): description`).
- Name branches in the format `type/scope/short-description`.
- Keep changes focused, update related documentation, and validate them with the project's configured checks.
- For versioned packages, use [Semantic Versioning](https://semver.org/) and synchronize release metadata where it exists.
- Ensure user-facing interfaces meet or exceed [WCAG 2.2 Level AA](https://www.w3.org/TR/WCAG22/) where applicable.
- Use {{package_manager}} with {{runtime}} {{runtime_version}}.
- Place source code in `{{source_directory}}` and tests in `{{test_directory}}`.
- Run `{{validation_command}}` before submitting changes.
- Follow the {{release_model}} release model.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the contribution workflow.
