#!/usr/bin/env bash
# One command: ensure JDK on PATH, then run the full E2E suite.
# Playwright's webServer boots both services itself (see e2e-tests/playwright.config.ts).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -z "${JAVA_HOME:-}" ] && [ -x /opt/homebrew/opt/openjdk@21/bin/java ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi

cd "$ROOT/e2e-tests"
npm install --silent
npx playwright test