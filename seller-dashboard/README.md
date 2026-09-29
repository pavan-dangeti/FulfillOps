# Seller Dashboard — Angular 21

Seller-facing inventory and order-visibility tool. Angular is the deliberate
choice here precisely *because* the seller is the modern, high-interaction user:
they need inline stock edits and immediate feedback that a full-page server
round-trip can't offer comfortably.

## Why Angular here

- **SPA interaction model.** Sellers live in this tool all day. Client-side
  routing and instant list updates (no reload) are the expected baseline, and
  Angular's change detection makes that ergonomic.
- **Opinionated state container built in.** RxJS is first-class. The whole
  inventory domain is a single injectable — `InventoryService` — exposing a
  `BehaviorSubject<Product[]>`; components read via the `AsyncPipe` and never
  hold their own copies. Writes go to inventory-service first, and the stream
  only changes once the server has accepted them.
- **Reactive Forms with sync validators.** Required fields, `min(0)` numeric
  constraints, and one *custom* validator (`uniqueSku`) that checks the loaded
  catalog for SKU collisions. It is a hint; the server's unique index is the
  real guarantee, and a 409 from the server is shown as an error banner.
- **Real sign-in.** `AuthService` exchanges the seller's credentials for an
  order-service access token, kept in memory only (a reload signs out). An HTTP
  interceptor attaches it, and a 401 sends the seller back to `/login`. Both
  routes are guarded by `signedInGuard`, and the post-login redirect only
  accepts same-app paths.

## Structure

```
src/app/
  api.ts                      API_BASE_URL and DEMO_MODE injection tokens
  models/catalog.ts           Product + Order types
  services/
    auth.service.ts           sign-in, token in memory
    auth.interceptor.ts       adds the Bearer token, handles 401
    inventory.service.ts      catalog stream backed by inventory-service
    orders.service.ts         order stream backed by order-service
    errors.ts                 RFC 9457 problem details -> message
    api.contract.spec.ts      Pact consumer tests
  guards/auth.guard.ts
  validators/unique-sku.validator.ts
  components/                 presentational (dumb): table + form
  pages/                      smart/container: login, orders, inventory
  app.routes.ts
```

Presentation is separated from data access: `ProductTable`/`ProductForm` are
pure render/emit components; `InventoryPage` is the container that talks to the
service.

## Run

With the backend running (`docker compose up --build --wait` from the repo root):

```bash
npm install
npm start        # http://localhost:4200, sign in as seller / seller-dev-password
```

`ng serve` proxies `/api/products` to inventory-service (:8082) and the rest of
`/api` to order-service (:8081); see `proxy.conf.json`.

**Offline demo mode:** open `http://localhost:4200/?demo=1` to run on built-in
seed data with no backend and no sign-in. Changes are kept in the page only.

## Tests

`npx ng test --watch=false` runs the Pact consumer tests, which drive the real
services and interceptor against Pact mock servers and write
`../contracts/pacts/seller-dashboard-*.json`.
