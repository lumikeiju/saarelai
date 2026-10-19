<!-- @format -->

# Saarelai - Changelog

All notable changes to Saarelai are documented in this file. Component changes remain in each component's own changelog; there is no repository-wide product version.

The format of this changelog is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## 2026-10-18

### Changed

- Use a narrowly scoped release token with workflow write access when creating or resuming stable draft releases.

## 2026-10-17

### Changed

- Queue beta and stable releases together in a FIFO concurrency queue so new runs do not cancel existing pending runs.
- Rename the stable publication action from `promote-stable` to `stable`.
- Derive preset version dates from committed XML instead of requiring a planned publication date for beta creation and stable promotion.
- Remove the four-job parallelism cap from component validation, allowing GitHub Actions to run component jobs up to the account's available runner capacity.
- Require a relevant changelog update after every development change.
