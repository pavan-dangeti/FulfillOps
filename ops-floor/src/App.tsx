import { useEffect, useMemo, useState } from 'react';
import { OpsCanvas } from './scene/OpsCanvas';
import { Hud } from './hud/Hud';
import { Fallback } from './Fallback';
import { SeedSource } from './data/seed';
import { LiveSource } from './data/live';
import { useStore } from './store';
import { rebuildScene, loop } from './scene/shared';
import type { UiFlags } from './scene/shared';

function hasWebGL(): boolean {
  try {
    const c = document.createElement('canvas');
    return !!(c.getContext('webgl2') || c.getContext('webgl'));
  } catch {
    return false;
  }
}

function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(
    () => window.matchMedia('(prefers-reduced-motion: reduce)').matches,
  );
  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const on = () => setReduced(mq.matches);
    mq.addEventListener('change', on);
    return () => mq.removeEventListener('change', on);
  }, []);
  return reduced;
}

export default function App() {
  const webgl = useMemo(hasWebGL, []);
  const reduced = usePrefersReducedMotion();
  const setReduced = useStore((s) => s.setReduced);
  const sourceMode = useStore((s) => s.sourceMode);
  const snapshot = useStore((s) => s.snapshot);
  const statusFilter = useStore((s) => s.statusFilter);
  const planes = useStore((s) => s.planes);
  const isolate = useStore((s) => s.isolate);

  const [source] = useState(() => (sourceMode === 'live' ? new LiveSource() : new SeedSource()));

  useEffect(() => setReduced(reduced), [reduced, setReduced]);

  // source → store
  useEffect(() => {
    const un = source.subscribe((s) => useStore.getState().setSnapshot(s));
    void source.snapshot().then((s) => useStore.getState().setSnapshot(s));
    return () => {
      un();
      if (source instanceof LiveSource) source.dispose();
    };
  }, [source]);

  // snapshot + ui → scene model (one rebuild per change; React never renders per frame)
  useEffect(() => {
    if (!snapshot) return;
    const ui: UiFlags = { statusFilter, planes, isolate };
    rebuildScene(snapshot, ui, useStore.getState().reduced);
    // filter-only changes don't go through setSnapshot — bump so the scene
    // components and the scene-state mirror re-render
    useStore.getState().bumpScene();
  }, [snapshot, statusFilter, planes, isolate]);

  // sim clock + timeline + ambient/transition invalidation
  useEffect(() => {
    let raf = 0;
    let last = performance.now();
    const step = (now: number) => {
      const dt = Math.min(0.1, (now - last) / 1000);
      last = now;
      const st = useStore.getState();
      if (!st.paused) {
        loop.simT += dt;
        // Exact-constructor check, not `instanceof`: LiveSource extends
        // SeedSource for its snapshot/subscribe/setSnap plumbing, so
        // `instanceof SeedSource` is also true for a LiveSource instance.
        // That let the scripted demo timeline (e.g. "refund ORD-1045 at
        // T+4s") mutate csOrders in live mode too, fighting the real
        // 2.5s poll — "no seller API is faked" only holds if the seed
        // timeline stays out of live mode entirely.
        if (source.constructor === SeedSource) source.tick(loop.simT);
        // explicit invalidate only while animations are in flight or ambient
        // motion is on — a static, unhovered scene sits at 0 GPU work
        if (!st.reduced && (loop.pending > 0 || loop.ambient > 0)) loop.invalidate();
      }
      raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [source]);

  return (
    <div className="ops-floor">
      {webgl ? <OpsCanvas /> : <Fallback />}
      <Hud />
    </div>
  );
}
