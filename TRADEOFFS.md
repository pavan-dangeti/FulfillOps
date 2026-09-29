# Tradeoffs

## Storage: one Postgres, one schema per service

The project started with each app keeping its data in memory: the CS console
used H2 with `create-drop` and reseeded on boot, and the seller dashboard held
state in a `BehaviorSubject` that reset on refresh. That made it instantly
reproducible, but the two stores diverged by design and nothing persisted.

Orders, stock, payments and fulfilment now live in Postgres, each owned by one
service. They share **one Postgres instance** but get **one schema and one login
role each**, and a role has no rights outside its own schema
(`deploy/postgres/init.sh`, checked by `scripts/check-schema-isolation.sh`).

- **Why not a database server per service?** Separate instances give stronger
  isolation (independent upgrades, failure domains, connection limits) but cost
  more memory than a small demo host has to spare. Schema-per-service keeps the
  property that matters for design (no service can read or write another's
  tables, so cross-service data flows only through APIs) at a fraction of the
  cost. Moving a schema to its own instance later is a connection-string change.
- **Why not one shared schema?** That is what makes microservices a distributed
  monolith: any service could join on another's tables and every schema change
  would need coordinating.

**The CS console holds no data at all now.** It calls order-service with the
signed-in rep's token. The jQuery-over-Thymeleaf reasoning still holds: handing
an SSR team a full framework would be the expensive migration this project
exists to *avoid*, and a few dozen lines of jQuery deliver the interactions a
CS rep needs.

## Authentication: RS256 tokens from order-service

order-service exchanges an operator's password for a one-hour JWT signed with
RS256, and publishes only the public key at `/.well-known/jwks.json`. The other
services verify tokens against that JWKS and hold no key that could sign one,
so compromising inventory, payment or fulfilment does not let an attacker mint
tokens. (An earlier version shared one HS256 secret across all services, which
gave every service the power to forge any role.) Verifiers accept RS256 only,
which blocks the "sign HS256 with the public key" confusion attack; this, a
foreign key, a wrong issuer and expiry are covered by `TokenVerificationTest`.

The cost: the signing key is generated at startup and held in memory, so a
restart of order-service invalidates issued tokens (users sign in again) and
order-service cannot run as several replicas. Loading the key from a secret
store removes both limits.

Sign-in is throttled per account: after five consecutive wrong passwords the
account is refused (429) for a minute. That caps online guessing without
letting an attacker lock an operator out for long. The count is in memory and
per instance; per-IP limits belong at the reverse proxy in front of a public
deployment.

The seller dashboard keeps its token in memory only, so a page reload signs the
seller out. The CS console keeps the token in the server-side session, never in
the browser, with a session timeout shorter than the token's lifetime.

## Payments and fulfilment use plain JDBC

order-service and inventory-service use JPA; payment-service and
fulfilment-service use `JdbcClient`. Their core operations are single
statements whose correctness comes from the database (`INSERT … ON CONFLICT DO
NOTHING` on a unique order number, a conditional `UPDATE … WHERE allocated <
capacity`). Writing those statements directly is clearer than coaxing an ORM
into them.

## Ops Floor (Three.js)

**Why a fourth stack instead of an Angular component.** The obvious place to
put a 3D view is inside `seller-dashboard/` as another routed component — one
`npm install`, one dev server, no new port to remember. It was rejected
because Angular's change detection and zone.js are built around discrete,
batched UI updates, not a render loop mutating hundreds of `InstancedMesh`
transforms 60 times a second; fighting zone.js to keep it out of the render
path would cost more than the isolation of a separate app. `ops-floor/` is a
plain Vite + React + react-three-fiber app instead, at the cost of a third
`npm install` and a third `localhost` port.

**Why instancing over discrete meshes.** Every parcel, every SKU tower fill,
every status-lane strip is one `InstancedMesh` batch with per-instance color
and custom attributes, rather than one mesh per order. At 17 seeded orders
this wouldn't matter; it's done because the seed data is meant to grow (the
scene supports up to 512 parcels without a second draw call) and because it's
the difference between a scene that stays under the ~40 draw call budget and
one that doesn't scale past a demo.

**Why polling over WebSockets.** `LiveSource` polls `GET /api/ops/orders`
every 2.5s rather than opening a WebSocket or SSE stream. Refunds and ships
aren't high-frequency events, and a 2.5s poll is invisible against the
deliberate multi-second lane-travel animations. Switching to push would mean a
stream endpoint next to `OpsApiController` and replacing `LiveSource`'s
`setInterval` with a subscription that calls the same `replaceCs` — the
`OpsSource` interface on the frontend doesn't change either way, since
`subscribe()` already models push.

**Why the seller plane has no live wire yet.** The Ops Floor was built around
two separate stores disagreeing. Now that sellers and CS read the same order
store, a live seller plane would simply mirror the CS plane, so live mode still
polls only the CS feed and the seller plane stays seed-backed. The HUD says so
explicitly. That feed is public and read-only, so it carries initials instead
of customer names and no emails, and it is cached for two seconds so anonymous
traffic cannot multiply load on order-service. It signs in to order-service
with a dedicated read-only OPS account.
