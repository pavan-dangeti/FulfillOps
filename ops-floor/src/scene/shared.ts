import * as THREE from 'three';
import type { OpsSnapshot, StoreId, SellerStatus } from '../data/source';
import { computeDivergences } from '../data/source';

// ---------- palette (from the shared design tokens; constructed in sRGB so
// the dark UI palette does not wash out under ACES tone mapping) ----------

export const PAL = {
  bg: '#0f1115',
  panel: '#171a20',
  line: '#262b33',
  fg: '#e8eaed',
  muted: '#8b93a1',
  accent: '#5b8cff',
  danger: '#ff6b6b',
  ok: '#3ecf8e',
  warn: '#f0b429',
  info: '#4cc2ff',
} as const;

export function tokenColor(hex: string, target?: THREE.Color): THREE.Color {
  const c = target ?? new THREE.Color();
  c.setStyle(hex, THREE.SRGBColorSpace);
  return c;
}

// ---------- layout ----------

export const FLOOR = { x0: -21, x1: 21, z0: -9, z1: 13 };
export const SELLER_SPAN = { x0: -18, x1: -8 };
export const CS_SPAN = { x0: 8, x1: 18 };
export const SKU_COLUMN = { xs: [-4, -2, 0, 2, 4], z: 0 };

export const LANES: { status: SellerStatus | 'REFUNDED'; z: number }[] = [
  { status: 'PENDING', z: -6 },
  { status: 'PROCESSING', z: -2 },
  { status: 'SHIPPED', z: 2 },
  { status: 'DELIVERED', z: 6 },
  { status: 'REFUNDED', z: 10 },
];

export const LANE_Z = new Map<string, number>(LANES.map((l) => [l.status as string, l.z]));
export const SELLER_LANE_COUNT = 4; // physically no REFUNDED lane on the seller side
export const CS_LANE_COUNT = 5;

export const TOWER_SHELL_H = 6.6;

// Shared log-ish scale so stock 0 and stock 142 both read.
export function stockHeight(stock: number): number {
  const maxL = Math.log(1 + 142);
  return 0.15 + (TOWER_SHELL_H - 0.9) * (Math.log(1 + Math.max(0, stock)) / maxL);
}

export function towerStatusColorIndex(stock: number): number {
  if (stock === 0) return 2; // danger
  if (stock <= 10) return 1; // warn
  return 0; // ok
}

export function parcelScale(quantity: number): number {
  return Math.min(1.35, 0.82 + 0.11 * Math.max(1, quantity));
}

export function totalBucket(total: number): number {
  if (total < 50) return 0;
  if (total < 100) return 1;
  if (total < 200) return 2;
  return 3;
}

export const BUCKET_EMISSIVE = [0.25, 1.0, 2.0, 3.6];

// ---------- ui flags the scene derives from ----------

export interface UiFlags {
  statusFilter: SellerStatus | 'REFUNDED' | null;
  planes: { seller: boolean; cs: boolean };
  isolate: boolean;
}

export function isDimmed(status: string, store: StoreId, divergent: boolean, ui: UiFlags): boolean {
  if (!ui.planes[store]) return true;
  if (ui.statusFilter !== null && status !== ui.statusFilter) return true;
  if (ui.isolate && !divergent) return true;
  return false;
}

// ---------- derived scene model ----------

export interface SceneParcel {
  key: string;
  orderNumber: string;
  store: StoreId;
  status: string;
  quantity: number;
  total: number;
  x: number;
  y: number;
  z: number;
  scale: number;
  colorIndex: number; // 0 accent (seller), 1 info (cs), 2 danger (refunded)
  bucket: number;
  dimmed: boolean;
  divergent: boolean;
}

export interface SceneTower {
  sku: string;
  name: string;
  stock: number;
  height: number;
  colorIndex: number;
  breathe: boolean;
}

export interface SceneArc {
  key: string;
  orderNumber: string;
  ax: number;
  ay: number;
  az: number;
  bx: number;
  by: number;
  bz: number;
  divergent: boolean;
}

