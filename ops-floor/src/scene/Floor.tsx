import { useEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { Html } from '@react-three/drei';
import {
  CS_SPAN,
  FLOOR,
  LANES,
  SELLER_SPAN,
  _obj,
  _color,
  isDimmed,
  makeFloorMaterial,
} from './shared';
import { useStore } from '../store';

// One floor plane; the two store bands are tinted inside the floor's own
// material shader (see makeFloorMaterial), so the three bands are one mesh.
export function Floor() {
  const mat = useMemo(() => makeFloorMaterial(), []);
  useEffect(() => () => mat.dispose(), [mat]);

  const w = FLOOR.x1 - FLOOR.x0;
  const d = FLOOR.z1 - FLOOR.z0;
  return (
    <mesh
      receiveShadow
      position={[(FLOOR.x0 + FLOOR.x1) / 2, 0, (FLOOR.z0 + FLOOR.z1) / 2]}
      rotation={[-Math.PI / 2, 0, 0]}
      material={mat}
    >
      <planeGeometry args={[w, d]} />
    </mesh>
  );
}

// Status lanes: four per plane plus a REFUNDED return lane that only the CS
// plane has — physically absent on the seller side. That absence is the
// visual argument that the Angular model can't express refunds.
export function Lanes() {
  const dim = useStore((s) => s.planes.seller && s.planes.cs ? 0.3 : 0.08);
  const ref = useRef<THREE.InstancedMesh>(null);

  const strips = useMemo(() => {
    const out: { x: number; z: number; seller: boolean }[] = [];
    for (const lane of LANES) {
      const hasSeller = lane.status !== 'REFUNDED';
      if (hasSeller) out.push({ x: (SELLER_SPAN.x0 + SELLER_SPAN.x1) / 2, z: lane.z, seller: true });
      out.push({ x: (CS_SPAN.x0 + CS_SPAN.x1) / 2, z: lane.z, seller: false });
    }
    return out;
  }, []);

  useEffect(() => {
    const mesh = ref.current;
    if (!mesh) return;
    for (let i = 0; i < strips.length; i++) {
      _obj.position.set(strips[i].x, 0.012, strips[i].z);
      _obj.scale.set(9.2, 1, 0.09);
      _obj.rotation.set(-Math.PI / 2, 0, 0);
      _obj.updateMatrix();
      mesh.setMatrixAt(i, _obj.matrix);
      // refunded lane strip only exists on the CS plane
      _color.setStyle(strips[i].seller ? '#5b8cff' : strips[i].z === 10 ? '#ff6b6b' : '#4cc2ff', THREE.SRGBColorSpace);
      mesh.setColorAt(i, _color);
    }
    mesh.count = strips.length;
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  }, [strips]);

  useEffect(
    () => () => {
      ref.current?.geometry.dispose();
    },
    [],
  );

  return (
    <instancedMesh ref={ref} args={[undefined, undefined, strips.length]} key={strips.length} frustumCulled={false}>
      <planeGeometry args={[1, 1]} />
      <meshBasicMaterial transparent opacity={dim} toneMapped={false} />
    </instancedMesh>
  );
}

export function Labels() {
  const planes = useStore((s) => s.planes);
  const statusFilter = useStore((s) => s.statusFilter);
  const isolate = useStore((s) => s.isolate);

  const planeDimmed = (store: 'seller' | 'cs') => {
    const divergent = false;
    return isDimmed('PENDING', store, divergent, { statusFilter, planes, isolate });
  };

  const sellerDim = planeDimmed('seller');
  const csDim = planeDimmed('cs');

  return (
    <group>
      <Html position={[SELLER_SPAN.x0 + 4.5, 0.3, -8.2]} center zIndexRange={[8, 0]}>
        <div className={`label-3d plane seller${sellerDim ? ' dimmed' : ''}`}>seller store · angular</div>
      </Html>
      <Html position={[CS_SPAN.x0 + 5, 0.3, -8.2]} center zIndexRange={[8, 0]}>
        <div className={`label-3d plane cs${csDim ? ' dimmed' : ''}`}>cs store · spring</div>
      </Html>
      {LANES.map((lane) => {
        const isSeller = lane.status !== 'REFUNDED';
        const d = isSeller ? sellerDim : csDim;
        return (
          <group key={lane.status}>
            {isSeller && (
              <Html position={[SELLER_SPAN.x0 - 0.9, 0.3, lane.z]} center zIndexRange={[8, 0]}>
                <div className={`label-3d lane${d ? ' dimmed' : ''}`} data-plane="seller">
                  {lane.status}
                </div>
              </Html>
            )}
            <Html position={[CS_SPAN.x0 - 0.9, 0.3, lane.z]} center zIndexRange={[8, 0]}>
              <div
                className={`label-3d lane${lane.status === 'REFUNDED' ? ' refunded' : ''}${d ? ' dimmed' : ''}`}
                data-plane="cs"
              >
                {lane.status}
              </div>
            </Html>
          </group>
        );
      })}
    </group>
  );
}
