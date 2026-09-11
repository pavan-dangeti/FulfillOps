# FulfillOps — Multi-Stack Fulfillment & Seller Operations Platform

A working simulation of the split-stack reality of e-commerce at scale: a modern
SPA for sellers and a server-rendered console for customer service, integrated
around one shared domain (orders, inventory, fulfillment) and validated
end-to-end by a single automated test suite.

## Architecture

| Service | Stack | Runs on | Purpose |
| --- | --- | --- | --- |
| `seller-dashboard/` | Angular 21 (standalone, RxJS, Reactive Forms) | `http://localhost:4200` | Inventory + order visibility for sellers |
| `cs-console/` | Spring Boot 3 + Thymeleaf + JPA + H2 | `http://localhost:8080` | Order lookup, refunds, fulfillment status for CS reps |
| `e2e-tests/` | Playwright + TypeScript | — | UI-level assertions over both apps |

Each service has its own `README.md` explaining **why** its stack was chosen.
`TRADEOFFS.md` documents the notable compromises.

## Prerequisites

- Node.js 20+ (Angular 21 requires `^20.19 || ^22.12 || >=24`)
- JDK 21 (`JAVA_HOME` set — e.g. `brew install openjdk@21`)
- Playwright's Chromium (`npx playwright install chromium`)

Maven is bundled via the wrapper (`cs-console/mvnw`); no global install needed.

## Run the applications

```bash
# Seller dashboard (port 4200)
cd seller-dashboard && npm install && npm start

# CS console (port 8080; boots and auto-seeds 12 orders into H2)
cd cs-console && ./mvnw spring-boot:run
```

## Run the end-to-end suite

Playwright boots both apps for you (`playwright.config.ts` → `webServer`), driving
them independently with separate `baseURL`s:

```bash
cd e2e-tests
npm install
npx playwright test          # list reporter
npx playwright test --ui     # or: show-report
```

The suite asserts on **rendered DOM state**, not HTTP codes — the point of UI
testing. See `e2e-tests/README.md` for what each `test.describe` covers.

## What each suite proves

- **Angular** — reactive-form submission reflects into the `BehaviorSubject`
  stream and the list; inline stock edit reflects in place; the inventory route
  is gated by a route guard.
- **Spring** — search-as-you-type filters the Thymeleaf table; the refund action
  is gated behind a jQuery confirmation modal and updates the status badge inline
  without a page reload.