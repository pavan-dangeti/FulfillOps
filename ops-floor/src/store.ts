import { create } from 'zustand';
import type { OpsSnapshot, SellerStatus, StoreId } from './data/source';

type AnyStatus = SellerStatus | 'REFUNDED';
import { sceneState } from './scene/shared';

export type CameraPreset = 'overview' | 'seller' | 'cs';
export type SourceMode = 'seed' | 'live';

interface OpsStore {
  snapshot: OpsSnapshot | null;
  sceneVersion: number;
  selected: string | null; // "store:orderNumber" or "p5"
  hoveredArc: string | null;
  statusFilter: AnyStatus | null;
  planes: { seller: boolean; cs: boolean };
  preset: CameraPreset;
  isolate: boolean;
  paused: boolean;
  reduced: boolean;
  contextLost: boolean;
  sourceMode: SourceMode;
  // Flips true after the Canvas has actually committed its first rendered
  // frame. Html-portalled scene content (lane labels, SKU labels) lives
  // inside the R3F tree and its DOM nodes can lag well behind the rest of
  // the page — the WebGL context setup and the procedural-environment PMREM
  // pass in OpsCanvas are synchronous GPU work that can take a long time
  // under software rendering (headless/CI runners without a GPU driver).
  // Tests and any other DOM-facing code should wait on this instead of
  // guessing a fixed delay before asserting on canvas-internal content.
  sceneReady: boolean;

  setSnapshot: (s: OpsSnapshot) => void;
  bumpScene: () => void;
  select: (key: string | null) => void;
  hoverArc: (key: string | null) => void;
  toggleStatusFilter: (s: AnyStatus) => void;
  togglePlane: (p: StoreId) => void;
  setPreset: (p: CameraPreset) => void;
  toggleIsolate: () => void;
  togglePaused: () => void;
  setReduced: (b: boolean) => void;
  setContextLost: (b: boolean) => void;
  setSceneReady: (b: boolean) => void;
}

export const useStore = create<OpsStore>((set) => ({
  snapshot: null,
  sceneVersion: 0,
  selected: null,
  hoveredArc: null,
  statusFilter: null,
  planes: { seller: true, cs: true },
  preset: 'overview',
  isolate: false,
  paused: false,
  reduced: false,
  contextLost: false,
  sourceMode: (import.meta.env.VITE_SOURCE === 'live' ? 'live' : 'seed') as SourceMode,
  sceneReady: false,

  setSnapshot: (snapshot) =>
    set((st) => ({ snapshot, sceneVersion: st.sceneVersion + 1 })),
  bumpScene: () => set((st) => ({ sceneVersion: st.sceneVersion + 1 })),
  select: (selected) => set({ selected }),
  hoverArc: (hoveredArc) => set({ hoveredArc }),
  toggleStatusFilter: (statusFilter) =>
    set((st) => ({ statusFilter: st.statusFilter === statusFilter ? null : statusFilter })),
  togglePlane: (p) =>
    set((st) => ({ planes: { ...st.planes, [p]: !st.planes[p] } })),
  setPreset: (preset) => set({ preset }),
  toggleIsolate: () => set((st) => ({ isolate: !st.isolate })),
  togglePaused: () => set((st) => ({ paused: !st.paused })),
  setReduced: (reduced) => set({ reduced }),
  setContextLost: (contextLost) => set({ contextLost }),
  setSceneReady: (sceneReady) => set({ sceneReady }),
}));

// Helper the App uses to rebuild the scene model from current store state.
export function currentUi() {
  const st = useStore.getState();
  return {
    statusFilter: st.statusFilter,
    planes: st.planes,
    isolate: st.isolate,
  };
}

export { sceneState };
