# End-to-End Tests — Playwright + TypeScript

One suite, three applications. Each `test.describe` targets its own `baseURL`
via Playwright **projects**, so the apps are tested in isolation even though a
single command runs everything.

```
tests/
  angular.spec.ts     baseURL http://localhost:4200   seller dashboard
  spring.spec.ts      baseURL http://localhost:8080   CS console
  ops-floor.spec.ts   baseURL http://localhost:5174   Ops Floor (seed mode)
  accounts.ts         local sign-in credentials (overridable by env)
```

## Coverage (DOM assertions, not HTTP codes)

**Angular — Seller Dashboard**
- sign-in is required; a wrong password shows an error
- orders load from order-service
- form submission appends the new product to the inventory table (saved by inventory-service)
- inline stock edit reflects in the row without reload
- `/inventory` redirects to sign-in when signed out (route guard)
- `?demo=1` runs offline on seed data

**Spring — CS Console**
- search-as-you-type filters rows; clicking through renders the detail page
- refund is gated by the confirmation modal, flips the status badge inline, and survives a reload
- shipping advances a `PROCESSING` order to `SHIPPED`
- signing out ends the session

**Ops Floor** — see `tests/ops-floor.spec.ts`.

## Run

`global-setup.ts` recreates the compose stack (Postgres, services, CS console)
on a fresh database before every run, because the specs change data. Playwright
then starts the seller dashboard and Ops Floor dev servers via `webServer`.
Requires Docker and Node.

```bash
npm install
npx playwright install chromium   # once
npx playwright test
npx playwright show-report        # HTML report on failures
```

Set `E2E_SKIP_STACK=1` to run against a stack you started yourself, and
`E2E_SELLER_PASSWORD` / `E2E_CS_PASSWORD` if it doesn't use the local
accounts from `.env.example`.

The suite asserts on **rendered DOM** (`toContainText` on live elements, visible
counts after jQuery filtering) because true UI testing verifies what the user
sees, not what the wire returns.