export interface SceneData {
  parcels: SceneParcel[];
  towers: SceneTower[];
  arcs: SceneArc[];
  divergenceCount: number;
}

export function laneSlotX(store: StoreId, idx: number, count: number): number {
  const span = store === 'seller' ? SELLER_SPAN : CS_SPAN;
  return span.x0 + ((idx + 0.5) * (span.x1 - span.x0)) / Math.max(1, count);
}

export function buildSceneData(snap: OpsSnapshot, ui: UiFlags): SceneData {
  const divergences = computeDivergences(snap);
  const divergedByNo = new Set(divergences.filter((d) => d.anyMismatch).map((d) => d.orderNumber));

  const parcels: SceneParcel[] = [];
  // keyed by "store:id" — order numbers collide across stores by design, so a
  // bare id key would let the CS store overwrite the seller's parcel position
  const posByNo = new Map<string, SceneParcel>();

  const addStore = (store: StoreId, orders: { id: string; customer: string; sku: string; quantity: number; total: number; status: string }[]) => {
    const byLane = new Map<string, typeof orders>();
    for (const o of orders) {
      const list = byLane.get(o.status) ?? [];
      list.push(o);
      byLane.set(o.status, list);
    }
    for (const [status, list] of byLane) {
      list.sort((a, b) => a.id.localeCompare(b.id));
      const z = LANE_Z.get(status) ?? 0;
      list.forEach((o, i) => {
        const divergent = divergedByNo.has(o.id);
        const x = laneSlotX(store, i, list.length);
        const s = parcelScale(o.quantity);
        const parcel: SceneParcel = {
          key: `${store}:${o.id}`,
          orderNumber: o.id,
          store,
          status,
          quantity: o.quantity,
          total: o.total,
          x,
          y: (0.55 * s) / 2,
          z,
          scale: s,
          colorIndex: status === 'REFUNDED' ? 2 : store === 'seller' ? 0 : 1,
          bucket: totalBucket(o.total),
          dimmed: isDimmed(status, store, divergent, ui),
          divergent,
        };
        parcels.push(parcel);
        posByNo.set(`${store}:${o.id}`, parcel);
      });
    }
  };

  addStore('seller', snap.sellerOrders);
  addStore('cs', snap.csOrders);

  const towers: SceneTower[] = snap.stocks.map((s) => ({
    sku: s.sku,
    name: s.name,
    stock: s.stock,
    height: stockHeight(s.stock),
    colorIndex: towerStatusColorIndex(s.stock),
    breathe: s.stock === 0,
  }));

  const arcs: SceneArc[] = divergences.map((d) => {
    const seller = posByNo.get(`seller:${d.seller.id}`);
    const cs = posByNo.get(`cs:${d.cs.orderNumber}`);
    return {
      key: `arc:${d.orderNumber}`,
      orderNumber: d.orderNumber,
      ax: seller?.x ?? 0,
      ay: seller?.y ?? 0,
      az: seller?.z ?? 0,
      bx: cs?.x ?? 0,
      by: cs?.y ?? 0,
      bz: cs?.z ?? 0,
      divergent: d.anyMismatch,
    };
  });

  return {
    parcels,
    towers,
    arcs,
    divergenceCount: divergences.filter((d) => d.anyMismatch).length,
  };
}

export function countVisible(data: SceneData): number {
  return data.parcels.reduce((n, p) => (p.dimmed ? n : n + 1), 0);
}

// Compute the scene model for a snapshot/ui state, create lane and tower
// transitions against the previous model (jump-cut under reduced motion) and
// store it in sceneState. Returns the fresh data.
export function rebuildScene(snap: OpsSnapshot, ui: UiFlags, reduced: boolean): SceneData {
  const prev = sceneState.data;
  const data = buildSceneData(snap, ui);
  if (prev) {
    const prevByKey = new Map(prev.parcels.map((p) => [p.key, p]));
    for (const p of data.parcels) {
      const pp = prevByKey.get(p.key);
      if (!pp) continue;
      if (Math.abs(pp.x - p.x) > 0.01 || Math.abs(pp.z - p.z) > 0.01 || Math.abs(pp.y - p.y) > 0.01) {
        if (!reduced) startLaneTransition(p.key, [pp.x, pp.y, pp.z], [p.x, p.y, p.z]);
        // reduced motion: jump-cut — target position already set
      }
    }
    for (const t of data.towers) {
      const pt = prev.towers.find((x) => x.sku === t.sku);
      if (pt && Math.abs(pt.height - t.height) > 0.01 && !reduced) {
        startTowerTransition(t.sku, pt.height, t.height);
      }
    }
  }
  sceneState.data = data;
  loop.ambient = data.arcs.some((a) => a.divergent) || data.towers.some((t) => t.breathe) ? 1 : 0;
  return data;
}

