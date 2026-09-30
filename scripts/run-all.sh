#!/usr/bin/env bash
# One command: install the e2e dependencies and run the full suite.
# global-setup.ts builds and starts the compose stack on a fresh database, and
# Playwright's webServer starts the two frontends (see e2e-tests/playwright.config.ts).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT/e2e-tests"
npm install --silent
npx playwright test
