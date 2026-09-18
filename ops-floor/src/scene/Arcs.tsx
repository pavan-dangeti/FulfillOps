import { useEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { useFrame } from '@react-three/fiber';
import { Line as DreiLine } from '@react-three/drei';
import type { Line2 } from 'three/examples/jsm/lines/Line2.js';
import { sceneState } from './shared';
import { useStore } from '../store';

// Divergence rendering: for each order number present in both stores, an arc
// connects the two parcels across the SKU column. Matching fields → dim thin
// muted arc. Any of sku/quantity/status/customer differing → --danger, thicker,
// dashed, offset animated along its length. Hovering shows the diff panel.
export function Arcs() {
  useStore((s) => s.sceneVersion);
  const data = sceneState.data;
  const hovered = useStore((s) => s.hoveredArc);
  const reduced = useStore((s) => s.reduced);

  const arcs = useMemo(
    () =>
      (data?.arcs ?? []).map((a) => {
        const curve = new THREE.CatmullRomCurve3([
          new THREE.Vector3(a.ax, a.ay + 0.3, a.az),
          new THREE.Vector3((a.ax + a.bx) / 2, 2.6, (a.az + a.bz) / 2 - 1.2),
          new THREE.Vector3((a.ax + a.bx) / 2, 3.4, (a.az + a.bz) / 2),
          new THREE.Vector3((a.ax + a.bx) / 2, 2.6, (a.az + a.bz) / 2 + 1.2),
          new THREE.Vector3(a.bx, a.by + 0.3, a.bz),
        ]);
        return { ...a, points: curve.getSpacedPoints(56) };
      }),
    [data],
  );

  // dispose old geometries when the arc set changes
  const prevGeos = useRef<THREE.BufferGeometry[]>([]);
  useEffect(() => {
    for (const g of prevGeos.current) g.dispose();
    prevGeos.current = [];
  }, [arcs]);

  const refs = useRef<(Line2 | null)[]>([]);
  refs.current.length = arcs.length;

  // arcs are raycastable: enable layer 1 (camera still renders layer 0)
  useEffect(() => {
    for (const el of refs.current) el?.layers.enable(1);
  }, [arcs]);

  // dashed offset animated along the length — invalidation comes from
  // loop.ambient (divergences exist) via the App rAF loop; frozen when paused
  // or reduced.
  useFrame(() => {
    if (reduced) return;
    for (let i = 0; i < arcs.length; i++) {
      if (!arcs[i].divergent) continue;
      const mat = refs.current[i]?.material as unknown as { dashOffset: number } | undefined;
      if (mat) mat.dashOffset -= 0.012;
    }
  });

  const onOver = (key: string) => (e: { stopPropagation: () => void }) => {
    useStore.getState().hoverArc(key);
    e.stopPropagation();
  };
  const onOut = () => useStore.getState().hoverArc(null);

  return (
    <group>
      {arcs.map((a, i) => (
        <DreiLine
          key={a.key}
          ref={(el) => {
            refs.current[i] = (el as unknown as Line2) ?? null;
          }}
          points={a.points}
          color={a.divergent ? '#ff6b6b' : '#8b93a1'}
          lineWidth={a.divergent ? 2.4 : 1}
          dashed={a.divergent}
          dashSize={0.34}
          gapSize={0.2}
          dashScale={1}
          transparent={!a.divergent}
          opacity={a.divergent ? 1 : 0.45}
          toneMapped={false}
          onPointerOver={onOver(a.key)}
          onPointerOut={onOut}
        />
      ))}
      {hovered && <HoverHighlight hovered={hovered} />}
    </group>
  );
}

// Both endpoints of a hovered divergent arc get a brief lift marker — the
// persistent outlines already exist; hovering brightens the arc itself via
// lineWidth and shows the diff panel in the HUD.
function HoverHighlight({ hovered }: { hovered: string }) {
  useStore((s) => s.sceneVersion);
  const data = sceneState.data;
  const arc = data?.arcs.find((a) => a.key === hovered);
  if (!arc || !arc.divergent) return null;
  return (
    <mesh position={[arc.ax, 0.02, arc.az]} rotation={[-Math.PI / 2, 0, 0]}>
      <ringGeometry args={[0.5, 0.68, 32]} />
      <meshBasicMaterial color="#ff6b6b" transparent opacity={0.7} toneMapped={false} />
    </mesh>
  );
}
