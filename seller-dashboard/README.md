# Seller Dashboard — Angular 21

Seller-facing inventory and order-visibility tool. Angular is the deliberate
choice here precisely *because* the seller is the modern, high-interaction user:
they need optimistic UI, inline stock edits, and immediate feedback that a
full-page server round-trip can't offer comfortably.

## Why Angular here

- **SPA interaction model.** Sellers live in this tool all day. Client-side
  routing and instant list updates (no reload) are the expected baseline, and
  Angular's change detection makes that ergonomic.
- **Opinionated state container built in.** RxJS is first-class. The whole
  inventory domain is a single injectable — `InventoryService` — exposing a
  `BehaviorSubject<Product[]>`; components read via the `AsyncPipe` and never
  hold their own copies. That "one source of truth" is real work Angular does
  for you, vs. hand-rolling pub/sub in a server-rendered page.
- **Reactive Forms with sync validators.** Required fields, `min(0)` numeric
  constraints, and one *custom* validator (`uniqueSku`) that checks the live
  stream for SKU collisions — all synchronous, all typed.
- **A real guard.** `/inventory` is gated by `inventoryAccessGuard`; unauthed
  visiters are redirected to `/orders`.

## Structure

```
src/app/
  models/catalog.ts           Product + Order types
  services/                   injectable + BehaviorSubject-backed state
    inventory.service.ts
    orders.service.ts
    auth.service.ts
  guards/inventory-access.guard.ts
  validators/unique-sku.validator.ts
  components/                 presentational (dumb): table + form
  pages/                      smart/container: orders-page + inventory-page
  app.routes.ts               two routes + guard
```

Presentation is separated from data access: `ProductTable`/`ProductForm` are
pure render/emit components; `InventoryPage` is the container that talks to the
service.

## Run

```bash
npm install
npm start        # http://localhost:4200 (use "Sign in" to unlock /inventory)
```

Key flows: **Inventory → Add product** (validators, custom SKU uniqueness) and
**click "edit" on any stock cell** for inline updates. Orders is read-only.