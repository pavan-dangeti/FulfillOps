# Customer Service & Fulfillment Console — Spring Boot + Thymeleaf

Internal operations console for CS representatives: order lookup, refund
processing, and fulfillment-status management. The stack is server-rendered on
purpose, modeling the real-world org where ops tooling predates the SPA era and
migrating it isn't worth the cost.

## Why Thymeleaf here

- **Staff tools are form-and-list shaped.** A CS rep needs a searchable list, a
  detail page, and a couple of confirmable actions. That is Thymeleaf's sweet
  spot; no framework-driven state sync is required.
- **Server-rendered keeps the token off the browser.** Controllers return view
  names, fragments (`status-badge`) are reused across pages and returned as DOM
  responses to jQuery, and the rep's order-service access token never leaves
  the server-side session.
- **Progressive enhancement, not a migration.** Rather than re-platform onto a
  SPA (the expensive move this project explicitly avoids), jQuery layers the
  three interactions CS needs onto the SSR base: live search-as-you-type
  filtering, a confirmation modal gating refunds, and inline DOM updates after a
  refund — no full page reload.

## How it works

- **No data of its own.** `OrderService` calls order-service over `RestClient`
  (`fulfillops.order-service-url`); `Order` is a record of order-service's JSON.
- **Sign-in** (`SecurityConfig`) is a form login that exchanges the rep's
  username and password for an order-service access token. Only accounts with
  the CS role get in. The token sits in the session's `Authentication` and a
  `RestClient` interceptor forwards it on every call. The session times out
  after 55 minutes, before the one-hour token expires.
- **CSRF** stays on: the layout exposes the token in meta tags and `console.js`
  sends it as a header with every jQuery POST.
- `OrderController` (`@Controller`) returns Thymeleaf view names; the refund and
  ship endpoints return a **fragment** (`fragments/status-badge :: badge`) that
  jQuery injects into the DOM in place of the old status cell. Refunding twice
  is a no-op in order-service; shipping a refunded order is refused (409) and
  the badge stays as it was.
- `OpsApiController` (`/api/ops/**`) is a read-only JSON feed for `ops-floor/`.
  It needs no user session: it signs in to order-service with a dedicated
  read-only OPS account (`OPS_FEED_PASSWORD`), caches that token until a
  minute before expiry, and never receives customer emails. It is the only
  path covered by CORS (`OpsCorsConfig`, `http://localhost:5174` only).

## Run

The console runs as part of the compose stack from the repository root:

```bash
cp .env.example .env
docker compose up --build --wait     # http://localhost:8080, sign in as cs / cs-dev-password
```

Key flows: **Orders** — type in the filter box to see live row filtering; hit
**Refund** to see the confirmation modal, then **Refund payment** to watch the
badge flip to `REFUNDED` inline.

## Tests

`./mvnw -pl cs-console verify` runs `ConsoleWebTest` (the whole console with
order-service mocked: sign-in redirect, role checks, CSRF, template rendering)
and `OrderServiceContractTest` (Pact consumer tests that write
`contracts/pacts/cs-console-order-service.json`).
