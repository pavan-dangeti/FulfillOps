# Tradeoffs

The largest deliberate compromise is storage: both services keep data **in
memory**. The CS console uses H2 with `ddl-auto: create-drop` and reseeds itself
on boot, while the seller dashboard holds state in a `BehaviorSubject` that
resets on refresh. This makes the project instantly reproducible with zero
infrastructure — no Postgres instance, no migration tooling, no seed scripts to
keep in sync — at the cost of non-persistence and divergence between the two
stores. In production these would back onto a shared persistent datastore (the
seller service writing, the CS service reading the same orders), but for a
portfolio exercise demonstrating two different presentation stacks, isolation is
the point. The same reasoning justifies jQuery over a SPA for the CS console:
handing an SSR team a full framework would be the expensive migration this
project exists to *avoid*, and a few dozen lines of jQuery deliver the three
interactions a CS rep actually needs.

## Ops Floor (Three.js)

**Why a fourth stack instead of an Angular component.** The obvious place to
put a 3D view is inside `seller-dashboard/` as another routed component — one
`npm install`, one dev server, no new port to remember. It was rejected
because Angular's change detection and zone.js are built around discrete,
batched UI updates, not a render loop mutating hundreds of `InstancedMesh`
transforms 60 times a second; fighting zone.js to keep it out of the render
path would cost more than the isolation of a separate app. `ops-floor/` is a
plain Vite + React + react-three-fiber app instead, at the cost of a third
`npm install` and a third `localhost` port — the same trade the project
already makes between the seller and CS stores, one layer further out.

**Why instancing over discrete meshes.** Every parcel, every SKU tower fill,
every status-lane strip is one `InstancedMesh` batch with per-instance color
and custom attributes, rather than one mesh per order. At 17 seeded orders
this wouldn't matter; it's done because the seed data is meant to grow (the
scene supports up to 512 parcels without a second draw call) and because it's
the difference between a scene that stays under the ~40 draw call budget and
one that doesn't scale past a demo.

**Why polling over WebSockets.** `LiveSource` polls `GET /api/ops/orders`
every 2.5s rather than opening a WebSocket or SSE stream. The CS console has
no push infrastructure today, refunds and ships aren't high-frequency events,
and a 2.5s poll is invisible against the deliberate multi-second lane-travel
animations. Switching to push would mean adding a `spring-boot-starter-
websocket` dependency, a `@ServerEndpoint` (or STOMP topic) alongside
`OpsApiController`, and replacing `LiveSource`'s `setInterval` with a socket
subscription that calls the same `replaceCs` — the `OpsSource` interface on
the frontend doesn't need to change either way, since `subscribe()` already
models push.

**Why the seller plane has no live wire.** The Angular seller dashboard has
no server at all — its state is a `BehaviorSubject` that resets on refresh —
so `LiveSource` only ever polls `cs-console`; the seller plane stays
seed-backed in both modes, and the HUD says so explicitly rather than
inventing an endpoint that doesn't exist. Giving the seller side a live wire
would mean the same thing giving it persistence would: standing up a shared
datastore both apps read from, which is exactly the infrastructure this
project's in-memory-everywhere design deliberately avoids (see above). Short
of that, the cheapest real step would be exposing `InventoryService`/
`OrdersService`'s `BehaviorSubject`s over a small `/api` surface in the
Angular app itself and polling that too — still in-memory, still resets on
refresh, but at least visible cross-origin.