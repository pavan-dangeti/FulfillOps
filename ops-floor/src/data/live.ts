// LiveSource — polls the new JSON endpoints on cs-console (GET /api/ops/orders,
// GET /api/ops/health). The Angular seller dashboard has no server and no wire
// protocol, so in live mode the seller plane stays seed-backed; the HUD says so
// explicitly. No seller API is faked here.
import { SeedSource } from './seed';
import type { CsOrder, CsStatus, LogEntry } from './source';

const CS_BASE = import.meta.env.VITE_CS_URL ?? 'http://localhost:8080';
const POLL_MS = 2500;

interface OrderViewDto {
  orderNumber: string;
  customerName: string;
  email: string;
  sku: string;
  productName: string;
  quantity: number;
  total: number;
  status: CsStatus;
  createdAt: string | null;
  refundedAt: string | null;
}

export class LiveSource extends SeedSource {
  private timer: ReturnType<typeof setInterval> | null = null;
  private lastErrorLogged = 0;
  private start = performance.now();

  constructor() {
    super();
    this.timer = setInterval(() => void this.poll(), POLL_MS);
    void this.poll();
  }

  // monotonic seconds since source start — reads as T+xx.xs like seed events
  private t(): number {
    return (performance.now() - this.start) / 1000;
  }

  dispose(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  private async poll(): Promise<void> {
    try {
      const res = await fetch(`${CS_BASE}/api/ops/orders`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const rows = (await res.json()) as OrderViewDto[];
      const csOrders: CsOrder[] = rows.map((r) => ({
        id: r.orderNumber,
        orderNumber: r.orderNumber,
        customer: r.customerName,
        email: r.email,
        sku: r.sku ?? '',
        productName: r.productName ?? '',
        quantity: r.quantity,
        total: r.total,
        status: r.status,
        createdAt: r.createdAt ?? '',
        refundedAt: r.refundedAt ?? null,
      }));
      this.replaceCs(csOrders, true);
    } catch (err) {
      const now = Date.now();
      if (now - this.lastErrorLogged > 10_000) {
        this.lastErrorLogged = now;
        const text = `cs-console unreachable (${err instanceof Error ? err.message : String(err)}) — retrying every ${POLL_MS / 1000}s`;
        this.pushLog({ t: this.t(), kind: 'info' as const, text });
      }
      this.replaceCs(null, false);
    }
  }

  private replaceCs(csOrders: CsOrder[] | null, reachable: boolean): void {
    const snap = this.snap;
    if (reachable && csOrders) {
      const same =
        snap.csOrders.length === csOrders.length &&
        snap.csOrders.every((a, i) => {
          const b = csOrders[i];
          return a.orderNumber === b.orderNumber && a.status === b.status && a.quantity === b.quantity;
        });
      if (same) return;
      this.setSnap({
        ...snap,
        csOrders,
        live: { ...snap.live, csReachable: true },
        log: [...snap.log, { t: this.t(), kind: 'info' as const, text: `cs-console poll: ${csOrders.length} orders` }].slice(-200),
      });
    } else if (!reachable && snap.live.csReachable) {
      this.setSnap({
        ...snap,
        live: { ...snap.live, csReachable: false },
      });
    }
  }

  private pushLog(entry: LogEntry): void {
    this.setSnap({ ...this.snap, log: [...this.snap.log, entry].slice(-200) });
  }
}
