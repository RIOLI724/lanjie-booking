<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

const canvas = ref<HTMLCanvasElement | null>(null)
let raf = 0
let disposed = false

type V3 = [number, number, number]

function perspective(fovy: number, aspect: number, near: number, far: number): Float32Array {
  const f = 1 / Math.tan(fovy / 2)
  const nf = 1 / (near - far)
  return new Float32Array([
    f / aspect, 0, 0, 0,
    0, f, 0, 0,
    0, 0, (far + near) * nf, -1,
    0, 0, 2 * far * near * nf, 0,
  ])
}

function lookAt(eye: V3, target: V3, up: V3): Float32Array {
  const zx = eye[0] - target[0], zy = eye[1] - target[1], zz = eye[2] - target[2]
  const zl = Math.hypot(zx, zy, zz) || 1
  const z: V3 = [zx / zl, zy / zl, zz / zl]
  let x: V3 = [up[1] * z[2] - up[2] * z[1], up[2] * z[0] - up[0] * z[2], up[0] * z[1] - up[1] * z[0]]
  const xl = Math.hypot(x[0], x[1], x[2]) || 1
  x = [x[0] / xl, x[1] / xl, x[2] / xl]
  const y: V3 = [z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0]]
  return new Float32Array([
    x[0], y[0], z[0], 0,
    x[1], y[1], z[1], 0,
    x[2], y[2], z[2], 0,
    -(x[0] * eye[0] + x[1] * eye[1] + x[2] * eye[2]),
    -(y[0] * eye[0] + y[1] * eye[1] + y[2] * eye[2]),
    -(z[0] * eye[0] + z[1] * eye[1] + z[2] * eye[2]),
    1,
  ])
}

function multiply(a: Float32Array, b: Float32Array): Float32Array {
  const out = new Float32Array(16)
  for (let i = 0; i < 4; i++) {
    for (let j = 0; j < 4; j++) {
      out[i * 4 + j] = a[j] * b[i * 4] + a[4 + j] * b[i * 4 + 1] + a[8 + j] * b[i * 4 + 2] + a[12 + j] * b[i * 4 + 3]
    }
  }
  return out
}

const HEX = (hex: string): V3 => {
  const n = parseInt(hex.slice(1), 16)
  return [(n >> 16) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255]
}

const TRI: number[] = []
const LINE: number[] = []

function tri(a: V3, b: V3, c: V3, color: V3) {
  TRI.push(a[0], a[1], a[2], color[0], color[1], color[2])
  TRI.push(b[0], b[1], b[2], color[0], color[1], color[2])
  TRI.push(c[0], c[1], c[2], color[0], color[1], color[2])
}

function quad(a: V3, b: V3, c: V3, d: V3, color: V3) {
  tri(a, b, c, color)
  tri(a, c, d, color)
}

function line(a: V3, b: V3, color: V3) {
  LINE.push(a[0], a[1], a[2], color[0], color[1], color[2])
  LINE.push(b[0], b[1], b[2], color[0], color[1], color[2])
}

function box(cx: number, cy: number, cz: number, sx: number, sy: number, sz: number, color: V3) {
  const x0 = cx - sx / 2, x1 = cx + sx / 2
  const y0 = cy - sy / 2, y1 = cy + sy / 2
  const z0 = cz - sz / 2, z1 = cz + sz / 2
  quad([x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0], color)
  quad([x1, y0, z1], [x0, y0, z1], [x0, y1, z1], [x1, y1, z1], color)
  quad([x0, y0, z1], [x0, y0, z0], [x0, y1, z0], [x0, y1, z1], color)
  quad([x1, y0, z0], [x1, y0, z1], [x1, y1, z1], [x1, y1, z0], color)
  quad([x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1], color)
}

function arc(cx: number, cz: number, r: number, from: number, to: number, color: V3, y = 0.035, steps = 40) {
  let prev: V3 = [cx + r * Math.cos(from), y, cz + r * Math.sin(from)]
  for (let i = 1; i <= steps; i++) {
    const a = from + ((to - from) * i) / steps
    const p: V3 = [cx + r * Math.cos(a), y, cz + r * Math.sin(a)]
    line(prev, p, color)
    prev = p
  }
}

function circle(cx: number, cy: number, cz: number, r: number, color: V3, horizontal = true, steps = 40) {
  let prev: V3 = horizontal
    ? [cx + r, cy, cz]
    : [cx + r, cy, cz]
  for (let i = 1; i <= steps; i++) {
    const a = (Math.PI * 2 * i) / steps
    const p: V3 = horizontal ? [cx + r * Math.cos(a), cy, cz + r * Math.sin(a)] : [cx + r * Math.cos(a), cy + r * Math.sin(a), cz]
    line(prev, p, color)
    prev = p
  }
}

