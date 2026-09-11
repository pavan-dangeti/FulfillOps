<div align="center">

# 📦 FulfillOps

**Multi-Stack Fulfillment & Seller Operations Platform**

[![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)](./LICENSE)
[![Status](https://img.shields.io/badge/Status-Working_Simulation-blue?style=for-the-badge)]()

![Angular](https://img.shields.io/badge/Angular_21-DD0031?style=flat-square&logo=angular&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_3-6DB33F?style=flat-square&logo=spring&logoColor=white)
![Java](https://img.shields.io/badge/Java_21-007396?style=flat-square&logo=openjdk&logoColor=white)
![Thymeleaf](https://img.shields.io/badge/Thymeleaf-005F0F?style=flat-square&logo=thymeleaf&logoColor=white)
![Playwright](https://img.shields.io/badge/Playwright-2EAD33?style=flat-square&logo=playwright&logoColor=white)

> A working simulation of the split-stack reality of e-commerce at scale: a modern SPA for sellers and a server-rendered console for customer service, integrated around one shared domain (orders, inventory, fulfillment) and validated end-to-end by a single automated test suite.

</div>

---

## 🏗️ Architecture

| Service | Stack | Runs on | Purpose |
|---|---|---|---|
| `seller-dashboard/` | Angular 21 (standalone, RxJS, Reactive Forms) | `http://localhost:4200` | Inventory + order visibility for sellers |
| `cs-console/` | Spring Boot 3 + Thymeleaf + JPA + H2 | `http://localhost:8080` | Order lookup, refunds, fulfillment status for CS reps |
| `e2e-tests/` | Playwright + TypeScript | — | UI-level assertions over both apps |

Each service has its own `README.md` explaining why its stack was chosen. `TRADEOFFS.md` documents the notable compromises.

---

## ✨ Why two stacks?

FulfillOps intentionally mirrors a common real-world scenario: a modern reactive frontend for one user type (sellers) sitting alongside a legacy-style server-rendered console for another (customer service reps) — both driven off the same underlying order/inventory domain. It's a deliberate exercise in split-stack integration, not a monolith.

---

## 🚀 Prerequisites

- **Node.js 20+** (Angular 21 requires `^20.19 || ^22.12 || >=24`)
- **JDK 21** (`JAVA_HOME` set — e.g. `brew install openjdk@21`)
- **Playwright's Chromium** (`npx playwright install chromium`)

Maven is bundled via the wrapper (`cs-console/mvnw`); no global install needed.

---

## ▶️ Run the applications

```bash
# Seller dashboard (port 4200)
cd seller-dashboard && npm install && npm start

# CS console (port 8080; boots and auto-seeds 12 orders into H2)
cd cs-console && ./mvnw spring-boot:run
```

---

## ✅ Run the end-to-end suite

Playwright boots both apps for you (`playwright.config.ts` → `webServer`), driving them independently with separate `baseURL`s:

```bash
cd e2e-tests
npm install
npx playwright test           # list reporter
npx playwright test --ui      # or: show-report
```

The suite asserts on **rendered DOM state**, not HTTP codes — the point of UI testing. See `e2e-tests/README.md` for what each `test.describe` covers.

---

## 🔍 What each suite proves

- **Angular** — reactive-form submission reflects into the `BehaviorSubject` stream and the list; inline stock edit reflects in place; the inventory route is gated by a route guard.
- **Spring** — search-as-you-type filters the Thymeleaf table; the refund action is gated behind a jQuery confirmation modal and updates the status badge inline without a page reload.

---

## 📁 Project Structure

```
FulfillOps/
├── seller-dashboard/    # Angular 21 SPA — sellers' inventory & order view
├── cs-console/          # Spring Boot 3 server-rendered console — CS ops
├── e2e-tests/           # Playwright + TypeScript, cross-app UI assertions
├── scripts/             # Dev/setup helper scripts
├── README.md
└── TRADEOFFS.md         # Documented compromises and design decisions
```

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](./LICENSE) file for details.

---

<div align="center">

📦 **FulfillOps — Two stacks, one domain, one truth.**

</div>