// ---------- frame-loop shared state (plain refs — React never renders on this) ----------

export const loop = {
  invalidate: () => {},
  simT: 0,
  pending: 0, // active transition count; App rAF invalidates while > 0
  ambient: 0, // 1 while divergence dashes / stockout breathe should animate
  controls: null as null | {
    setLookAt: (px: number, py: number, pz: number, tx: number, ty: number, tz: number, enableTransition: boolean) => void | Promise<void>;
  },
};

// Scene model, computed once per snapshot/ui change by rebuildScene and read
// by the frame loop and HUD. React re-renders only when sceneVersion bumps.
export const sceneState = {
  data: null as SceneData | null,
};

// ---------- transitions (precomputed outside the frame loop) ----------

export interface LaneTransition {
  key: string;
  points: Float32Array; // precomputed Catmull-Rom spaced points, xyz triplets
  n: number;
  start: number;
  dur: number;
}

export const laneTransitions = new Map<string, LaneTransition>(); // by parcel key

export function startLaneTransition(key: string, from: [number, number, number], to: [number, number, number], dur = 1.1): void {
  const curve = new THREE.CatmullRomCurve3([
    new THREE.Vector3(...from),
    new THREE.Vector3(from[0] + (to[0] - from[0]) * 0.35, from[1] + 1.5, from[2] + (to[2] - from[2]) * 0.35),
    new THREE.Vector3(from[0] + (to[0] - from[0]) * 0.65, to[1] + 2.2, from[2] + (to[2] - from[2]) * 0.65),
    new THREE.Vector3(...to),
  ]);
  const pts = curve.getSpacedPoints(56); // arc-length-correct, constant speed
  const flat = new Float32Array(pts.length * 3);
  pts.forEach((p, i) => {
    flat[i * 3] = p.x;
    flat[i * 3 + 1] = p.y;
    flat[i * 3 + 2] = p.z;
  });
  laneTransitions.set(key, { key, points: flat, n: pts.length, start: loop.simT, dur });
  loop.pending++;
}

export interface TowerTransition {
  from: number;
  to: number;
  start: number;
  dur: number;
}

export const towerTransitions = new Map<string, TowerTransition>(); // by sku

export function startTowerTransition(sku: string, from: number, to: number, dur = 1.2): void {
  towerTransitions.set(sku, { from, to, start: loop.simT, dur });
  loop.pending++;
}

// ---------- scratch (zero allocation inside useFrame) ----------

export const _obj = new THREE.Object3D();
export const _v3 = new THREE.Vector3();
export const _v3b = new THREE.Vector3();
export const _color = new THREE.Color();
export const _mat4 = new THREE.Matrix4();

export function easeInOutCubic(t: number): number {
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
}

// ---------- shared materials (single instance each; onBeforeCompile patches,
// never per-object clones) ----------

