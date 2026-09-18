import { useEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { useFrame } from '@react-three/fiber';
import {
  _obj,
  _color,
  easeInOutCubic,
  laneTransitions,
  loop,
  makeParcelMaterial,
  sceneState,
  tokenColor,
} from './shared';
import { useStore } from '../store';

const PARCEL_CAPACITY = 512; // 17 parcels at seed; headroom for live growth
const OUTLINE_CAPACITY = 8; // 4 seed divergences × 2 endpoints

const PARCEL_PALETTE = [tokenColor('#5b8cff'), tokenColor('#4cc2ff'), tokenColor('#ff6b6b')];

// One InstancedMesh for all parcels across both planes. Each parcel sits on a
// status lane (4 per plane + the CS-only REFUNDED return lane). Size scales
// subtly with quantity; a thin emissive band encodes the total bucket.
export function Parcels() {
  useStore((s) => s.sceneVersion);
  const data = sceneState.data;

  const mat = useMemo(() => makeParcelMaterial(), []);
  const geo = useMemo(() => {
    const g = new THREE.BoxGeometry(1, 1, 1);
    g.setAttribute('aBucket', new THREE.InstancedBufferAttribute(new Float32Array(PARCEL_CAPACITY), 1));
    g.setAttribute('aDim', new THREE.InstancedBufferAttribute(new Float32Array(PARCEL_CAPACITY), 1));
    return g;
  }, []);
  const outlineGeo = useMemo(() => new THREE.BoxGeometry(1, 1, 1), []);
  const outlineMat = useMemo(
    () =>
      new THREE.MeshBasicMaterial({
        // >1 so the divergence outline clears the bloom threshold (0.9)
        color: tokenColor('#ff6b6b', _color).multiplyScalar(2.4),
        side: THREE.BackSide,
        toneMapped: false,
      }),
    [],
  );

  useEffect(
    () => () => {
      mat.dispose();
      geo.dispose();
      outlineGeo.dispose();
      outlineMat.dispose();
    },
    [mat, geo, outlineGeo, outlineMat],
  );

  // interactive layer: the raycaster is restricted to layer 1 (see OpsCanvas).
  // enable(1) keeps the meshes on the camera's layer 0 as well — set(1) would
  // remove them from the render entirely.
  const parcelRef = useRef<THREE.InstancedMesh>(null);
  const outlineRef = useRef<THREE.InstancedMesh>(null);
  useEffect(() => {
    parcelRef.current?.layers.enable(1);
    outlineRef.current?.layers.enable(1);
  }, []);

  // per-instance attributes on model change
  useEffect(() => {
    const mesh = parcelRef.current;
    if (!mesh || !data) return;
    mesh.count = data.parcels.length;
    const bucket = geo.getAttribute('aBucket') as THREE.InstancedBufferAttribute;
    const dim = geo.getAttribute('aDim') as THREE.InstancedBufferAttribute;
    data.parcels.forEach((p, i) => {
      bucket.setX(i, p.bucket);
      dim.setX(i, p.dimmed ? 1 : 0);
      _color.copy(PARCEL_PALETTE[p.colorIndex]);
      mesh.setColorAt(i, _color);
    });
    bucket.needsUpdate = true;
    dim.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  }, [data, geo]);

  // Batched frame writes: advance lane transitions (refund travel / ship
  // moves), rewrite the batch, needsUpdate once. Zero allocation.
  useFrameParcels(parcelRef, outlineRef);

  const onPointerDown = (e: { instanceId?: number; stopPropagation: () => void }) => {
    if (!data || e.instanceId === undefined) return;
    const p = data.parcels[e.instanceId];
    if (!p || p.dimmed) return; // dimmed parcels are raycast-disabled
    useStore.getState().select(p.key);
    e.stopPropagation();
  };

  return (
    <group>
      <instancedMesh
        ref={parcelRef}
        args={[geo, mat, PARCEL_CAPACITY]}
        castShadow
        frustumCulled={false}
        onPointerDown={onPointerDown}
      />
      {/* persistent outline on divergent endpoints */}
      <instancedMesh ref={outlineRef} args={[outlineGeo, outlineMat, OUTLINE_CAPACITY]} frustumCulled={false} />
    </group>
  );
}

function useFrameParcels(
  parcelRef: React.RefObject<THREE.InstancedMesh | null>,
  outlineRef: React.RefObject<THREE.InstancedMesh | null>,
) {
  useFrame(() => {
    const data = sceneState.data;
    const mesh = parcelRef.current;
    if (!mesh) return;

    if (data) {
      let animated = false;
      for (const [key, tr] of laneTransitions) {
        const t = Math.min(1, (loop.simT - tr.start) / tr.dur);
        const e = easeInOutCubic(t);
        const f = e * (tr.n - 1);
        const i0 = Math.floor(f);
        const i1 = Math.min(tr.n - 1, i0 + 1);
        const frac = f - i0;
        const p = data.parcels.find((x) => x.key === key);
        if (p) {
          p.x = tr.points[i0 * 3] + (tr.points[i1 * 3] - tr.points[i0 * 3]) * frac;
          p.y = tr.points[i0 * 3 + 1] + (tr.points[i1 * 3 + 1] - tr.points[i0 * 3 + 1]) * frac;
          p.z = tr.points[i0 * 3 + 2] + (tr.points[i1 * 3 + 2] - tr.points[i0 * 3 + 2]) * frac;
        }
        if (t >= 1) laneTransitions.delete(key);
        animated = true;
      }

      for (let i = 0; i < data.parcels.length; i++) {
        const p = data.parcels[i];
        _obj.position.set(p.x, p.y, p.z);
        _obj.scale.set(p.scale, 0.55 * p.scale, 0.75 * p.scale);
        _obj.rotation.set(0, 0, 0);
        _obj.updateMatrix();
        mesh.setMatrixAt(i, _obj.matrix);
      }
      mesh.count = data.parcels.length;
      mesh.instanceMatrix.needsUpdate = true;

      // divergence endpoint outlines mirror the parcels
      const outline = outlineRef.current;
      if (outline) {
        let n = 0;
        for (const p of data.parcels) {
          if (!p.divergent || n >= OUTLINE_CAPACITY) continue;
          _obj.position.set(p.x, p.y, p.z);
          _obj.scale.set(p.scale * 1.16, 0.55 * p.scale * 1.16, 0.75 * p.scale * 1.16);
          _obj.rotation.set(0, 0, 0);
          _obj.updateMatrix();
          outline.setMatrixAt(n, _obj.matrix);
          n++;
        }
        outline.count = n;
        outline.instanceMatrix.needsUpdate = true;
      }

      if (animated) loop.pending--;
    }
  });
}
