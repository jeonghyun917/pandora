import { useEffect, useRef } from 'react';
import { ArrowRight } from 'lucide-react';

const VERTEX_SHADER = `
attribute vec2 a_position;
varying vec2 v_uv;

void main() {
  v_uv = a_position * 0.5 + 0.5;
  gl_Position = vec4(a_position, 0.0, 1.0);
}
`;

const RIPPLE_VISIBLE_SECONDS = 1.5;
const MAX_SHADER_RIPPLES = 24;

const FRAGMENT_SHADER = `
precision highp float;

uniform vec2 u_resolution;
uniform float u_time;
uniform vec2 u_pointer;
uniform float u_pointerActive;
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
    vec4 r = u_ripples[i];
    float age = max(0.0, u_time - r.z);
    float alive = step(0.0, r.z) * smoothstep(1.5, 0.0, age);
    vec2 rp = r.xy / u_resolution;
    rp.y = 1.0 - rp.y;
    vec2 delta = (uv - rp) * aspect;
    float distanceToRipple = length(delta);
    float wave = sin(distanceToRipple * 48.0 - age * 10.8);
    float envelope = exp(-distanceToRipple * 5.0) * smoothstep(1.5, 0.0, age);
    float ring = exp(-abs(distanceToRipple - age * 0.13) * 15.0);
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
  flow.x += noise(displaced * 3.0 + u_time * 0.08) * 0.035;
  flow.y += noise(displaced * 4.0 - u_time * 0.07) * 0.025;

  float fieldA = lineField(flow * vec2(1.24, 1.0), 0.2);
  float fieldB = lineField(flow.yx * vec2(1.1, 1.45), 1.8);
  float filament = smoothstep(0.105, 0.0, fieldA) * 0.7 + smoothstep(0.075, 0.0, fieldB) * 0.42;

  float grid = 0.0;
  vec2 gridUv = flow * vec2(28.0, 18.0);
  vec2 cell = abs(fract(gridUv) - 0.5);
  grid = (1.0 - smoothstep(0.0, 0.025, min(cell.x, cell.y))) * 0.035;

  float vignette = smoothstep(0.86, 0.22, length((uv - 0.5) * vec2(1.0, 0.82)));
  float glow = exp(-length((uv - vec2(0.5, 0.48)) * aspect) * 2.2);
  float grain = noise(uv * u_resolution.xy * 0.75 + u_time);

  vec3 deep = vec3(0.015, 0.055, 0.047);
  vec3 emerald = vec3(0.31, 0.92, 0.68);
  vec3 gold = vec3(0.9, 0.68, 0.34);
  vec3 color = deep;
  color += emerald * glow * 0.12;
  color += emerald * filament * 0.23;
  color += gold * filament * 0.08;
  color += emerald * grid;
  color += emerald * rippleLight * 0.32;
  color += gold * rippleLight * 0.15;
  color += emerald * wakeLight * 0.22;
  color += (grain - 0.5) * 0.025;
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
export function LandingConstellation({ interactive = true }) {
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
    const ripplesLocation = gl.getUniformLocation(program, 'u_ripples');
    const pointer = { x: 0, y: 0, lastX: 0, lastY: 0, active: false, moved: false };
    const ripples = [];
    const rippleUniforms = new Float32Array(MAX_SHADER_RIPPLES * 4);
    const start = performance.now();
    let animationFrame = 0;
    let width = 0;
    let height = 0;

    // 메소드 설명: resize 처리 흐름을 수행합니다.
    const resize = () => {
      const rect = canvas.getBoundingClientRect();
      const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
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
        ring.style.setProperty('--ripple-force', String(Math.max(0.4, force)));
        ring.style.setProperty('--ripple-duration', `${RIPPLE_VISIBLE_SECONDS}s`);
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

      rippleUniforms.fill(-1);
      const shaderRippleCount = Math.min(MAX_SHADER_RIPPLES, ripples.length);
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

        const age = elapsed - ripple.startTime;
        const offset = index * 4;
        rippleUniforms[offset] = ripple.x;
        rippleUniforms[offset + 1] = ripple.y;
        rippleUniforms[offset + 2] = ripple.startTime;
        rippleUniforms[offset + 3] = ripple.force;
      }

      gl.uniform2f(resolutionLocation, canvas.width, canvas.height);
      gl.uniform1f(timeLocation, elapsed);
      gl.uniform2f(pointerLocation, pointer.x * (canvas.width / width), pointer.y * (canvas.height / height));
      gl.uniform1f(pointerActiveLocation, pointer.active ? 1 : 0);
      gl.uniform4fv(ripplesLocation, rippleUniforms);
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

        if (distance > 20) {
        addRipple(
          nextX * (canvas.width / width),
          nextY * (canvas.height / height),
          Math.min(0.62, 0.16 + distance / 160),
        );
      }
    };

    // 메소드 설명: handlePointerLeave 처리 흐름을 수행합니다.
    const handlePointerLeave = () => {
      pointer.active = false;
      pointer.moved = false;
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
  }, []);

  return (
    <>
      <canvas ref={canvasRef} className="landing-constellation" aria-hidden="true" />
      <div ref={rippleLayerRef} className="landing-ripple-layer" aria-hidden="true" />
    </>
  );
}

// 메소드 설명: LandingPage 처리 흐름을 수행합니다.
export function LandingPage({ onEnter }) {
  return (
    <main className="landing">
      <LandingConstellation />
      <section className="hero" aria-labelledby="brand-title">
        <p className="kicker">law open data workspace</p>
        <h1 id="brand-title">pandora</h1>
        <button className="enter-button" type="button" onClick={onEnter}>
          <span>시작하기</span>
          <ArrowRight aria-hidden="true" size={16} strokeWidth={1.5} />
        </button>
      </section>
    </main>
  );
}