export function makeFloorMaterial(): THREE.MeshStandardMaterial {
  const mat = new THREE.MeshStandardMaterial({ color: tokenColor(PAL.bg).multiplyScalar(1.6), roughness: 0.95, metalness: 0 });
  mat.onBeforeCompile = (shader) => {
    shader.uniforms.uSellerTint = { value: tokenColor(PAL.accent) };
    shader.uniforms.uCsTint = { value: tokenColor(PAL.info) };
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', '#include <common>\nvarying float vWx;')
      .replace('#include <begin_vertex>', '#include <begin_vertex>\nvWx = (modelMatrix * vec4(transformed, 1.0)).x;');
    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>', '#include <common>\nvarying float vWx;\nuniform vec3 uSellerTint;\nuniform vec3 uCsTint;')
      .replace(
        '#include <color_fragment>',
        `#include <color_fragment>
        float sellerBand = 1.0 - smoothstep(-7.6, -6.4, vWx);
        float csBand = smoothstep(6.4, 7.6, vWx);
        diffuseColor.rgb = mix(diffuseColor.rgb, uSellerTint, sellerBand * 0.085);
        diffuseColor.rgb = mix(diffuseColor.rgb, uCsTint, csBand * 0.085);`,
      );
  };
  mat.customProgramCacheKey = () => 'ops-floor-tint';
  return mat;
}

export function makeParcelMaterial(): THREE.MeshStandardMaterial {
  const mat = new THREE.MeshStandardMaterial({ roughness: 0.55, metalness: 0.05 });
  mat.onBeforeCompile = (shader) => {
    shader.uniforms.uBand = { value: tokenColor(PAL.warn) };
    shader.vertexShader = shader.vertexShader
      .replace(
        '#include <common>',
        '#include <common>\nattribute float aBucket;\nattribute float aDim;\nvarying float vBucket;\nvarying float vDim;\nvarying float vLocalY;\nvarying vec3 vGeomNormal;',
      )
      .replace('#include <begin_vertex>', '#include <begin_vertex>\nvBucket = aBucket;\nvDim = aDim;\nvLocalY = position.y;\nvGeomNormal = normal;');
    shader.fragmentShader = shader.fragmentShader
      .replace(
        '#include <common>',
        '#include <common>\nvarying float vBucket;\nvarying float vDim;\nvarying float vLocalY;\nvarying vec3 vGeomNormal;\nuniform vec3 uBand;',
      )
      .replace(
        '#include <emissivemap_fragment>',
        `#include <emissivemap_fragment>
        float sideFace = 1.0 - step(0.5, abs(vGeomNormal.y));
        float bandMask = smoothstep(0.28, 0.42, vLocalY) * sideFace;
        totalEmissiveRadiance += uBand * (0.08 + vBucket * 0.95) * bandMask;
        float fade = 1.0 - 0.82 * vDim;
        diffuseColor.rgb *= fade;
        totalEmissiveRadiance *= fade;`,
      );
  };
  mat.customProgramCacheKey = () => 'ops-parcel';
  return mat;
}

export function makeTowerMaterial(): THREE.MeshStandardMaterial {
  const mat = new THREE.MeshStandardMaterial({ roughness: 0.6, metalness: 0.05 });
  mat.onBeforeCompile = (shader) => {
    shader.uniforms.uTime = { value: 0 };
    mat.userData.uTimeUniform = shader.uniforms.uTime; // frame loop advances this
    shader.vertexShader = shader.vertexShader
      .replace(
        '#include <common>',
        '#include <common>\nattribute float aBreathe;\nvarying float vLocalY;\nvarying float vBreathe;\nvarying float vWx;',
      )
      .replace(
        '#include <begin_vertex>',
        '#include <begin_vertex>\nvLocalY = position.y;\nvBreathe = aBreathe;\nvWx = (instanceMatrix * vec4(position, 1.0)).x;',
      );
    shader.fragmentShader = shader.fragmentShader
      .replace(
        '#include <common>',
        '#include <common>\nvarying float vLocalY;\nvarying float vBreathe;\nvarying float vWx;\nuniform float uTime;',
      )
      .replace(
        '#include <emissivemap_fragment>',
        `#include <emissivemap_fragment>
        float grad = clamp(vLocalY + 0.5, 0.0, 1.0);
        diffuseColor.rgb *= mix(0.5, 1.3, grad);
        float pulse = 0.85 + 0.55 * sin(uTime * 1.6 + vWx * 0.7);
        totalEmissiveRadiance += diffuseColor.rgb * vBreathe * pulse * 2.4;`,
      );
  };
  mat.customProgramCacheKey = () => 'ops-tower';
  return mat;
}
