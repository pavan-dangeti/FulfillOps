// Data types shared by every source. Field names mirror the two real stores:
// the Angular seller dashboard (BehaviorSubject, in memory) and the Spring
// cs-console (JPA + H2). This file does not invent domain concepts.

export type SellerStatus = 'PENDING' | 'PROCESSING' | 'SHIPPED' | 'DELIVERED';
export type CsStatus = SellerStatus | 'REFUNDED';

export interface StockItem {
  id: string;
  sku: string;
  name: string;
  unitPrice: number;
  stock: number;
}

export interface SellerOrder {
  id: string;
  customer: string;
  sku: string;
  quantity: number;
  total: number;
  status: SellerStatus;
}

export interface CsOrder {
  id: string;
  orderNumber: string;
  customer: string;
  email: string;
  sku: string;
  productName: string;
  quantity: number;
  total: number;
  status: CsStatus;
  createdAt: string;
  refundedAt: string | null;
}

export interface LogEntry {
  t: number;
  kind: 'refund' | 'ship' | 'stock' | 'info';
  text: string;
  orderId?: string;
}

export interface OpsSnapshot {
  stocks: StockItem[];
  sellerOrders: SellerOrder[];
  csOrders: CsOrder[];
  log: LogEntry[];
  live: { csReachable: boolean; sellerLive: boolean };
}

// The source of truth the 3D scene and HUD read. snapshot() resolves the
// current state; subscribe(cb) fires on every mutation and returns unlisten.
export interface OpsSource {
  snapshot(): Promise<OpsSnapshot>;
  subscribe(cb: (snap: OpsSnapshot) => void): () => void;
}

export const SELLER_STATUSES: SellerStatus[] = ['PENDING', 'PROCESSING', 'SHIPPED', 'DELIVERED'];
export const CS_STATUSES: CsStatus[] = [...SELLER_STATUSES, 'REFUNDED'];

export type StoreId = 'seller' | 'cs';

// Selection keys are "store:orderNumber" — order numbers collide across the
// two stores by design (that is the divergence), so the store must be part of
// the identity. The scene-state attribute drops the prefix (plain orderNumber).
export function selectionKey(store: StoreId, orderNumber: string): string {
  return `${store}:${orderNumber}`;
}

export function parseSelection(key: string): { store: StoreId; orderNumber: string } | null {
  const i = key.indexOf(':');
  if (i < 0) return null;
  const store = key.slice(0, i);
  if (store !== 'seller' && store !== 'cs') return null;
  return { store, orderNumber: key.slice(i + 1) };
}

export interface DivergencePair {
  orderNumber: string;
  seller: SellerOrder;
  cs: CsOrder;
  // Field-by-field comparison. customer here is the CS customerName.
  mismatch: { sku: boolean; quantity: boolean; status: boolean; customer: boolean; total: boolean };
  anyMismatch: boolean;
}

export function comparePair(seller: SellerOrder, cs: CsOrder): DivergencePair['mismatch'] {
  return {
    sku: seller.sku !== cs.sku,
    quantity: seller.quantity !== cs.quantity,
    status: seller.status !== cs.status,
    customer: seller.customer !== cs.customer,
    total: Math.abs(seller.total - cs.total) > 0.001,
  };
}

export function computeDivergences(snap: OpsSnapshot): DivergencePair[] {
  const sellerByNo = new Map(snap.sellerOrders.map((o) => [o.id, o]));
  const pairs: DivergencePair[] = [];
  for (const cs of snap.csOrders) {
    const seller = sellerByNo.get(cs.orderNumber);
    if (!seller) continue;
    const mismatch = comparePair(seller, cs);
    pairs.push({
      orderNumber: cs.orderNumber,
      seller,
      cs,
      mismatch,
      anyMismatch: Object.values(mismatch).some(Boolean),
    });
  }
  return pairs;
}
