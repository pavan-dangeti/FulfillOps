# Ops Floor — Three.js Operations Visualization

A third frontend that reads the same order/inventory domain as the other two
apps but renders it, rather than tabulating it. Where the seller dashboard and
the CS console each show *their own* store, Ops Floor puts both stores on one
floor at once — so the thing the other two apps can't show you (that they
disagree) becomes the picture.

## Why a third app, and why Three.js

- **The divergence is the point, and a table can't show it well.** ORD-1039
  through ORD-1042 exist in both the Angular `BehaviorSubject` and the Spring
  H2 store, with different customers, quantities, statuses and totals —
  `TRADEOFFS.md` documents that as deliberate store isolation. A side-by-side
  table makes you diff four rows by eye. A spatial view can put both parcels
  on the floor and draw the mismatch as a line between them — proximity and
  color do the noticing for you.
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
                                        └─   REFUNDED   ← seller has no
                                                            REFUNDED lane —
                                                            the Angular model
                                                            has no such status
```

- **Two planes, one floor.** Seller orders (Angular's 5 seeded orders) on the
  left, CS orders (Spring's 12 seeded orders) on the right, each parceled
  onto lanes by status. The seller plane physically has four lanes; the CS
  plane has a fifth REFUNDED lane. That absence is deliberate — it's the same
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

`VITE_SOURCE=live` polls `cs-console`'s new read-only feed
(`GET /api/ops/orders` every 2.5s) for the CS plane. **The seller plane stays
seed-backed in both modes** — the Angular app has no server and no wire
protocol at all, so there's nothing to poll, and the HUD says so explicitly
rather than pretending otherwise.

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
