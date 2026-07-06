import { useEffect, useRef } from 'react';
import { ArrowRight, LogOut, Settings } from 'lucide-react';

const VERTEX_SHADER = `
attribute vec2 a_position;
varying vec2 v_uv;

void main() {
  v_uv = a_position * 0.5 + 0.5;
  gl_Position = vec4(a_position, 0.0, 1.0);
}
`;

const RIPPLE_VISIBLE_SECONDS = 1.65;
const MAX_SHADER_RIPPLES = 24;
const RIPPLE_STEP_PX = 9;
const MAX_RIPPLES_PER_MOVE = 10;

const FRAGMENT_SHADER = `
precision highp float;

uniform vec2 u_resolution;
uniform float u_time;
uniform vec2 u_pointer;
uniform float u_pointerActive;
uniform float u_gridStrength;
uniform float u_textureStrength;
uniform int u_rippleCount;
uniform vec4 u_ripples[24];
varying vec2 v_uv;

float hash(vec2 p) {
  return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float noise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(
    mix(hash(i + vec2(0.0, 0.0)), hash(i + vec2(1.0, 0.0)), u.x),
    mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x),
    u.y
  );
}

float lineField(vec2 p, float offset) {
  float a = sin(p.x * 5.2 + sin(p.y * 2.4 + u_time * 0.33) + offset);
  float b = sin((p.x + p.y) * 3.1 - u_time * 0.24 + offset * 1.7);
  float c = sin(length(p - vec2(0.5, 0.48)) * 9.0 - u_time * 0.42 + offset);
  return abs(a * 0.54 + b * 0.32 + c * 0.2);
}

void main() {
  vec2 uv = v_uv;
  vec2 aspect = vec2(u_resolution.x / u_resolution.y, 1.0);
  vec2 pointer = u_pointer / u_resolution;
  pointer.y = 1.0 - pointer.y;

  vec2 p = (uv - 0.5) * aspect + 0.5;
  vec2 displaced = uv;
  float rippleLight = 0.0;
  float wakeLight = 0.0;

  for (int i = 0; i < 24; i++) {
    if (i >= u_rippleCount) {
      break;
    }
    vec4 r = u_ripples[i];
    if (r.z < 0.0) {
      continue;
    }
    float age = max(0.0, u_time - r.z);
    float alive = smoothstep(1.65, 0.0, age);
    vec2 rp = r.xy / u_resolution;
    rp.y = 1.0 - rp.y;
    vec2 delta = (uv - rp) * aspect;
    float distanceToRipple = length(delta);
    float seed = hash(r.xy + vec2(r.z * 37.0, r.w * 19.0));
    float angle = atan(delta.y, delta.x);
    float lobe = sin(angle * (3.0 + floor(seed * 4.0)) + seed * 6.283 + age * (1.4 + seed));
    float radiusWarp = 1.0 + lobe * 0.13 + (noise(delta * 9.0 + seed * 14.0 + age * 0.8) - 0.5) * 0.16;
    float warpedDistance = max(0.0, distanceToRipple * radiusWarp);
    float wave = sin(warpedDistance * (40.0 + seed * 18.0) - age * (9.2 + seed * 4.8));
    float envelope = exp(-warpedDistance * (4.3 + seed * 2.0)) * smoothstep(1.5, 0.0, age);
    float ringRadius = age * (0.105 + seed * 0.055);
    float ring = exp(-abs(warpedDistance - ringRadius) * (12.0 + seed * 10.0));
    ring *= 0.74 + 0.26 * sin(angle * 5.0 + seed * 8.0 + age * 2.0);
    float force = alive * r.w * envelope * wave;
    displaced += normalize(delta + 0.0001) * force * 0.0078;
    rippleLight += alive * ring * (0.22 + r.w * 0.42);
    wakeLight += alive * abs(force) * 0.96;
  }

  if (u_pointerActive > 0.5) {
    vec2 pointerDelta = (uv - pointer) * aspect;
    float pointerDistance = length(pointerDelta);
    float pressure = exp(-pointerDistance * 6.2);
    displaced += normalize(pointerDelta + 0.0001) * pressure * 0.006;
    wakeLight += pressure * 0.08;
  }

  vec2 flow = displaced;
  flow.x += noise(displaced * 3.0 + u_time * 0.08) * 0.035 * u_textureStrength;
  flow.y += noise(displaced * 4.0 - u_time * 0.07) * 0.025 * u_textureStrength;

  float fieldA = lineField(flow * vec2(1.24, 1.0), 0.2);
  float fieldB = lineField(flow.yx * vec2(1.1, 1.45), 1.8);
  float filament = smoothstep(0.105, 0.0, fieldA) * 0.7 + smoothstep(0.075, 0.0, fieldB) * 0.42;

  float grid = 0.0;
  vec2 gridUv = flow * vec2(28.0, 18.0);
  vec2 cell = abs(fract(gridUv) - 0.5);
  grid = (1.0 - smoothstep(0.0, 0.025, min(cell.x, cell.y))) * 0.035;

  float vignette = smoothstep(0.86, 0.22, length((uv - 0.5) * vec2(1.0, 0.82)));
  float glow = exp(-length((uv - vec2(0.5, 0.48)) * aspect) * 2.2);
  float grain = noise(uv * vec2(150.0, 92.0) + u_time * 0.08);

  vec3 deep = vec3(0.015, 0.055, 0.047);
  vec3 emerald = vec3(0.31, 0.92, 0.68);
  vec3 gold = vec3(0.9, 0.68, 0.34);
  vec3 color = deep;
  color += emerald * glow * 0.12;
  color += emerald * filament * 0.23;
  color += gold * filament * 0.08;
  color += emerald * grid * u_gridStrength;
  color += emerald * rippleLight * 0.32;
  color += gold * rippleLight * 0.15;
  color += emerald * wakeLight * 0.22;
  color += (grain - 0.5) * 0.025 * u_textureStrength;
  color *= 0.72 + vignette * 0.55;

  gl_FragColor = vec4(color, 0.96);
}
`;