function buildScene() {
  const floorDark = HEX('#081a12')
  const floorBase = HEX('#0d2a1e')
  const courtBase = HEX('#12402d')
  const courtLight = HEX('#17533a')
  const lime = HEX('#c8f169')
  const white = HEX('#e9f2e6')
  const board = HEX('#dfe8df')
  const rim = HEX('#ff9b52')
  const pole = HEX('#10281d')

  // 场外地面：网格细分，做中心光感
  const halfX = 36, halfZ = 22, nx = 36, nz = 22
  for (let i = 0; i < nx; i++) {
    for (let j = 0; j < nz; j++) {
      const x0 = -halfX + (2 * halfX * i) / nx, x1 = -halfX + (2 * halfX * (i + 1)) / nx
      const z0 = -halfZ + (2 * halfZ * j) / nz, z1 = -halfZ + (2 * halfZ * (j + 1)) / nz
      const cx = (x0 + x1) / 2, cz = (z0 + z1) / 2
      const glow = Math.exp(-(cx * cx) / 700 - (cz * cz) / 260)
      const base = floorDark
      const c: V3 = [
        base[0] + (floorBase[0] - base[0]) * glow,
        base[1] + (floorBase[1] - base[1]) * glow,
        base[2] + (floorBase[2] - base[2]) * glow,
      ]
      quad([x0, 0, z0], [x1, 0, z0], [x1, 0, z1], [x0, 0, z1], c)
    }
  }

  // 球场板 28 x 15，网格做光感
  const cx0 = 14, cz0 = 7.5, gx = 28, gz = 15
  for (let i = 0; i < gx; i++) {
    for (let j = 0; j < gz; j++) {
      const x0 = -cx0 + (2 * cx0 * i) / gx, x1 = -cx0 + (2 * cx0 * (i + 1)) / gx
      const z0 = -cz0 + (2 * cz0 * j) / gz, z1 = -cz0 + (2 * cz0 * (j + 1)) / gz
      const mx = (x0 + x1) / 2, mz = (z0 + z1) / 2
      const glow = Math.max(0, 1 - Math.hypot(mx / 14, mz / 7.5) * 0.85)
      const c: V3 = [
        courtBase[0] + (courtLight[0] - courtBase[0]) * glow,
        courtBase[1] + (courtLight[1] - courtBase[1]) * glow,
        courtBase[2] + (courtLight[2] - courtBase[2]) * glow,
      ]
      quad([x0, 0.02, z0], [x1, 0.02, z0], [x1, 0.02, z1], [x0, 0.02, z1], c)
    }
  }

  // 球场线
  const y = 0.035
  line([-14, y, -7.5], [14, y, -7.5], lime)
  line([14, y, -7.5], [14, y, 7.5], lime)
  line([14, y, 7.5], [-14, y, 7.5], lime)
  line([-14, y, 7.5], [-14, y, -7.5], lime)
  line([0, y, -7.5], [0, y, 7.5], lime)
  circle(0, y, 0, 1.8, lime)

  // 两端禁区 + 罚球圈 + 三分弧
  for (const dir of [-1, 1]) {
    const baseX = dir * 14
    const keyX = dir * (14 - 5.8)
    line([baseX, y, -2.45], [keyX, y, -2.45], lime)
    line([keyX, y, -2.45], [keyX, y, 2.45], lime)
    line([keyX, y, 2.45], [baseX, y, 2.45], lime)
    arc(dir * (14 - 5.8), 0, 1.8, dir > 0 ? Math.PI / 2 : -Math.PI / 2, dir > 0 ? Math.PI * 1.5 : Math.PI / 2, lime)
    const hoopX = dir * (14 - 1.575)
    const from = dir > 0 ? Math.PI * 0.28 : Math.PI * 0.72
    const to = dir > 0 ? Math.PI * 1.72 : Math.PI * 0.28
    arc(hoopX, 0, 6.75, from, to, lime)
    line([baseX, y, -6.6], [dir * (14 - 6.6), y, -6.6], lime)
    line([baseX, y, 6.6], [dir * (14 - 6.6), y, 6.6], lime)
  }

  // 篮架：立柱 + 篮板 + 篮筐
  for (const dir of [-1, 1]) {
    box(dir * 15.2, 1.9, 0, 0.16, 3.8, 0.16, pole)
    box(dir * 15.2, 3.9, 0, 1.2, 0.08, 0.08, pole)
    box(dir * 14.35, 3.55, 0, 0.06, 1.05, 1.8, board)
    circle(dir * 14.02, 3.15, 0, 0.23, rim)
    line([dir * 14.35, 3.05, 0], [dir * 14.02, 3.15, 0], rim)
  }
}

