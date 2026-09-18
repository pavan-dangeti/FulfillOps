// SeedSource — hardcodes the exact seed arrays from the sibling apps so the
// 3D view and the other two frontends agree on first paint.
//
//   Stocks + seller orders: InventoryService / OrdersService (seller-dashboard)
//   CS orders:              DataSeeder (cs-console)
//
// Copied literally — same IDs, names, totals, statuses. Do not "fix" the
// overlap: ORD-1039..1042 existing in both stores with different customers,
// quantities and statuses is the deliberate store isolation TRADEOFFS.md
// documents, and rendering that split is this app's thesis.
import type { CsOrder, LogEntry, OpsSnapshot, OpsSource, SellerOrder, StockItem } from './source';

const STOCKS: StockItem[] = [
  { id: 'p1', sku: 'SKU-1001', name: 'Wireless Mouse', unitPrice: 24.99, stock: 142 },
  { id: 'p2', sku: 'SKU-1002', name: 'Mechanical Keyboard', unitPrice: 89.5, stock: 56 },
  { id: 'p3', sku: 'SKU-1003', name: 'USB-C Hub 7-in-1', unitPrice: 45.0, stock: 8 },
  { id: 'p4', sku: 'SKU-1004', name: '4K Webcam', unitPrice: 129.99, stock: 23 },
  { id: 'p5', sku: 'SKU-1005', name: 'Standing Desk Mat', unitPrice: 39.75, stock: 0 },
];

const SELLER_ORDERS: SellerOrder[] = [
  { id: 'ORD-1042', customer: 'Ava Chen', sku: 'SKU-1002', quantity: 1, total: 89.5, status: 'PROCESSING' },
  { id: 'ORD-1041', customer: 'Marcus Webb', sku: 'SKU-1001', quantity: 3, total: 74.97, status: 'SHIPPED' },
  { id: 'ORD-1040', customer: 'Priya Nair', sku: 'SKU-1003', quantity: 2, total: 90.0, status: 'PENDING' },
  { id: 'ORD-1039', customer: 'Diego Fernandez', sku: 'SKU-1004', quantity: 1, total: 129.99, status: 'DELIVERED' },
  { id: 'ORD-1038', customer: 'Yuki Tanaka', sku: 'SKU-1005', quantity: 2, total: 79.5, status: 'SHIPPED' },
];

const CS_ORDERS: CsOrder[] = [
  { id: 'ORD-1050', orderNumber: 'ORD-1050', customer: 'Ava Chen', email: 'ava.chen@example.com', sku: 'SKU-1002', productName: 'Mechanical Keyboard', quantity: 1, total: 89.5, status: 'PROCESSING', createdAt: 'T-2h', refundedAt: null },
  { id: 'ORD-1049', orderNumber: 'ORD-1049', customer: 'Marcus Webb', email: 'marcus.webb@example.com', sku: 'SKU-1001', productName: 'Wireless Mouse', quantity: 3, total: 74.97, status: 'SHIPPED', createdAt: 'T-5h', refundedAt: null },
  { id: 'ORD-1048', orderNumber: 'ORD-1048', customer: 'Priya Nair', email: 'priya.nair@example.com', sku: 'SKU-1003', productName: 'USB-C Hub 7-in-1', quantity: 2, total: 90.0, status: 'PENDING', createdAt: 'T-8h', refundedAt: null },
  { id: 'ORD-1047', orderNumber: 'ORD-1047', customer: 'Diego Fernandez', email: 'diego.fernandez@example.com', sku: 'SKU-1004', productName: '4K Webcam', quantity: 1, total: 129.99, status: 'DELIVERED', createdAt: 'T-1d', refundedAt: null },
  { id: 'ORD-1046', orderNumber: 'ORD-1046', customer: 'Yuki Tanaka', email: 'yuki.tanaka@example.com', sku: 'SKU-1005', productName: 'Standing Desk Mat', quantity: 2, total: 79.5, status: 'SHIPPED', createdAt: 'T-1d3h', refundedAt: null },
  { id: 'ORD-1045', orderNumber: 'ORD-1045', customer: 'Hannah Schmidt', email: 'hannah.s@example.com', sku: 'SKU-1002', productName: 'Mechanical Keyboard', quantity: 1, total: 89.5, status: 'PROCESSING', createdAt: 'T-2d', refundedAt: null },
  { id: 'ORD-1044', orderNumber: 'ORD-1044', customer: 'Omar Haddad', email: 'omar.h@example.com', sku: 'SKU-1003', productName: 'USB-C Hub 7-in-1', quantity: 1, total: 45.0, status: 'PENDING', createdAt: 'T-2d6h', refundedAt: null },
  { id: 'ORD-1043', orderNumber: 'ORD-1043', customer: 'Lena Kovacs', email: 'lena.k@example.com', sku: 'SKU-1001', productName: 'Wireless Mouse', quantity: 5, total: 124.95, status: 'DELIVERED', createdAt: 'T-3d', refundedAt: null },
  { id: 'ORD-1042', orderNumber: 'ORD-1042', customer: 'Tomás Rivera', email: 'tomas.r@example.com', sku: 'SKU-1004', productName: '4K Webcam', quantity: 2, total: 259.98, status: 'SHIPPED', createdAt: 'T-3d2h', refundedAt: null },
  { id: 'ORD-1041', orderNumber: 'ORD-1041', customer: 'Sofia Greco', email: 'sofia.g@example.com', sku: 'SKU-1005', productName: 'Standing Desk Mat', quantity: 1, total: 39.75, status: 'PENDING', createdAt: 'T-4d', refundedAt: null },
  { id: 'ORD-1040', orderNumber: 'ORD-1040', customer: 'Wei Zhang', email: 'wei.z@example.com', sku: 'SKU-1002', productName: 'Mechanical Keyboard', quantity: 2, total: 179.0, status: 'DELIVERED', createdAt: 'T-5d', refundedAt: null },
  { id: 'ORD-1039', orderNumber: 'ORD-1039', customer: 'Emily Stone', email: 'emily.stone@example.com', sku: 'SKU-1001', productName: 'Wireless Mouse', quantity: 1, total: 24.99, status: 'REFUNDED', createdAt: 'T-6d', refundedAt: 'T-5d' },
];

