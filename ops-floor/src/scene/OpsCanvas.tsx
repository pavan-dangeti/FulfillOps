import { useEffect, useRef } from 'react';
import * as THREE from 'three';
import { Canvas, events as createPointerEvents, useFrame, useThree } from '@react-three/fiber';
import { CameraControls, PerformanceMonitor } from '@react-three/drei';
import { EffectComposer, N8AO, Bloom, SMAA, ToneMapping } from '@react-three/postprocessing';
import { ToneMappingMode } from 'postprocessing';
import { Floor, Lanes, Labels } from './Floor';
import { SkuTowers } from './SkuTowers';
import { Parcels } from './Parcels';
import { Arcs } from './Arcs';
import { loop, PAL, sceneState } from './shared';
import { useStore } from '../store';

const DEG = Math.PI / 180;

export const CAMERA_PRESETS = {
  overview: { pos: [0, 21, 42] as const, target: [0, 1, 0] as const },
  seller: { pos: [-14, 9, 17] as const, target: [-13, 1, 0] as const },
  cs: { pos: [14, 9, 17] as const, target: [13, 1, 0] as const },
};

// Raycasting: throttled pointermove (<=30Hz) via the events factory, raycaster
// restricted to layer 1 (parcels + arcs only), Line2 threshold for arcs.
const throttledPointerEvents = ((store: unknown) => {
  const original = createPointerEvents(store as never);
  let last = 0;
  const move = original.handlers!.onPointerMove;
  return {
    ...original,
    handlers: {
      ...original.handlers,
      onPointerMove: (event: PointerEvent) => {
        const now = performance.now();
        if (now - last < 33) return; // <=30Hz
        last = now;
        move(event);
      },
    },
  };
}) as never;

function RaycastSetup() {
  const raycaster = useThree((s) => s.raycaster);
  useEffect(() => {
    raycaster.layers.set(1); // only interactive objects
    const params = raycaster.params as { Line2?: { threshold: number }; Line?: { threshold: number } };
    params.Line2 = { threshold: 0.35 };
    params.Line = { threshold: 0.35 };
  }, [raycaster]);
  return null;
}

function InvalidateBridge() {
  const invalidate = useThree((s) => s.invalidate);
  useEffect(() => {
    loop.invalidate = invalidate;
    return useStore.subscribe(() => invalidate());
  }, [invalidate]);
  return null;
}

function Perf() {
  const setDpr = useThree((s) => s.setDpr);
  return <PerformanceMonitor onDecline={() => setDpr(1)} onIncline={() => setDpr(1.75)} />;
}

// ?stats=1 overlay reads renderer.info — draw-call assertion lives here.
export const statsBridge = {
  calls: 0, // full composed frame (scene + shadow + post passes)
  sceneCalls: 0, // main scene pass only (geometry + shadow maps) — the budget target
  frame: 0,
  triangles: 0,
  geometries: 0,
  textures: 0,
};

function StatsBridge() {
  const gl = useThree((s) => s.gl);
  const signaledReady = useRef(false);
  useFrame(() => {
    // values accumulated since the previous reset = the last full composed
    // frame (useFrame runs before the composer's passes, then we reset)
    statsBridge.calls = gl.info.render.calls;
    statsBridge.sceneCalls = gl.info.render.calls; // fallback when no composer
    statsBridge.frame = gl.info.render.frame;
    statsBridge.triangles = gl.info.render.triangles;
    statsBridge.geometries = gl.info.memory.geometries;
    statsBridge.textures = gl.info.memory.textures;
    gl.info.reset();
    // Fire once: the first committed frame is the signal that Html-portalled
    // scene content (lane/SKU labels) has actually reached the DOM. Cheap —
    // a single ref check, never re-fires, no allocation.
    if (!signaledReady.current) {
      signaledReady.current = true;
      useStore.getState().setSceneReady(true);
    }
  });
  return null;
}

// Environment: PMREMGenerator from a procedural gradient scene at startup,
// source disposed immediately after. No HDRI file, no CDN.
function ProceduralEnv() {
  const gl = useThree((s) => s.gl);
  const scene = useThree((s) => s.scene);
  useEffect(() => {
    const pmrem = new THREE.PMREMGenerator(gl);
    const envScene = new THREE.Scene();
    const geo = new THREE.SphereGeometry(20, 16, 16);
    const mat = new THREE.ShaderMaterial({
      side: THREE.BackSide,
      uniforms: {
        uTop: { value: new THREE.Color('#1a2230') },
        uHorizon: { value: new THREE.Color('#232a36') },
        uBottom: { value: new THREE.Color('#0f1115') },
        uKey: { value: new THREE.Color('#31435f') },
      },
      vertexShader: `
        varying vec3 vDir;
        void main() {
          vDir = normalize(position);
          gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
        }`,
      fragmentShader: `
        varying vec3 vDir;
        uniform vec3 uTop; uniform vec3 uHorizon; uniform vec3 uBottom; uniform vec3 uKey;
        void main() {
          float h = vDir.y;
          vec3 c = h > 0.0 ? mix(uHorizon, uTop, pow(h, 0.7)) : mix(uHorizon, uBottom, pow(-h, 0.7));
          // soft key light patch upper right for reflections
          float key = pow(max(dot(vDir, normalize(vec3(0.6, 0.75, 0.4))), 0.0), 6.0);
          c = mix(c, uKey, key);
          gl_FragColor = vec4(c, 1.0);
        }`,
    });
    envScene.add(new THREE.Mesh(geo, mat));
    const rt = pmrem.fromScene(envScene, 0.04);
    scene.environment = rt.texture;
    scene.environmentIntensity = 0.45;
    geo.dispose();
    mat.dispose();
    pmrem.dispose();
    return () => {
      scene.environment = null;
      rt.texture.dispose();
      rt.dispose();
    };
  }, [gl, scene]);
  return null;
}

