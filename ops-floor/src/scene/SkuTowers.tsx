import { useEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { useFrame } from '@react-three/fiber';
import { Html } from '@react-three/drei';
import {
  SKU_COLUMN,
  TOWER_SHELL_H,
  _color,
  _obj,
  easeInOutCubic,
  loop,
  makeTowerMaterial,
  sceneState,
  tokenColor,
  towerTransitions,
} from './shared';
import { useStore } from '../store';

// Five extruded towers, one per SKU. Tower fill height maps to stock on the
// shared log-ish scale (stockHeight) so 0 and 142 both read. Fill color:
// --ok above 10, --warn at <=10, --danger at 0 with a slow emissive breathe.
// The shell is a thin translucent extrusion; the fill is a separate instanced
// mesh scaled on Y. Labels via drei/Html with occlude="blending".
export function SkuTowers() {
  // re-render only when the scene model changes (sceneVersion bumps)
  useStore((s) => s.sceneVersion);
  const towers = sceneState.data?.towers ?? [];

  const fillMat = useMemo(() => makeTowerMaterial(), []);
  const shellMat = useMemo(
    () =>
      new THREE.MeshStandardMaterial({
        color: tokenColor('#262b33'),
        transparent: true,
        opacity: 0.16,
        roughness: 0.35,
        metalness: 0.1,
        depthWrite: false,
      }),
    [],
  );
  const shellGeo = useMemo(() => new THREE.BoxGeometry(1.7, 1, 1.7), []);
  const shellRef = useRef<THREE.InstancedMesh>(null);
  useEffect(() => {
    const mesh = shellRef.current;
    if (!mesh) return;
    SKU_COLUMN.xs.forEach((x, i) => {
      _obj.position.set(x, TOWER_SHELL_H / 2, SKU_COLUMN.z);
      _obj.scale.set(1, 1, 1);
      _obj.rotation.set(0, 0, 0);
      _obj.updateMatrix();
      mesh.setMatrixAt(i, _obj.matrix);
    });
    mesh.count = SKU_COLUMN.xs.length;
    mesh.instanceMatrix.needsUpdate = true;
  }, []);
  const fillGeo = useMemo(() => {
    const g = new THREE.BoxGeometry(1, 1, 1);
    g.setAttribute('aBreathe', new THREE.InstancedBufferAttribute(new Float32Array(8), 1));
    return g;
  }, []);

  useEffect(
    () => () => {
      fillMat.dispose();
      shellMat.dispose();
      shellGeo.dispose();
      fillGeo.dispose();
    },
    [fillMat, shellMat, shellGeo, fillGeo],
  );

  return (
    <group>
      {/* translucent shells — fixed height, one instanced mesh for all SKUs */}
      <instancedMesh
        ref={shellRef}
        args={[shellGeo, shellMat, SKU_COLUMN.xs.length]}
        castShadow
        frustumCulled={false}
      />

      {/* fill — one instanced mesh, per-instance color + breathe */}
      {towers.length > 0 && <FillInstances towers={towers} mat={fillMat} geo={fillGeo} />}

      {towers.map((t, i) => (
        <Html
          key={t.sku}
          position={[SKU_COLUMN.xs[i], TOWER_SHELL_H + 0.3 + (i % 2) * 0.55, SKU_COLUMN.z]}
          center
          zIndexRange={[8, 0]}
          occlude="blending"
        >
          <div className="label-3d">
            {t.sku}
            <span className="name">{t.name}</span> · {t.stock}
          </div>
        </Html>
      ))}
    </group>
  );
}

const TOWER_PALETTE = [tokenColor('#3ecf8e'), tokenColor('#f0b429'), tokenColor('#ff6b6b')];

function FillInstances({
  towers,
  mat,
  geo,
}: {
  towers: { sku: string; height: number; colorIndex: number; breathe: boolean }[];
  mat: THREE.MeshStandardMaterial;
  geo: THREE.BoxGeometry;
}) {
  const ref = useRef<THREE.InstancedMesh>(null);

  // per-instance color + breathe, set on model change (not per frame)
  useEffect(() => {
    const mesh = ref.current;
    if (!mesh) return;
    mesh.count = towers.length;
    const attr = geo.getAttribute('aBreathe') as THREE.InstancedBufferAttribute;
    towers.forEach((t, i) => {
      _color.copy(TOWER_PALETTE[t.colorIndex]);
      mesh.setColorAt(i, _color);
      attr.setX(i, t.breathe ? 1 : 0);
    });
    attr.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    mesh.instanceMatrix.needsUpdate = true;
  }, [towers, geo]);

  // Batched frame writes: advance fill transitions, rewrite the whole batch,
  // needsUpdate once. Zero allocation (module-scope scratch in shared.ts).
  const uTime = mat.userData.uTimeUniform as { value: number } | undefined;
  useFrame(() => {
    if (uTime) uTime.value = loop.simT;
    const mesh = ref.current;
    if (!mesh) return;
    let animated = false;
    for (const [sku, tr] of towerTransitions) {
      const t = Math.min(1, (loop.simT - tr.start) / tr.dur);
      const tower = towers.find((x) => x.sku === sku);
      if (tower) tower.height = tr.from + (tr.to - tr.from) * easeInOutCubic(t);
      if (t >= 1) towerTransitions.delete(sku);
      animated = true;
    }
    for (let i = 0; i < towers.length; i++) {
      const t = towers[i];
      _obj.position.set(SKU_COLUMN.xs[i], t.height / 2, SKU_COLUMN.z);
      _obj.scale.set(1.15, t.height, 1.15);
      _obj.rotation.set(0, 0, 0);
      _obj.updateMatrix();
      mesh.setMatrixAt(i, _obj.matrix);
    }
    mesh.instanceMatrix.needsUpdate = true;
    if (animated) loop.pending--;
  });

  return <instancedMesh ref={ref} args={[geo, mat, towers.length]} castShadow />;
}