function compile(gl: WebGLRenderingContext, vs: string, fs: string): WebGLProgram {
  const make = (type: number, src: string) => {
    const s = gl.createShader(type)!
    gl.shaderSource(s, src)
    gl.compileShader(s)
    return s
  }
  const p = gl.createProgram()!
  gl.attachShader(p, make(gl.VERTEX_SHADER, vs))
  gl.attachShader(p, make(gl.FRAGMENT_SHADER, fs))
  gl.linkProgram(p)
  return p
}

const VS = `
attribute vec3 aPos;
attribute vec3 aColor;
uniform mat4 uMVP;
uniform vec3 uCam;
varying vec3 vColor;
varying float vFog;
void main() {
  vColor = aColor;
  float d = length(aPos - uCam);
  vFog = clamp((d - 20.0) / 46.0, 0.0, 0.82);
  gl_Position = uMVP * vec4(aPos, 1.0);
}`

const FS = `
precision mediump float;
varying vec3 vColor;
varying float vFog;
void main() {
  vec3 fog = vec3(0.024, 0.07, 0.05);
  gl_FragColor = vec4(mix(vColor, fog, vFog), 1.0);
}`

function start(gl: WebGLRenderingContext, width: number, height: number) {
  buildScene()
  const program = compile(gl, VS, FS)
  const aPos = gl.getAttribLocation(program, 'aPos')
  const aColor = gl.getAttribLocation(program, 'aColor')
  const uMVP = gl.getUniformLocation(program, 'uMVP')
  const uCam = gl.getUniformLocation(program, 'uCam')

  const triBuffer = gl.createBuffer()
  const triCount = TRI.length / 6
  gl.bindBuffer(gl.ARRAY_BUFFER, triBuffer)
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(TRI), gl.STATIC_DRAW)

  const lineBuffer = gl.createBuffer()
  const lineCount = LINE.length / 6
  gl.bindBuffer(gl.ARRAY_BUFFER, lineBuffer)
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(LINE), gl.STATIC_DRAW)

  gl.enable(gl.DEPTH_TEST)
  gl.clearColor(0.024, 0.07, 0.05, 1)

  const render = (time: number) => {
    if (disposed) return
    const az = time * 0.00006 + 0.6
    const radius = 23
    const eye: V3 = [Math.cos(az) * radius, 9.5, Math.sin(az) * radius]
    const target: V3 = [0, 0.6, 0]
    const proj = perspective((42 * Math.PI) / 180, width / height, 0.1, 120)
    const view = lookAt(eye, target, [0, 1, 0])
    const mvp = multiply(proj, view)

    gl.viewport(0, 0, width, height)
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
    gl.useProgram(program)
    gl.uniformMatrix4fv(uMVP, false, mvp)
    gl.uniform3fv(uCam, eye)

    gl.bindBuffer(gl.ARRAY_BUFFER, triBuffer)
    gl.enableVertexAttribArray(aPos)
    gl.enableVertexAttribArray(aColor)
    gl.vertexAttribPointer(aPos, 3, gl.FLOAT, false, 24, 0)
    gl.vertexAttribPointer(aColor, 3, gl.FLOAT, false, 24, 12)
    gl.drawArrays(gl.TRIANGLES, 0, triCount)

    gl.bindBuffer(gl.ARRAY_BUFFER, lineBuffer)
    gl.vertexAttribPointer(aPos, 3, gl.FLOAT, false, 24, 0)
    gl.vertexAttribPointer(aColor, 3, gl.FLOAT, false, 24, 12)
    gl.drawArrays(gl.LINES, 0, lineCount)

    raf = requestAnimationFrame(render)
  }
  raf = requestAnimationFrame(render)
}

onMounted(() => {
  const el = canvas.value
  if (!el) return
  const gl = el.getContext('webgl', { antialias: true, alpha: false })
  if (!gl) return
  const resize = () => {
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    el.width = Math.floor(el.clientWidth * dpr)
    el.height = Math.floor(el.clientHeight * dpr)
  }
  resize()
  start(gl, el.width, el.height)
  window.addEventListener('resize', () => {
    resize()
    gl.viewport(0, 0, el.width, el.height)
  })
})

onUnmounted(() => {
  disposed = true
  cancelAnimationFrame(raf)
})
</script>

<template>
  <canvas ref="canvas" class="court-backdrop" aria-hidden="true"></canvas>
</template>

<style scoped>
.court-backdrop {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
}
</style>
