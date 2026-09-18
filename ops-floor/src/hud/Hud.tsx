import { useEffect, useMemo, useState } from 'react';
import { useStore } from '../store';
import { sceneState, countVisible, loop } from '../scene/shared';
import { statsBridge } from '../scene/OpsCanvas';
import {
  CS_STATUSES,
  computeDivergences,
  parseSelection,
  type CsOrder,
  type DivergencePair,
  type LogEntry,
  type SellerOrder,
} from '../data/source';

const ROW_H = 24;
const VIEW_H = 120;

export function Hud() {
  const mode = useStore((s) => s.sourceMode);
  const paused = useStore((s) => s.paused);
  const preset = useStore((s) => s.preset);
  const selected = useStore((s) => s.selected);
  const contextLost = useStore((s) => s.contextLost);
  const sceneReady = useStore((s) => s.sceneReady);
  useStore((s) => s.sceneVersion);
  const data = sceneState.data;

  useKeys();

  const selectedOrder = selected ? selected.slice(selected.indexOf(':') + 1) : '';

  return (
    <div className="hud">
      <TopBar />
      <div className="hud-mid">
        <LeftRail />
        <Inspector />
      </div>
      <EventLog />
      <div className="hud-keys">
        F focus · Esc clear · 1/2/3 presets · D isolate · Space pause
      </div>
      {data && (
        <div
          data-testid="scene-state"
          data-selected-order={selectedOrder}
          data-visible-parcels={countVisible(data)}
          data-divergences={data.divergenceCount}
          data-camera-preset={preset}
          data-paused={paused}
          data-source={mode}
          data-scene-ready={sceneReady}
          hidden
        />
      )}
      {contextLost && <div className="scene-state-note">WebGL context lost — waiting for restore…</div>}
      <HoverDiff />
      <StatsOverlay />
    </div>
  );
}

function Clock() {
  const [t, setT] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setT(loop.simT), 100);
    return () => clearInterval(id);
  }, []);
  const m = Math.floor(t / 60);
  const s = Math.floor(t % 60);
  return (
    <span className="stat">
      clock <b className="mono">{`T+${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`}</b>
    </span>
  );
}

function TopBar() {
  const mode = useStore((s) => s.sourceMode);
  const snapshot = useStore((s) => s.snapshot);
  useStore((s) => s.sceneVersion);
  const data = sceneState.data;

  return (
    <div className="hud-top">
      <span className="brand">FulfillOps · Ops Floor</span>
      <span className="stat">
        source <b>{mode}</b>
      </span>
      <span className="stat">
        spring{' '}
        <b>{mode === 'live' ? (snapshot?.live.csReachable ? 'reachable' : 'unreachable') : 'simulated'}</b>
      </span>
      <Clock />
      <span className="stat">
        parcels <b>{data?.parcels.length ?? 0}</b>
      </span>
      <span className="stat divergence">
        divergences <b>{data?.divergenceCount ?? 0}</b>
      </span>
      {mode === 'live' && <span className="note">seller: in-memory seed — no wire protocol</span>}
    </div>
  );
}

function LeftRail() {
  const snapshot = useStore((s) => s.snapshot);
  const statusFilter = useStore((s) => s.statusFilter);
  const planes = useStore((s) => s.planes);
  const isolate = useStore((s) => s.isolate);

  const counts = useMemo(() => {
    const c = new Map<string, number>();
    for (const o of snapshot?.sellerOrders ?? []) c.set(o.status, (c.get(o.status) ?? 0) + 1);
    for (const o of snapshot?.csOrders ?? []) c.set(o.status, (c.get(o.status) ?? 0) + 1);
    return c;
  }, [snapshot]);

  return (
    <div className="hud-rail panel">
      <h3>status filter</h3>
      {CS_STATUSES.map((s) => (
        <button key={s} className={statusFilter === s ? 'on' : ''} onClick={() => useStore.getState().toggleStatusFilter(s)}>
          {s}
          <span className="count">{counts.get(s) ?? 0}</span>
        </button>
      ))}
      <h3>planes</h3>
      <button className={planes.seller ? 'on' : ''} onClick={() => useStore.getState().togglePlane('seller')}>
        seller
      </button>
      <button className={planes.cs ? 'on' : ''} onClick={() => useStore.getState().togglePlane('cs')}>
        cs
      </button>
      <h3>view</h3>
      <button className={isolate ? 'on' : ''} onClick={() => useStore.getState().toggleIsolate()}>
        isolate divergences
      </button>
    </div>
  );
}

