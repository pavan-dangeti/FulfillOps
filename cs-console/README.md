# Customer Service & Fulfillment Console — Spring Boot + Thymeleaf

Internal operations console for CS representatives: order lookup, refund
processing, and fulfillment-status management. The stack is server-rendered on
purpose, modeling the real-world org where ops tooling predates the SPA era and
migrating it isn't worth the cost.

## Why Thymeleaf here

- **Staff tools are form-and-list shaped.** A CS rep needs a searchable list, a
  detail page, and a couple of confirmable actions. That is Thymeleaf's sweet
  spot; no framework-driven state sync is required.
- **Server-rendered = trivially correct.** The data is already in memory in the
  same JVM (Spring Data JPA → H2). Rendering it into HTML with `@Controller`
  return values (not `@RestController`) demonstrates the template architecture:
  controllers return view names, and fragments (`status-badge`) are reused across
  pages and returned as DOM responses to jQuery.
- **Progressive enhancement, not a migration.** Rather than re-platform onto a
  SPA (the expensive move this project explicitly avoids), jQuery layers the
  three interactions CS needs onto the SSR base: live search-as-you-type
  filtering, a confirmation modal gating refunds, and inline DOM updates after a
  refund — no full page reload.

## How it works

- `OrderController` (`@Controller`) returns Thymeleaf view names; the refund and
  ship endpoints return a **fragment** (`fragments/status-badge :: badge`) that
  jQuery injects into the DOM in place of the old status cell.
- `DataSeeder` (`CommandLineRunner`) seeds 12 realistic orders on startup if H2
  is empty; H2 is in-memory with `ddl-auto: create-drop`.
- `OrderService` owns the state transitions (`refund`, `ship`) and guards
  against refunding a refunded order.
- `OpsApiController` (`@RestController`, `/api/ops/**`) is a small read-only
  JSON feed added for `ops-floor/`'s 3D visualization — `GET /api/ops/orders`
  and `GET /api/ops/health`. It sits next to `OrderController`, not inside
  it: the Thymeleaf routes stay `@Controller`/fragment-returning and
  same-origin, this one returns an `OrderView` record (never the JPA entity
  directly — `open-in-view: false` stays in force) and is the only thing
  covered by CORS (`OpsCorsConfig`, `http://localhost:5174` only).

## Run

```bash
./mvnw spring-boot:run     # http://localhost:8080  (H2 console: /h2-console)
```

Key flows: **Orders** — type in the filter box to see live row filtering; hit
**Refund** to see the confirmation modal, then **Refund payment** to watch the
badge flip to `REFUNDED` inline.