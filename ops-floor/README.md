# Ops Floor — Three.js Operations Visualization

The live view of the order system. In live mode it polls the CS console's read-only feed and puts
every order on a 3D floor, laned by status, so a 1,000-order storm is something you watch: orders
arrive, move through the saga, and settle in their status lane or in FAILED, with every step change
in the event feed. In seed mode it runs with no backend at all, which is what the public deployment does.

## Why a third app, and why Three.js

- **Some things read better as a picture.** A storm of a thousand orders, or the gap between two
  records of the same order, is hard to see in a table and easy to see on a floor. The project
  began with the seller app and the CS console reading two different stores that disagreed; seed
  mode still replays that dataset, and draws each disagreement as a line between the two parcels
  (the apps now share one order store; see `TRADEOFFS.md`).
- **Why not an Angular component.** Angular's change detection and zone.js
  are built around discrete UI updates, not a 60fps render loop driving
  hundreds of instanced meshes. A separate Vite + React + react-three-fiber
  app keeps that render loop outside Angular's world entirely, at the cost of
  a third `npm install` and a third `localhost` port — the same isolation
  trade the repo already makes between the seller and CS stores, one layer
  further out.
- **Why not a generic warehouse animation.** An early draft of this app was a
  robots-picking-bins simulation. It looked good but had nothing to do with
  this repo's actual domain (there's no warehouse, no bins, no pick paths in
  either real store) — it would have been a demo bolted onto the project
  rather than a rendering of the project. This version only draws things
  that exist in the other two apps' code.

## What's on the floor

```
        seller · angular                    cs · spring
  PENDING    ─┐                         ┌─   PENDING
  PROCESSING ─┤   [SKU-1001] [SKU-1002] ├─   PROCESSING
  SHIPPED    ─┤   [SKU-1003] [SKU-1004] ├─   SHIPPED
  DELIVERED  ─┘   [SKU-1005]            ├─   DELIVERED
                                        ├─   REFUNDED   ← outcomes the seller
                                        └─   FAILED       model has no lane for
```

- **Two planes, one floor.** Seller orders (Angular's 5 seeded orders) on the
  left, CS orders on the right — Spring's 12 seeded orders, or every real
  order in live mode — each parceled onto lanes by status. The seller plane
  physically has four lanes; the CS plane adds REFUNDED and FAILED, where an
  order lands when the saga gives up. That absence is deliberate — it's the same
  argument `TRADEOFFS.md` makes about the seller store, rendered as geometry
  instead of prose.
- **A SKU column down the middle.** Five towers, one per product, fill height
  on a log scale so stock 0 and stock 142 both read clearly. Color follows
  the same health bands the seller dashboard uses (`stock <= 10` is a warning,
  `0` is a slow emissive pulse).
- **Divergence arcs.** For every order number that exists in both stores, an
  arc connects the two parcels across the SKU column. A quiet, dim, thin arc
  means the two records agree. A bright, dashed, animated arc means they
  don't — hover it (or click a log row for that order) for a field-by-field
  diff of customer/sku/quantity/status/total.
- **A scripted timeline**, running standalone with no backend: a refund, a
  ship, and a restock, each logged in the event feed and animated as a
  parcel or tower actually moving — never a jump-cut unless
  `prefers-reduced-motion` is set.

## Data sources

`VITE_SOURCE=seed` (default) runs entirely client-side against the same seed
values as `InventoryService`/`OrdersService`/`DataSeeder`, so the 3D view and
the other two apps agree on first paint with nothing running.

`VITE_SOURCE=live` polls `cs-console`'s read-only feed (`GET /api/ops/orders`
every 2.5s), which reads order-service with a read-only account, for the CS
plane. **The seller plane stays seed-backed in both modes**: sellers and CS
now share one order store, so a live seller plane would only mirror the CS
plane. The HUD says so explicitly rather than pretending otherwise.

## Run

```bash
npm install
npm run dev       # http://localhost:5174
```

Add `?stats=1` to see draw calls, triangle count, and geometry/texture memory.
Everything renders with `frameloop="demand"`: a static, unhovered scene does
zero GPU work until something changes.

## Testability

A `<canvas>` is opaque to Playwright, so scene truth is mirrored into a
hidden `data-testid="scene-state"` node (selected order, visible parcel
count, divergence count, camera preset, pause state, source mode, and a
`data-scene-ready` flag that flips once the Canvas commits its first frame —
wait on that, not a fixed delay, before asserting on anything that lives
inside the canvas). See `e2e-tests/tests/ops-floor.spec.ts`.
