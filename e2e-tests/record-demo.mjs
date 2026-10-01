// Records the demo video. Everything in it is the real system: the orders on the 3D floor are placed
// through the API while it is being filmed, the trace shown is the one those orders produced, and the
// dashboard is scraping live.
//
//   docker compose --profile observability up -d --wait
//   npm run start -- --host 127.0.0.1            # in seller-dashboard/
//   VITE_SOURCE=live npm run dev -- --port 5174   # in ops-floor/
//   node record-demo.mjs
//
// Output: demo-raw/*.webm, then concatenated into docs/demo.mp4. The scenes are deliberately the
// five things a reviewer cannot get from the README alone.

import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readdirSync, rmSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, 'demo-raw');
const ORDER_SERVICE = 'http://localhost:8081';
const SELLER = { username: 'seller', password: 'seller-dev-password' };
const SKU = 'SKU-DEMO';

const size = { width: 1280, height: 720 };

async function api(path, body, token) {
  const response = await fetch(`${ORDER_SERVICE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`${path} -> ${response.status}`);
  return response.json();
}

async function token() {
  return (await api('/api/auth/token', SELLER)).accessToken;
}

// A caption burned into the video, so each scene says what it is showing rather than leaving a
// viewer to guess.
async function caption(page, text, sub) {
  await page.evaluate(
    ([t, s]) => {
      document.getElementById('demo-caption')?.remove();
      const bar = document.createElement('div');
      bar.id = 'demo-caption';
      bar.style.cssText = [
        'position:fixed', 'left:0', 'right:0', 'bottom:0', 'z-index:2147483647',
        'padding:18px 28px 22px', 'background:linear-gradient(transparent, rgba(4,7,14,.92) 40%)',
        'color:#eef3fb', 'font:600 20px/1.35 ui-sans-serif,-apple-system,Segoe UI,sans-serif',
        'pointer-events:none', 'text-shadow:0 1px 3px rgba(0,0,0,.9)',
      ].join(';');
      bar.innerHTML = `<div>${t}</div>` +
        (s ? `<div style="font-weight:400;font-size:15px;opacity:.82;margin-top:4px">${s}</div>` : '');
      document.body.appendChild(bar);
    },
    [text, sub || ''],
  );
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  rmSync(OUT, { recursive: true, force: true });
  mkdirSync(OUT, { recursive: true });

  // A product of our own, on a stack with no demo fixtures. The fixtures are inserted straight into
  // the table and never went through the saga, so an unscoped invariant check reports them as
  // violations — which would make the closing scene of this video a failure. Everything here is
  // therefore created by this script and nothing else is in the database.
  execFileSync('./scripts/ordering-harness.py', ['setup', '--sku', SKU, '--units', '5000'], {
    cwd: join(HERE, '..'), stdio: 'inherit',
  });

  const auth = await token();
  const browser = await chromium.launch();
  const context = await browser.newContext({
    recordVideo: { dir: OUT, size },
    viewport: size,
  });
  const page = await context.newPage();

  // 1. The 3D ops floor, live, with orders arriving while it is on screen.
  await page.goto('http://localhost:5174/');
  await wait(4000);
  await caption(page, 'FulfillOps — the ops floor, live',
    'Every parcel is an order from the running system, placed through the API as this was filmed');
  // Keep orders flowing for the length of the scene so the lanes actually fill.
  const flooding = setInterval(() => {
    api('/api/orders', {
      customerName: 'Demo Customer',
      email: 'demo@example.com',
      sku: SKU,
      quantity: 1,
    }, auth).catch(() => {});
  }, 1400);
  await wait(34000);
  clearInterval(flooding);

  // 2. One order, followed across all four services.
  const placed = await api('/api/orders', {
    customerName: 'Demo Customer', email: 'demo@example.com', sku: SKU, quantity: 1,
  }, auth);
  const traceId = execFileSync(
    'docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'fulfillops',
      '-t', '-A', '-c',
      `select split_part(traceparent,'-',2) from order_svc.outbox where order_number='${placed.orderNumber}' order by id limit 1`],
    { cwd: join(HERE, '..'), encoding: 'utf8' },
  ).trim();

  // Spans are batched by the exporter, so the trace is not in Jaeger the moment the order is
  // placed. Navigating early loads an empty page and the scene films as a white screen — which is
  // exactly what the first attempt did. Poll until the trace is actually there.
  // Wait for all four services, not just for "some" spans: order-service's own HTTP and security
  // spans arrive first and on their own satisfy a span-count check, which filmed a trace that
  // looked like it stopped at the first hop.
  for (let i = 0; i < 60; i++) {
    const services = await fetch(`http://localhost:16686/api/traces/${traceId}`).then((r) => r.json())
      .then((b) => new Set(Object.values(b.data?.[0]?.processes ?? {}).map((p) => p.serviceName)).size)
      .catch(() => 0);
    if (services >= 4) break;
    await wait(1000);
  }
  await page.goto(`http://localhost:16686/trace/${traceId}`, { waitUntil: 'networkidle' });
  await wait(13000);
  await caption(page, `Order ${placed.orderNumber}, end to end`,
    'One request, four services: reserve stock, take payment, claim a slot, confirm');
  await wait(17000);

  // 3. The metrics.
  await page.goto('http://localhost:3000/d/fulfillops-saga');
  await wait(9000);
  await caption(page, 'Order saga dashboard',
    'Live from the same stack: acceptance rate and p99, outbox backlog, consumer lag, heap');
  await wait(15000);

  // 4. The CS console, on the order just placed.
  await page.goto('http://localhost:8080/login');
  await page.getByTestId('username').fill('cs');
  await page.getByTestId('password').fill('cs-dev-password');
  await page.getByTestId('sign-in').click();
  await page.waitForURL(/orders/, { timeout: 15000 });
  await page.getByTestId('order-search').fill(placed.orderNumber);
  await wait(9000);
  await caption(page, 'Customer service console',
    'A client of order-service holding no order state of its own — refunds and shipping go through the API');
  await wait(12000);

  // 5. The seller dashboard.
  await page.goto('http://localhost:4200/');
  await page.getByTestId('username').fill(SELLER.username);
  await page.getByTestId('password').fill(SELLER.password);
  await page.getByTestId('sign-in').click();
  await page.waitForURL(/orders/, { timeout: 20000 });
  await wait(8000);
  await caption(page, 'Seller dashboard',
    'Order and inventory straight from the services, with consumer-driven contracts holding the client honest');
  await wait(13000);

  // 6. The proof: the invariant checks, from the real script.
  let proof = '';
  try {
    proof = execFileSync('./scripts/ordering-harness.py', ['check'], {
      cwd: join(HERE, '..'), encoding: 'utf8',
    });
  } catch (error) {
    proof = `${error.stdout || ''}${error.stderr || ''}`;
  }
  const html = `<!doctype html><meta charset="utf-8">
    <style>body{background:#0b0f17;color:#d7e2f2;font:15px/1.7 ui-monospace,SFMono-Regular,Menlo,monospace;margin:0;padding:34px 40px}
    h1{font:600 20px/1.3 ui-sans-serif,sans-serif;color:#fff;margin:0 0 4px}
    p{font:14px/1.5 ui-sans-serif,sans-serif;color:#93a4bd;margin:0 0 22px}
    pre{white-space:pre-wrap;margin:0}</style>
    <h1>scripts/ordering-harness.py check</h1>
    <p>Nine invariants across all four services' stores, run against the stack this video was filmed on</p>
    <pre>${proof.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]))}</pre>`;
  const proofFile = join(OUT, 'proof.html');
  writeFileSync(proofFile, html);
  await page.goto(`file://${proofFile}`);
  await wait(12000);
  await caption(page, 'And the claim itself, checked',
    'No oversell, no lost order, no double charge — including after everything above went wrong');
  await wait(6000);

  await context.close();
  await browser.close();

  // Playwright records one file per context, so there is a single webm to convert.
  const videos = readdirSync(OUT).filter((f) => f.endsWith('.webm'));
  if (videos.length === 0) throw new Error('no video was recorded');
  console.log(`recorded ${videos.length} clip(s)`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});