function DiffTable({ pair }: { pair: DivergencePair }) {
  const rows: { field: string; a: string; b: string; bad: boolean }[] = [
    { field: 'sku', a: pair.seller.sku, b: pair.cs.sku, bad: pair.mismatch.sku },
    { field: 'quantity', a: String(pair.seller.quantity), b: String(pair.cs.quantity), bad: pair.mismatch.quantity },
    { field: 'status', a: pair.seller.status, b: pair.cs.status, bad: pair.mismatch.status },
    { field: 'customer', a: pair.seller.customer, b: pair.cs.customer, bad: pair.mismatch.customer },
    { field: 'total', a: pair.seller.total.toFixed(2), b: pair.cs.total.toFixed(2), bad: pair.mismatch.total },
  ];
  return (
    <table>
      <thead>
        <tr>
          <th>field</th>
          <th>seller · angular</th>
          <th>cs · spring</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.field} className={r.bad ? 'mismatch' : ''}>
            <td className="field">{r.field}</td>
            <td>{r.a}</td>
            <td>{r.b}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Inspector() {
  const selected = useStore((s) => s.selected);
  const snapshot = useStore((s) => s.snapshot);

  const parsed = selected ? parseSelection(selected) : null;
  let seller: SellerOrder | undefined;
  let cs: CsOrder | undefined;
  if (parsed && snapshot) {
    if (parsed.store === 'seller') seller = snapshot.sellerOrders.find((o) => o.id === parsed.orderNumber);
    else cs = snapshot.csOrders.find((o) => o.orderNumber === parsed.orderNumber);
    // the counterpart for the diff — the order number may exist in both stores
    if (!seller) seller = snapshot.sellerOrders.find((o) => o.id === parsed.orderNumber);
    if (!cs) cs = snapshot.csOrders.find((o) => o.orderNumber === parsed.orderNumber);
  }
  const pair = useMemo(() => {
    if (!seller || !cs || !snapshot) return null;
    return computeDivergences(snapshot).find((d) => d.orderNumber === cs!.orderNumber) ?? null;
  }, [seller, cs, snapshot]);

  return (
    <div className="hud-inspector panel" data-testid="inspector">
      <h3>inspector</h3>
      {!parsed && <div style={{ color: 'var(--muted)', fontSize: 12 }}>Click a parcel or a log row. Esc clears.</div>}
      {seller && (
        <div className="rec" data-testid="record">
          <div className="row"><dt>order</dt><dd>{seller.id}</dd></div>
          <div className="row"><dt>store</dt><dd>seller · angular</dd></div>
          <div className="row"><dt>customer</dt><dd>{seller.customer}</dd></div>
          <div className="row"><dt>sku</dt><dd>{seller.sku}</dd></div>
          <div className="row"><dt>quantity</dt><dd>{seller.quantity}</dd></div>
          <div className="row"><dt>total</dt><dd>{seller.total.toFixed(2)}</dd></div>
          <div className="row"><dt>status</dt><dd>{seller.status}</dd></div>
        </div>
      )}
      {cs && (
        <div className="rec" data-testid="record">
          <div className="row"><dt>order</dt><dd>{cs.orderNumber}</dd></div>
          <div className="row"><dt>store</dt><dd>cs · spring</dd></div>
          <div className="row"><dt>customer</dt><dd>{cs.customer}</dd></div>
          <div className="row"><dt>email</dt><dd>{cs.email}</dd></div>
          <div className="row"><dt>sku</dt><dd>{cs.sku}</dd></div>
          <div className="row"><dt>product</dt><dd>{cs.productName}</dd></div>
          <div className="row"><dt>quantity</dt><dd>{cs.quantity}</dd></div>
          <div className="row"><dt>total</dt><dd>{cs.total.toFixed(2)}</dd></div>
          <div className="row"><dt>status</dt><dd>{cs.status}</dd></div>
          <div className="row"><dt>created</dt><dd>{cs.createdAt}</dd></div>
          {cs.refundedAt && <div className="row"><dt>refunded</dt><dd>{cs.refundedAt}</dd></div>}
        </div>
      )}
      {pair && pair.anyMismatch && (
        <div className="diff" data-testid="diff">
          <h3>divergence — {pair.orderNumber}</h3>
          <DiffTable pair={pair} />
        </div>
      )}
    </div>
  );
}

function EventLog() {
  const log = useStore((s) => s.snapshot?.log); // stable ref — never allocates
  const selected = useStore((s) => s.selected);
  const [scrollTop, setScrollTop] = useState(0);

  // newest first; virtualized window over a capped (200) list
  const entries: LogEntry[] = useMemo(() => [...(log ?? [])].reverse(), [log]);
  const first = Math.max(0, Math.floor(scrollTop / ROW_H) - 2);
  const last = Math.min(entries.length, Math.ceil((scrollTop + VIEW_H) / ROW_H) + 2);

  return (
    <div className="hud-log">
      <h3>event log</h3>
      <div className="log-scroll" onScroll={(e) => setScrollTop(e.currentTarget.scrollTop)}>
        <div className="log-spacer" style={{ height: entries.length * ROW_H }} />
        {entries.slice(first, last).map((entry, i) => {
          const idx = first + i;
          const sel = entry.orderId !== undefined && entry.orderId === selected;
          return (
            <div
              key={`${entry.t}-${idx}`}
              className={`log-row${sel ? ' selected' : ''}`}
              style={{ top: idx * ROW_H }}
              data-testid="log-row"
              data-order={entry.orderId ?? ''}
              onClick={() => entry.orderId && useStore.getState().select(entry.orderId)}
            >
              <span className="t">T+{entry.t < 10000 ? entry.t.toFixed(1) : String(Math.floor(entry.t))}s</span>
              <span className={`kind ${entry.kind}`}>{entry.kind}</span>
              <span>{entry.text}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function HoverDiff() {
  const hovered = useStore((s) => s.hoveredArc);
  const snapshot = useStore((s) => s.snapshot);
  useStore((s) => s.sceneVersion);
  const arc = sceneState.data?.arcs.find((a) => a.key === hovered);
  const pair = useMemo(() => {
    if (!arc || !snapshot) return null;
    return computeDivergences(snapshot).find((d) => d.orderNumber === arc.orderNumber) ?? null;
  }, [arc, snapshot]);
  if (!pair || !pair.anyMismatch) return null;
  return (
    <div className="hover-diff">
      <h4>ORD divergence — {pair.orderNumber}</h4>
      <DiffTable pair={pair} />
    </div>
  );
}

function StatsOverlay() {
  const [show] = useState(() => new URLSearchParams(window.location.search).has('stats'));
  const [s, setS] = useState({ ...statsBridge });
  useEffect(() => {
    if (!show) return;
    const id = setInterval(() => setS({ ...statsBridge }), 500);
    return () => clearInterval(id);
  }, [show]);
  if (!show) return null;
  return (
    <div className="stats-overlay" data-testid="stats-overlay">
      scene calls {s.sceneCalls} · total {s.calls} · frames {s.frame} · tris {s.triangles} · geo {s.geometries} · tex {s.textures}
    </div>
  );
}

function useKeys() {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const st = useStore.getState();
      switch (e.key) {
        case 'f':
        case 'F': {
          if (!st.selected) return;
          const p = sceneState.data?.parcels.find((x) => x.key === st.selected);
          if (p && loop.controls) {
            void loop.controls.setLookAt(p.x + 3, p.y + 3.5, p.z + 6, p.x, p.y + 0.4, p.z, !st.reduced);
          }
          break;
        }
        case 'Escape':
          st.select(null);
          break;
        case '1':
          st.setPreset('overview');
          break;
        case '2':
          st.setPreset('seller');
          break;
        case '3':
          st.setPreset('cs');
          break;
        case 'd':
        case 'D':
          st.toggleIsolate();
          break;
        case ' ':
          e.preventDefault();
          st.togglePaused();
          break;
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
}