// 메소드 설명: createShader 처리 흐름을 수행합니다.
function createShader(gl, type, source) {
  const shader = gl.createShader(type);
  gl.shaderSource(shader, source);
  gl.compileShader(shader);

  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    throw new Error(gl.getShaderInfoLog(shader) || 'Shader compile failed');
  }

  return shader;
}

// 메소드 설명: createProgram 처리 흐름을 수행합니다.
function createProgram(gl) {
  const vertexShader = createShader(gl, gl.VERTEX_SHADER, VERTEX_SHADER);
  const fragmentShader = createShader(gl, gl.FRAGMENT_SHADER, FRAGMENT_SHADER);
  const program = gl.createProgram();
  gl.attachShader(program, vertexShader);
  gl.attachShader(program, fragmentShader);
  gl.linkProgram(program);

  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    throw new Error(gl.getProgramInfoLog(program) || 'Program link failed');
  }

  return program;
}

// 메소드 설명: LandingConstellation 처리 흐름을 수행합니다.
export function LandingConstellation({ interactive = false, showGrid = false, showTexture = false }) {
  const canvasRef = useRef(null);
  const rippleLayerRef = useRef(null);

  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  useEffect(() => {
    const canvas = canvasRef.current;
    const rippleLayer = rippleLayerRef.current;
    const gl = canvas?.getContext('webgl', {
      alpha: true,
      antialias: true,
      depth: false,
      premultipliedAlpha: true,
    });

    if (!canvas || !gl) {
      return undefined;
    }

    let program;
    try {
      program = createProgram(gl);
    } catch {
      return undefined;
    }

    const buffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      new Float32Array([-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1]),
      gl.STATIC_DRAW,
    );

    const positionLocation = gl.getAttribLocation(program, 'a_position');
    const resolutionLocation = gl.getUniformLocation(program, 'u_resolution');
    const timeLocation = gl.getUniformLocation(program, 'u_time');
    const pointerLocation = gl.getUniformLocation(program, 'u_pointer');
    const pointerActiveLocation = gl.getUniformLocation(program, 'u_pointerActive');
    const gridStrengthLocation = gl.getUniformLocation(program, 'u_gridStrength');
    const textureStrengthLocation = gl.getUniformLocation(program, 'u_textureStrength');
    const rippleCountLocation = gl.getUniformLocation(program, 'u_rippleCount');
    const ripplesLocation = gl.getUniformLocation(program, 'u_ripples');
    const pointer = {
      x: 0,
      y: 0,
      lastX: 0,
      lastY: 0,
      rippleX: 0,
      rippleY: 0,
      active: false,
      moved: false,
      rippleReady: false,
    };
    const ripples = [];
    const rippleUniforms = new Float32Array(MAX_SHADER_RIPPLES * 4);
    const start = performance.now();
    let animationFrame = 0;
    let width = 0;
    let height = 0;

    // 메소드 설명: resize 처리 흐름을 수행합니다.
    const resize = () => {
      const rect = canvas.getBoundingClientRect();
      const pixelRatio = Math.min(window.devicePixelRatio || 1, interactive ? 1.45 : 1.2);
      width = Math.max(1, rect.width);
      height = Math.max(1, rect.height);
      canvas.width = Math.floor(width * pixelRatio);
      canvas.height = Math.floor(height * pixelRatio);
      gl.viewport(0, 0, canvas.width, canvas.height);
    };

    // 메소드 설명: addRipple 처리 흐름을 수행합니다.
    const addRipple = (x, y, force) => {
      const now = (performance.now() - start) / 1000;
      for (let index = ripples.length - 1; index >= 0; index -= 1) {
        if (now - ripples[index].startTime > RIPPLE_VISIBLE_SECONDS) {
          ripples.splice(index, 1);
        }
      }

      ripples.unshift({
        x,
        y,
        startTime: now,
        force,
      });

      if (rippleLayer) {
        const ring = document.createElement('span');
        ring.className = 'landing-ripple-ring';
        ring.style.left = `${x / (canvas.width / width)}px`;
        ring.style.top = `${y / (canvas.height / height)}px`;
        const spread = 0.72 + Math.random() * 0.72;
        const eccentricity = 0.72 + Math.random() * 0.42;
        const driftX = (Math.random() - 0.5) * 34;
        const driftY = (Math.random() - 0.5) * 34;
        ring.style.setProperty('--ripple-force', String(Math.max(0.4, force)));
        ring.style.setProperty('--ripple-duration', `${RIPPLE_VISIBLE_SECONDS}s`);
        ring.style.setProperty('--ripple-size', `${20 + spread * 12}px`);
        ring.style.setProperty('--ripple-scale-x', String(0.92 + spread * 0.44));
        ring.style.setProperty('--ripple-scale-y', String(eccentricity));
        ring.style.setProperty('--ripple-rotate', `${Math.random() * 180}deg`);
        ring.style.setProperty('--ripple-drift-x', `${driftX}px`);
        ring.style.setProperty('--ripple-drift-y', `${driftY}px`);
        rippleLayer.appendChild(ring);
        window.setTimeout(() => ring.remove(), RIPPLE_VISIBLE_SECONDS * 1000);
      }
    };

    // 메소드 설명: draw 처리 흐름을 수행합니다.
    const draw = () => {
      const elapsed = (performance.now() - start) / 1000;
      gl.useProgram(program);
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      gl.enableVertexAttribArray(positionLocation);
      gl.vertexAttribPointer(positionLocation, 2, gl.FLOAT, false, 0, 0);

      for (let index = ripples.length - 1; index >= 0; index -= 1) {
        if (elapsed - ripples[index].startTime > RIPPLE_VISIBLE_SECONDS) {
          ripples.splice(index, 1);
        }
      }

      const shaderRippleCount = Math.min(MAX_SHADER_RIPPLES, ripples.length);
      if (shaderRippleCount > 0) {
        rippleUniforms.fill(-1);
        const lastRippleIndex = Math.max(0, ripples.length - 1);
        for (let index = 0; index < shaderRippleCount; index += 1) {
          const sourceIndex =
            shaderRippleCount <= 1
              ? 0
              : Math.round((index / (shaderRippleCount - 1)) * lastRippleIndex);
          const ripple = ripples[sourceIndex];
          if (!ripple) {
            continue;
          }

          const offset = index * 4;
          rippleUniforms[offset] = ripple.x;
          rippleUniforms[offset + 1] = ripple.y;
          rippleUniforms[offset + 2] = ripple.startTime;
          rippleUniforms[offset + 3] = ripple.force;
        }
      }

      gl.uniform2f(resolutionLocation, canvas.width, canvas.height);
      gl.uniform1f(timeLocation, elapsed);
      gl.uniform2f(pointerLocation, pointer.x * (canvas.width / width), pointer.y * (canvas.height / height));
      gl.uniform1f(pointerActiveLocation, pointer.active ? 1 : 0);
      gl.uniform1f(gridStrengthLocation, showGrid ? 1 : 0);
      gl.uniform1f(textureStrengthLocation, showTexture ? 1 : 0);
      gl.uniform1i(rippleCountLocation, shaderRippleCount);
      if (shaderRippleCount > 0) {
        gl.uniform4fv(ripplesLocation, rippleUniforms);
      }
      gl.drawArrays(gl.TRIANGLES, 0, 6);
      animationFrame = window.requestAnimationFrame(draw);
    };

    // 메소드 설명: handlePointerMove 처리 흐름을 수행합니다.
    const handlePointerMove = (event) => {
      const rect = canvas.getBoundingClientRect();
      const nextX = event.clientX - rect.left;
      const nextY = event.clientY - rect.top;
      const distance = pointer.moved ? Math.hypot(nextX - pointer.x, nextY - pointer.y) : 0;
      pointer.lastX = pointer.moved ? pointer.x : nextX;
      pointer.lastY = pointer.moved ? pointer.y : nextY;
      pointer.x = nextX;
      pointer.y = nextY;
      pointer.active = true;
      pointer.moved = true;

      if (!pointer.rippleReady) {
        pointer.rippleX = nextX;
        pointer.rippleY = nextY;
        pointer.rippleReady = true;
        addRipple(
          nextX * (canvas.width / width),
          nextY * (canvas.height / height),
          0.24,
        );
        return;
      }

      const rippleDistance = Math.hypot(nextX - pointer.rippleX, nextY - pointer.rippleY);
      if (rippleDistance > RIPPLE_STEP_PX) {
        const count = Math.min(MAX_RIPPLES_PER_MOVE, Math.max(1, Math.floor(rippleDistance / RIPPLE_STEP_PX)));
        const startX = pointer.rippleX;
        const startY = pointer.rippleY;
        const fillWholePath = rippleDistance > RIPPLE_STEP_PX * MAX_RIPPLES_PER_MOVE;
        for (let index = 0; index < count; index += 1) {
          const progress = fillWholePath
            ? (index + 1) / count
            : ((index + 1) * RIPPLE_STEP_PX) / rippleDistance;
          const jitter = Math.min(9, 2.4 + rippleDistance * 0.018);
          const trailX = startX + (nextX - startX) * progress + (Math.random() - 0.5) * jitter;
          const trailY = startY + (nextY - startY) * progress + (Math.random() - 0.5) * jitter;
          const force = Math.min(0.64, 0.13 + rippleDistance / 220 + Math.random() * 0.08);
          addRipple(
            trailX * (canvas.width / width),
            trailY * (canvas.height / height),
            force,
          );
        }
        if (fillWholePath) {
          pointer.rippleX = nextX;
          pointer.rippleY = nextY;
        } else {
          const emittedProgress = (count * RIPPLE_STEP_PX) / rippleDistance;
          pointer.rippleX = startX + (nextX - startX) * emittedProgress;
          pointer.rippleY = startY + (nextY - startY) * emittedProgress;
        }
      }
    };

    // 메소드 설명: handlePointerLeave 처리 흐름을 수행합니다.
    const handlePointerLeave = () => {
      pointer.active = false;
      pointer.moved = false;
      pointer.rippleReady = false;
    };

    resize();
    draw();
    window.addEventListener('resize', resize);
    if (interactive) {
      window.addEventListener('pointermove', handlePointerMove);
      window.addEventListener('pointerleave', handlePointerLeave);
    }

    return () => {
      window.cancelAnimationFrame(animationFrame);
      window.removeEventListener('resize', resize);
      if (interactive) {
        window.removeEventListener('pointermove', handlePointerMove);
        window.removeEventListener('pointerleave', handlePointerLeave);
      }
      gl.deleteProgram(program);
      gl.deleteBuffer(buffer);
    };
  }, [interactive, showGrid, showTexture]);

  return (
    <>
      <canvas ref={canvasRef} className="landing-constellation" aria-hidden="true" />
      {interactive ? <div ref={rippleLayerRef} className="landing-ripple-layer" aria-hidden="true" /> : null}
    </>
  );
}

// 메소드 설명: LandingPage 처리 흐름을 수행합니다.
export function LandingPage({ admin, onEnter, onAdmin, onLogout }) {
  return (
    <main className="landing">
      <LandingConstellation interactive={false} showGrid={false} showTexture={false} />
      <div className="session-actions">
        <span>{admin?.displayName ?? admin?.username}</span>
        <button type="button" onClick={onLogout} aria-label="Logout">
          <LogOut aria-hidden="true" size={16} strokeWidth={1.6} />
        </button>
      </div>
      <section className="hero" aria-labelledby="brand-title">
        <p className="kicker">law open data workspace</p>
        <h1 id="brand-title">pandora</h1>
        <button className="enter-button" type="button" onClick={onEnter}>
          <span>Open Pandora</span>
          <ArrowRight aria-hidden="true" size={16} strokeWidth={1.5} />
        </button>
        <button className="admin-entry-button" type="button" onClick={onAdmin} aria-label="Admin">
          <Settings aria-hidden="true" size={17} strokeWidth={1.5} />
          <span>Admin</span>
        </button>
      </section>
    </main>
  );
}