const BOOT_LOG: LogEntry[] = [
  { t: 0, kind: 'info', text: 'seed source loaded — 5 stocks, 5 seller orders, 12 cs orders (in-memory, no wire protocol)' },
  { t: 0, kind: 'info', text: '4 order numbers disagree between the two stores — select ORD-1042 to inspect', orderId: 'cs:ORD-1042' },
];

// Scripted event timeline so the scene has motion standalone. Times are sim
// seconds; each event mutates the snapshot once and logs itself.
interface TimelineEvent {
  t: number;
  apply: (snap: OpsSnapshot) => LogEntry;
}

const TIMELINE: TimelineEvent[] = [
  {
    t: 4,
    apply: (snap) => {
      const o = snap.csOrders.find((c) => c.orderNumber === 'ORD-1045');
      if (o) {
        o.status = 'REFUNDED';
        o.refundedAt = `T+4s`;
      }
      // Refund never touches inventory — OrderService.refund() in cs-console
      // mutates only the order. SKU-1002's tower must not change.
      return { t: 4, kind: 'refund', text: 'refund ORD-1045 → REFUNDED (cs) — inventory untouched', orderId: 'cs:ORD-1045' };
    },
  },
  {
    t: 10,
    apply: (snap) => {
      const o = snap.csOrders.find((c) => c.orderNumber === 'ORD-1048');
      if (o && o.status !== 'REFUNDED') o.status = 'SHIPPED';
      return { t: 10, kind: 'ship', text: 'ship ORD-1048 → SHIPPED (cs)', orderId: 'cs:ORD-1048' };
    },
  },
  {
    t: 16,
    apply: (snap) => {
      const s = snap.stocks.find((p) => p.sku === 'SKU-1005');
      if (s) s.stock = 40;
      return { t: 16, kind: 'stock', text: 'stock SKU-1005 0 → 40 (seller)', orderId: 'p5' };
    },
  },
];

export class SeedSource implements OpsSource {
  protected snap: OpsSnapshot;
  protected listeners = new Set<(snap: OpsSnapshot) => void>();
  private applied = 0;

  constructor() {
    this.snap = {
      // deep-ish copies so mutations never leak back into the literal seeds
      stocks: STOCKS.map((s) => ({ ...s })),
      sellerOrders: SELLER_ORDERS.map((o) => ({ ...o })),
      csOrders: CS_ORDERS.map((o) => ({ ...o })),
      log: [...BOOT_LOG],
      live: { csReachable: false, sellerLive: false },
    };
  }

  async snapshot(): Promise<OpsSnapshot> {
    return this.snap;
  }

  subscribe(cb: (snap: OpsSnapshot) => void): () => void {
    this.listeners.add(cb);
    return () => this.listeners.delete(cb);
  }

  protected setSnap(snap: OpsSnapshot): void {
    this.snap = snap;
    for (const l of this.listeners) l(this.snap);
  }

  // Advance the sim clock. Called by the App loop; paused when frozen.
  tick(simT: number): void {
    while (this.applied < TIMELINE.length && TIMELINE[this.applied].t <= simT) {
      const entry = TIMELINE[this.applied].apply(this.snap);
      this.applied++;
      this.setSnap({ ...this.snap, log: [...this.snap.log, entry].slice(-200) });
    }
  }
}