function CameraRig() {
  const ref = useRef<CameraControls>(null);
  const preset = useStore((s) => s.preset);
  const reduced = useStore((s) => s.reduced);
  const selected = useStore((s) => s.selected);

  useEffect(() => {
    const c = ref.current;
    if (!c) return;
    c.smoothTime = reduced ? 0 : 0.08; // reduced motion: damping off
  }, [reduced]);

  useEffect(() => {
    const c = ref.current;
    if (!c) return;
    const p = CAMERA_PRESETS[preset];
    void c.setLookAt(p.pos[0], p.pos[1], p.pos[2], p.target[0], p.target[1], p.target[2], !reduced);
  }, [preset, reduced]);

  // snap to the overview preset on mount
  useEffect(() => {
    const c = ref.current;
    if (!c) return;
    const p = CAMERA_PRESETS.overview;
    void c.setLookAt(p.pos[0], p.pos[1], p.pos[2], p.target[0], p.target[1], p.target[2], false);
  }, []);

  // F key: focus the selected parcel
  useEffect(() => {
    loop.controls = {
      setLookAt: (px, py, pz, tx, ty, tz, t) => {
        void ref.current?.setLookAt(px, py, pz, tx, ty, tz, t);
      },
    };
    return () => {
      loop.controls = null;
    };
  }, []);

  useEffect(() => {
    if (!selected || !loop.controls) return;
    const p = sceneSelectedPos(selected);
    if (!p) return;
    void loop.controls.setLookAt(p[0] + 3, p[1] + 3, p[2] + 6, p[0], p[1], p[2], true);
  }, [selected]);

  return (
    <CameraControls
      ref={ref}
      makeDefault
      minPolarAngle={12 * DEG}
      maxPolarAngle={78 * DEG}
      minDistance={14}
      maxDistance={70}
    />
  );
}

function sceneSelectedPos(key: string): [number, number, number] | null {
  const parcel = sceneState.data?.parcels.find((p) => p.key === key);
  return parcel ? [parcel.x, parcel.y, parcel.z] : null;
}

export function OpsCanvas() {
  const setContextLost = useStore((s) => s.setContextLost);
  return (
    <Canvas
      frameloop="demand"
      dpr={[1, 1.75]}
      camera={{ fov: 32, position: [0, 21, 42], near: 0.5, far: 300 }}
      gl={{ antialias: false, powerPreference: 'high-performance' }}
      shadows
      events={throttledPointerEvents}
      onCreated={({ gl, scene }) => {
        gl.outputColorSpace = THREE.SRGBColorSpace;
        gl.toneMapping = THREE.ACESFilmicToneMapping;
        THREE.ColorManagement.enabled = true;
        // stats accumulate across the composer's passes; StatsBridge resets
        gl.info.autoReset = false;
        // capture the main scene pass (geometry + shadow maps) separately from
        // post-processing fullscreen passes — the <40 budget targets the scene
        const origRender = gl.render.bind(gl);
        gl.render = (s: THREE.Scene, c: THREE.Camera) => {
          origRender(s, c);
          if (s === scene) statsBridge.sceneCalls = gl.info.render.calls;
        };
        // debug handle for tests/HMR verification
        (window as unknown as { __opsDebug: unknown }).__opsDebug = { gl, scene };
        gl.domElement.addEventListener(
          'webglcontextlost',
          (e) => {
            e.preventDefault();
            setContextLost(true);
          },
          false,
        );
        gl.domElement.addEventListener(
          'webglcontextrestored',
          () => {
            setContextLost(false);
            loop.invalidate();
          },
          false,
        );
      }}
    >
      <RaycastSetup />
      <InvalidateBridge />
      <Perf />
      <StatsBridge />
      <ProceduralEnv />
      <CameraRig />

      <hemisphereLight args={['#2a3a52', PAL.bg, 0.5]} />
      <directionalLight
        position={[12, 22, 10]}
        intensity={1.7}
        castShadow
        shadow-mapSize={[2048, 2048]}
        shadow-camera-left={-26}
        shadow-camera-right={26}
        shadow-camera-top={20}
        shadow-camera-bottom={-16}
        shadow-camera-near={2}
        shadow-camera-far={70}
        shadow-bias={-0.0002}
        shadow-normalBias={0.03}
      />

      <Floor />
      <Lanes />
      <Labels />
      <SkuTowers />
      <Parcels />
      <Arcs />

      <EffectComposer multisampling={0} enableNormalPass={false} enabled={(window as unknown as { __noPost?: boolean }).__noPost !== true}>
        <N8AO halfRes aoRadius={5} intensity={1.6} distanceFalloff={1} />
        <Bloom mipmapBlur levels={3} luminanceThreshold={0.9} intensity={0.9} />
        <ToneMapping mode={ToneMappingMode.ACES_FILMIC} />
        <SMAA />
      </EffectComposer>
    </Canvas>
  );
}
