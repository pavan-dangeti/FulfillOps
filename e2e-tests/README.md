# End-to-End Tests — Playwright + TypeScript

One suite, two independent applications. Each `test.describe` targets its own
`baseURL` via Playwright **projects**, so the Angular and Spring apps are tested
in isolation even though a single command runs everything.

```
tests/
  angular.spec.ts   baseURL http://localhost:4200
  spring.spec.ts    baseURL http://localhost:8080
```

## Coverage (DOM assertions, not HTTP codes)

**Angular — Seller Dashboard**
- form submission appends the new product to the inventory table
- inline stock edit reflects in the row without reload
- `/inventory` redirects when not signed in (route guard)

**Spring — CS Console**
- search-as-you-type filters rows; clicking through renders the detail page
- refund is gated by the confirmation modal and flips the status badge inline
- shipping advances a `PROCESSING` order to `SHIPPED`

## Run

Playwright boots both servers via `webServer` in `playwright.config.ts`
(requires Node and a JDK on `JAVA_HOME`; the Spring service uses the bundled
Maven wrapper, so no global `mvn` is needed):

```bash
npm install
npx playwright install chromium   # once
npx playwright test
npx playwright show-report        # HTML report on failures
```

The suite asserts on **rendered DOM** (`toContainText` on live elements, visible
counts after jQuery filtering) because true UI testing verifies what the user
sees, not what the wire returns.