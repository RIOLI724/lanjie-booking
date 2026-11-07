let pendingMode = null;
window.lanjieArena = { setMode: function (mode) { pendingMode = mode; } };

const screen = document.getElementById('arenaScreen');
const canvas = document.getElementById('arenaCanvas');
const labelsEl = document.getElementById('courtLabels');
const sheet = document.getElementById('arenaSheet');
const slotsEl = document.getElementById('arenaSlots');
const bar = document.getElementById('arenaBar');
const toastEl = document.getElementById('arenaToast');

const D = window.LANJIE_DATA;
const DAYS = D.DAYS;
const HOURS = D.HOURS;
const GRID = D.GRID;
const priceFor = D.priceFor;

const state = { court: 1, day: '11-06', sel: [] };
let lastCourt = 1;
let toastTimer = 0;

function dayObj() {
  return DAYS.filter(function (d) { return d.key === state.day; })[0];
}

function dataFor(court) {
  const d = GRID[state.day] || { booked: {}, held: {} };
  return {
    booked: d.booked[String(court)] || [],
    held: d.held[String(court)] || []
  };
}

function renderSheet() {
  const day = dayObj();
  const data = dataFor(state.court);
  const free = HOURS.length - data.booked.length - data.held.length;
  document.getElementById('arenaCourtNo').textContent = '0' + state.court;
  document.getElementById('arenaCourtName').textContent = state.court + ' 号场';
  document.getElementById('arenaCourtMeta').textContent = day.label + ' 还剩 ' + free + ' 个时段 · 4 片全场';
  document.getElementById('arenaDayPrice').textContent = '¥' + (day.weekend ? 140 : 120) + '/时';
  document.querySelectorAll('[data-arena-day]').forEach(function (b) {
    b.classList.toggle('active', b.dataset.arenaDay === state.day);
  });
  let html = '';
  HOURS.forEach(function (h) {
    const booked = data.booked.indexOf(h) >= 0;
    const held = data.held.indexOf(h) >= 0;
    const sel = state.sel.indexOf(h) >= 0;
    const price = priceFor(day, h);
    const cls = booked ? 'booked' : held ? 'held' : sel ? 'sel' : '';
    const status = booked ? '已订' : held ? '待支付' : '空';
    html += '<button class="arena-slot ' + cls + '" data-arena-hour="' + h + '"' +
      (booked || held ? ' disabled' : '') + ' aria-pressed="' + (sel ? 'true' : 'false') + '">' +
      '<b>' + h + ':00</b><small>¥' + price + ' · ' + status + '</small></button>';
  });
  slotsEl.innerHTML = html;
  renderBar();
}

function renderBar() {
  const day = dayObj();
  const title = bar.querySelector('.bb-title');
  const sub = bar.querySelector('.bb-sub');
  const price = bar.querySelector('.bb-price');
  const btn = bar.querySelector('.bb-btn');
  const hours = state.sel.slice().sort(function (a, b) { return a - b; });
  if (!hours.length) {
    title.textContent = '选一个时段';
    sub.textContent = '点上面的时段，可连选';
    price.textContent = '—';
    btn.disabled = true;
    return;
  }
  const total = hours.reduce(function (s, h) { return s + priceFor(day, h); }, 0);
  const contiguous = hours.every(function (h, i) { return i === 0 || h === hours[i - 1] + 1; });
  const range = contiguous
    ? hours[0] + ':00–' + (hours[hours.length - 1] + 1) + ':00'
    : hours.map(function (h) { return h + ':00'; }).join('、');
  title.textContent = state.court + ' 号场 · ' + hours.length + ' 小时';
  sub.textContent = day.date + ' ' + day.week + ' · ' + range;
  price.textContent = '¥' + total;
  btn.disabled = false;
}

function showToast(msg) {
  toastEl.textContent = msg;
  toastEl.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(function () { toastEl.classList.remove('show'); }, 2600);
}

sheet.addEventListener('click', function (e) {
  const dayBtn = e.target.closest('[data-arena-day]');
  if (dayBtn) {
    state.day = dayBtn.dataset.arenaDay;
    state.sel = [];
    renderSheet();
    return;
  }
  const slot = e.target.closest('[data-arena-hour]');
  if (slot && !slot.disabled) {
    const h = Number(slot.dataset.arenaHour);
    const i = state.sel.indexOf(h);
    if (i >= 0) state.sel.splice(i, 1);
    else state.sel.push(h);
    renderSheet();
    return;
  }
  const sug = e.target.closest('[data-arena-suggest]');
  if (sug) {
    state.day = '11-07';
    state.sel = [20, 21];
    renderSheet();
    return;
  }
  const pay = e.target.closest('.bb-btn');
  if (pay && !pay.disabled) showToast('已锁位 15 分钟；付款由你本人确认');
});

const gl = canvas.getContext('webgl', { antialias: true, alpha: false });
if (!gl) {
  screen.classList.add('no-webgl');
  screen.insertAdjacentHTML('beforeend', '<div class="arena-fallback">这个浏览器没有开启 WebGL</div>');
  window.lanjieArena = { setMode: function () {} };
} else {
  initArena();
}

function initArena() {
  const courtZ = { 1: -4.95, 2: -1.65, 3: 1.65, 4: 4.95 };
  const FOV = 58;
  let aspect = 1;
  let proj = perspective(FOV, aspect, 0.1, 120);
  let viewMat = null;
  const camera = { eye: [11, 12, 11], target: [0, 0.2, 0] };
  let cameraUp = [0, 1, 0];
  const view = { az: 0.72, pol: 1.08, dist: 18.5, target: [0, 0.8, 0] };
  const defaultView = { az: 0.72, pol: 1.08, dist: 18.5 };
  let fly = null;
  let intro = { t: 0, dur: 1.6, from: { az: 0.5, pol: 1.2, dist: 25 }, to: { az: 0.72, pol: 1.08, dist: 18.5 } };
  let lastTime = performance.now();

  const C = {
    bg: [0.024, 0.063, 0.043],
    arena: [0.031, 0.075, 0.055],
    wood1: [0.63, 0.39, 0.23],
    wood2: [0.56, 0.34, 0.20],
    wood3: [0.69, 0.43, 0.25],
    line: [0.91, 0.96, 0.85],
    lime: [0.804, 0.984, 0.353],
    steel: [0.145, 0.215, 0.175],
    board: [0.82, 0.89, 0.83],
    rim: [0.96, 0.72, 0.26],
    stand: [0.09, 0.15, 0.12],
    jerseyA: [0.56, 0.72, 0.31],
    jerseyB: [0.13, 0.36, 0.27],
    jerseyC: [0.84, 0.87, 0.81],
    shorts: [0.09, 0.14, 0.11],
    shoe: [0.87, 0.88, 0.84],
    hair: [0.045, 0.04, 0.035],
    skin1: [0.78, 0.57, 0.42],
    skin2: [0.62, 0.43, 0.31],
    skin3: [0.88, 0.68, 0.52],
    ball: [0.85, 0.47, 0.18],
    light: [1.0, 0.96, 0.86],
    lightEmi: [0.85, 0.72, 0.45]
  };

  // ---------- math ----------
  function perspective(fov, aspect, near, far) {
    const f = 1 / Math.tan(fov * Math.PI / 360);
    const nf = 1 / (near - far);
    return new Float32Array([
      f / aspect, 0, 0, 0,
      0, f, 0, 0,
      0, 0, (far + near) * nf, -1,
      0, 0, 2 * far * near * nf, 0
    ]);
  }
  function vsub(a, b) { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
  function vcross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
  function vdot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
  function vnorm(a) {
    const l = Math.hypot(a[0], a[1], a[2]) || 1;
    return [a[0] / l, a[1] / l, a[2] / l];
  }
  function lookAt(eye, center, up) {
    const z = vnorm(vsub(eye, center));
    const x = vnorm(vcross(up, z));
    const y = vcross(z, x);
    return new Float32Array([
      x[0], y[0], z[0], 0,
      x[1], y[1], z[1], 0,
      x[2], y[2], z[2], 0,
      -vdot(x, eye), -vdot(y, eye), -vdot(z, eye), 1
    ]);
  }
  function mul(a, b) {
    const out = new Float32Array(16);
    for (let c = 0; c < 4; c++) {
      for (let r = 0; r < 4; r++) {
        out[c * 4 + r] =
          a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] +
          a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
      }
    }
    return out;
  }
  function translation(x, y, z) {
    const m = new Float32Array(16);
    m[0] = 1; m[5] = 1; m[10] = 1; m[15] = 1;
    m[12] = x; m[13] = y; m[14] = z;
    return m;
  }
  function projectPoint(x, y, z) {
    const m = mul(proj, lookAt(camera.eye, camera.target, cameraUp));
    const X = m[0] * x + m[4] * y + m[8] * z + m[12];
    const Y = m[1] * x + m[5] * y + m[9] * z + m[13];
    const W = m[3] * x + m[7] * y + m[11] * z + m[15];
    if (W <= 0) return null;
    return { x: (X / W * 0.5 + 0.5) * screen.clientWidth, y: (-Y / W * 0.5 + 0.5) * screen.clientHeight, w: W };
  }

  // ---------- geometry builders ----------
  function Builder() { this.p = []; this.n = []; this.c = []; this.e = []; this.i = []; }
  Builder.prototype.vert = function (x, y, z, nx, ny, nz, col, emi) {
    const idx = this.p.length / 3;
    this.p.push(x, y, z);
    this.n.push(nx, ny, nz);
    this.c.push(col[0], col[1], col[2]);
    const e = emi || [0, 0, 0];
    this.e.push(e[0], e[1], e[2]);
    return idx;
  };
  Builder.prototype.quad = function (a, b, c, d, col, emi, normal) {
    let nx, ny, nz;
    if (normal) {
      nx = normal[0]; ny = normal[1]; nz = normal[2];
    } else {
      const u = vsub(b, a), v = vsub(d, a);
      const n = vnorm(vcross(u, v));
      nx = n[0]; ny = n[1]; nz = n[2];
    }
    const i0 = this.vert(a[0], a[1], a[2], nx, ny, nz, col, emi);
    const i1 = this.vert(b[0], b[1], b[2], nx, ny, nz, col, emi);
    const i2 = this.vert(c[0], c[1], c[2], nx, ny, nz, col, emi);
    const i3 = this.vert(d[0], d[1], d[2], nx, ny, nz, col, emi);
    this.i.push(i0, i1, i2, i0, i2, i3);
  };
  Builder.prototype.box = function (cx, cy, cz, w, h, d, col, emi) {
    const x0 = cx - w / 2, x1 = cx + w / 2;
    const y0 = cy - h / 2, y1 = cy + h / 2;
    const z0 = cz - d / 2, z1 = cz + d / 2;
    this.quad([x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1], col, emi, [1, 0, 0]);
    this.quad([x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0], col, emi, [-1, 0, 0]);
    this.quad([x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0], col, emi, [0, 1, 0]);
    this.quad([x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1], col, emi, [0, -1, 0]);
    this.quad([x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1], col, emi, [0, 0, 1]);
    this.quad([x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0], col, emi, [0, 0, -1]);
  };
  Builder.prototype.cylinder = function (cx, cy, cz, r, h, seg, col, emi) {
    const y0 = cy - h / 2, y1 = cy + h / 2;
    for (let i = 0; i < seg; i++) {
      const a0 = i / seg * Math.PI * 2;
      const a1 = (i + 1) / seg * Math.PI * 2;
      const x0 = cx + Math.cos(a0) * r, z0 = cz + Math.sin(a0) * r;
      const x1 = cx + Math.cos(a1) * r, z1 = cz + Math.sin(a1) * r;
      const n0 = [Math.cos(a0), 0, Math.sin(a0)];
      const n1 = [Math.cos(a1), 0, Math.sin(a1)];
      const n = vnorm([n0[0] + n1[0], 0, n0[2] + n1[2]]);
      this.quad([x0, y0, z0], [x1, y0, z1], [x1, y1, z1], [x0, y1, z0], col, emi, n);
      const t0 = this.vert(cx, y1, cz, 0, 1, 0, col, emi);
      const t1 = this.vert(x0, y1, z0, 0, 1, 0, col, emi);
      const t2 = this.vert(x1, y1, z1, 0, 1, 0, col, emi);
      this.i.push(t0, t1, t2);
      const b0 = this.vert(cx, y0, cz, 0, -1, 0, col, emi);
      const b1 = this.vert(x1, y0, z1, 0, -1, 0, col, emi);
      const b2 = this.vert(x0, y0, z0, 0, -1, 0, col, emi);
      this.i.push(b0, b1, b2);
    }
  };
  Builder.prototype.sphere = function (cx, cy, cz, r, seg, rings, col, emi) {
    const index = [];
    for (let j = 0; j <= rings; j++) {
      const phi = j / rings * Math.PI;
      const y = Math.cos(phi) * r;
      const rr = Math.sin(phi) * r;
      for (let i = 0; i <= seg; i++) {
        const theta = i / seg * Math.PI * 2;
        const x = Math.cos(theta) * rr;
        const z = Math.sin(theta) * rr;
        const n = vnorm([x, y, z]);
        index.push(this.vert(cx + x, cy + y, cz + z, n[0], n[1], n[2], col, emi));
      }
    }
    for (let j = 0; j < rings; j++) {
      for (let i = 0; i < seg; i++) {
        const a = j * (seg + 1) + i;
        const b = a + seg + 1;
        this.i.push(a, b, a + 1, a + 1, b, b + 1);
      }
    }
  };
  Builder.prototype.torus = function (cx, cy, cz, R, r, seg, ring, col, emi) {
    const base = this.p.length / 3;
    for (let i = 0; i <= seg; i++) {
      const a = i / seg * Math.PI * 2;
      const ca = Math.cos(a), sa = Math.sin(a);
      for (let j = 0; j <= ring; j++) {
        const b = j / ring * Math.PI * 2;
        const cb = Math.cos(b), sb = Math.sin(b);
        const nx = ca * cb, ny = sb, nz = sa * cb;
        const x = cx + ca * (R + r * cb);
        const y = cy + r * sb;
        const z = cz + sa * (R + r * cb);
        this.vert(x, y, z, nx, ny, nz, col, emi);
      }
    }
    for (let i = 0; i < seg; i++) {
      for (let j = 0; j < ring; j++) {
        const a = base + i * (ring + 1) + j;
        const b = a + ring + 1;
        this.i.push(a, b, a + 1, a + 1, b, b + 1);
      }
    }
  };
  function upload(b) {
    const pos = new Float32Array(b.p);
    const nrm = new Float32Array(b.n);
    const col = new Float32Array(b.c);
    const emi = new Float32Array(b.e);
    const idx = new Uint16Array(b.i);
    function buf(data, target) {
      const x = gl.createBuffer();
      gl.bindBuffer(target, x);
      gl.bufferData(target, data, gl.STATIC_DRAW);
      return x;
    }
    return {
      pos: buf(pos, gl.ARRAY_BUFFER),
      nrm: buf(nrm, gl.ARRAY_BUFFER),
      col: buf(col, gl.ARRAY_BUFFER),
      emi: buf(emi, gl.ARRAY_BUFFER),
      idx: buf(idx, gl.ELEMENT_ARRAY_BUFFER),
      count: idx.length
    };
  }

  // ---------- shaders ----------
  function compile(type, src) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
    return s;
  }
  function program(vs, fs, attrs, uniforms) {
    const p = gl.createProgram();
    gl.attachShader(p, compile(gl.VERTEX_SHADER, vs));
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fs));
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    const out = { p: p };
    attrs.forEach(function (a) { out[a] = gl.getAttribLocation(p, a); });
    uniforms.forEach(function (u) { out[u] = gl.getUniformLocation(p, u); });
    return out;
  }

  const main = program(
    'attribute vec3 aPos; attribute vec3 aNormal; attribute vec3 aColor; attribute vec3 aEmi;' +
    'uniform mat4 uProj; uniform mat4 uView; uniform mat4 uModel; uniform vec3 uCam;' +
    'varying vec3 vColor; varying float vFog;' +
    'void main(){ vec4 w = uModel * vec4(aPos, 1.0); vec3 n = normalize(aNormal);' +
    ' float diff = max(dot(n, normalize(vec3(-0.35, 0.92, 0.4))), 0.0);' +
    ' float rim = pow(1.0 - max(dot(n, normalize(uCam - w.xyz)), 0.0), 2.0);' +
    ' vColor = aColor * (0.33 + 0.72 * diff) + aEmi + rim * 0.05;' +
    ' vFog = clamp((length(w.xyz - uCam) - 12.0) / 26.0, 0.0, 0.72);' +
    ' gl_Position = uProj * uView * w; }',
    'precision mediump float; varying vec3 vColor; varying float vFog;' +
    'void main(){ gl_FragColor = vec4(mix(vColor, vec3(0.024,0.063,0.043), vFog), 1.0); }',
    ['aPos', 'aNormal', 'aColor', 'aEmi'],
    ['uProj', 'uView', 'uModel', 'uCam']
  );

  const lineProg = program(
    'attribute vec3 aPos; attribute vec3 aColor; uniform mat4 uProj; uniform mat4 uView; varying vec3 vC;' +
    'void main(){ vC = aColor; gl_Position = uProj * uView * vec4(aPos, 1.0); }',
    'precision mediump float; varying vec3 vC; void main(){ gl_FragColor = vec4(vC, 1.0); }',
    ['aPos', 'aColor'],
    ['uProj', 'uView']
  );

  const pointProg = program(
    'attribute vec3 aPos; attribute vec3 aColor; uniform mat4 uProj; uniform mat4 uView; uniform float uSize; varying vec3 vC;' +
    'void main(){ vC = aColor; gl_Position = uProj * uView * vec4(aPos, 1.0); gl_PointSize = uSize; }',
    'precision mediump float; varying vec3 vC; void main(){ gl_FragColor = vec4(vC, 1.0); }',
    ['aPos', 'aColor'],
    ['uProj', 'uView', 'uSize']
  );

  const beamProg = program(
    'attribute vec3 aPos; uniform mat4 uProj; uniform mat4 uView;' +
    'void main(){ gl_Position = uProj * uView * vec4(aPos, 1.0); }',
    'precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor = uColor; }',
    ['aPos'],
    ['uProj', 'uView', 'uColor']
  );

  // ---------- scene ----------
  const B = new Builder();
  const L = { p: [], c: [] };
  const P = { p: [], c: [] };

  function line(a, b, col) {
    L.p.push(a[0], a[1], a[2], b[0], b[1], b[2]);
    L.c.push(col[0], col[1], col[2], col[0], col[1], col[2]);
  }
  function circle(cx, cz, r, y, col, seg) {
    for (let i = 0; i < seg; i++) {
      const a0 = i / seg * Math.PI * 2, a1 = (i + 1) / seg * Math.PI * 2;
      line([cx + Math.cos(a0) * r, y, cz + Math.sin(a0) * r], [cx + Math.cos(a1) * r, y, cz + Math.sin(a1) * r], col);
    }
  }
  function rect(x0, z0, x1, z1, y, col) {
    line([x0, y, z0], [x1, y, z0], col);
    line([x1, y, z0], [x1, y, z1], col);
    line([x1, y, z1], [x0, y, z1], col);
    line([x0, y, z1], [x0, y, z0], col);
  }
  function seat(x, y, z, col) {
    P.p.push(x, y, z);
    P.c.push(col[0], col[1], col[2]);
  }

  // arena floor
  B.quad([-5.5, 0, -8.5], [-5.5, 0, 8.5], [5.5, 0, 8.5], [5.5, 0, -8.5], C.arena, [0, 0, 0], [0, 1, 0]);

  Object.keys(courtZ).forEach(function (key) {
    const court = Number(key);
    const cz = courtZ[court];
    for (let i = 0; i < 12; i++) {
      const x = -2.2 + i * 0.4;
      const col = i % 3 === 0 ? C.wood1 : i % 3 === 1 ? C.wood2 : C.wood3;
      B.quad([x - 0.2, 0.02, cz - 1.35], [x - 0.2, 0.02, cz + 1.35], [x + 0.2, 0.02, cz + 1.35], [x + 0.2, 0.02, cz - 1.35], col, [0, 0, 0], [0, 1, 0]);
    }
    rect(-2.4, cz - 1.35, 2.4, cz + 1.35, 0.045, C.line);
    line([0, 0.045, cz - 1.35], [0, 0.045, cz + 1.35], C.lime);
    circle(0, cz, 0.45, 0.045, C.line, 28);
    rect(1.35, cz - 0.5, 2.4, cz + 0.5, 0.045, C.line);
    rect(-2.4, cz - 0.5, -1.35, cz + 0.5, 0.045, C.line);
    circle(1.35, cz, 0.4, 0.045, C.line, 22);
    circle(-1.35, cz, 0.4, 0.045, C.line, 22);

    [-1, 1].forEach(function (side) {
      B.box(side * 2.62, 0.55, cz, 0.09, 1.1, 0.09, C.steel, [0.01, 0.015, 0.01]);
      B.box(side * 2.5, 1.0, cz, 0.3, 0.05, 0.05, C.steel, [0.01, 0.015, 0.01]);
      B.box(side * 2.4, 1.05, cz, 0.05, 0.48, 0.68, C.board, [0.06, 0.08, 0.065]);
      B.torus(side * 2.26, 0.92, cz, 0.15, 0.012, 14, 5, C.rim, [0.28, 0.14, 0.03]);
      line([side * 2.37, 0.81, cz - 0.34], [side * 2.37, 0.81, cz + 0.34], C.lime);
      line([side * 2.37, 0.81, cz + 0.34], [side * 2.37, 1.29, cz + 0.34], C.lime);
      line([side * 2.37, 1.29, cz + 0.34], [side * 2.37, 1.29, cz - 0.34], C.lime);
      line([side * 2.37, 1.29, cz - 0.34], [side * 2.37, 0.81, cz - 0.34], C.lime);
      for (let i = 0; i < 8; i++) {
        const a = i / 8 * Math.PI * 2;
        line([side * 2.26 + Math.cos(a) * 0.15, 0.905, cz + Math.sin(a) * 0.15],
             [side * 2.26 + Math.cos(a) * 0.1, 0.64, cz + Math.sin(a) * 0.1], [0.7, 0.76, 0.7]);
      }
    });
  });

  // stands
  [-1, 1].forEach(function (side) {
    for (let row = 0; row < 3; row++) {
      B.box(side * (4.75 + row * 0.42), 0.16 + row * 0.32, 0, 0.55, 0.32, 15, C.stand, [0.005, 0.008, 0.006]);
      for (let i = 0; i < 54; i++) {
        const z = -7.3 + i * 0.275;
        seat(side * (4.75 + row * 0.42), 0.36 + row * 0.32, z, Math.random() < 0.13 ? C.lime : [0.36, 0.46, 0.4]);
      }
    }
  });
  for (let row = 0; row < 2; row++) {
    B.box(0, 0.15 + row * 0.3, -7.9, 9.5, 0.3, 0.5, C.stand, [0.005, 0.008, 0.006]);
    for (let i = 0; i < 48; i++) seat(-4.6 + i * 0.195, 0.32 + row * 0.3, -7.9, [0.36, 0.46, 0.4]);
  }

  // light discs (overview only)
  const topB = new Builder();
  Object.keys(courtZ).forEach(function (key) {
    topB.cylinder(0, 6.85, courtZ[Number(key)], 0.32, 0.07, 20, C.light, C.lightEmi);
  });

  // far wall + suspended lights
  B.box(0, 3, -8.6, 11.2, 6, 0.3, C.stand, [0.004, 0.007, 0.005]);
  line([-5.4, 1.2, -8.44], [5.4, 1.2, -8.44], [0.42, 0.52, 0.43]);

  // players
  function playerMesh(jersey, shorts, skin) {
    const b = new Builder();
    b.cylinder(0, 0.004, 0, 0.045, 0.002, 10, [0.02, 0.03, 0.025], [0, 0, 0]);
    b.cylinder(-0.02, 0.08, 0, 0.013, 0.16, 6, skin, [0, 0, 0]);
    b.cylinder(0.02, 0.08, 0, 0.013, 0.16, 6, skin, [0, 0, 0]);
    b.box(-0.02, 0.015, 0.012, 0.036, 0.03, 0.06, C.shoe, [0.02, 0.02, 0.02]);
    b.box(0.02, 0.015, 0.012, 0.036, 0.03, 0.06, C.shoe, [0.02, 0.02, 0.02]);
    b.cylinder(0, 0.18, 0, 0.036, 0.06, 6, shorts, [0, 0, 0]);
    b.cylinder(0, 0.255, 0, 0.033, 0.11, 6, jersey, [0.01, 0.02, 0.015]);
    b.cylinder(-0.043, 0.245, 0, 0.01, 0.1, 6, skin, [0, 0, 0]);
    b.cylinder(0.043, 0.245, 0, 0.01, 0.1, 6, skin, [0, 0, 0]);
    b.cylinder(0, 0.313, 0, 0.012, 0.02, 6, skin, [0, 0, 0]);
    b.sphere(0, 0.335, 0, 0.027, 8, 6, skin, [0, 0, 0]);
    b.sphere(0, 0.347, 0.004, 0.024, 8, 6, C.hair, [0, 0, 0]);
    return upload(b);
  }
  const playerConfigs = [
    { x: -0.9, z: courtZ[1] - 0.4, phase: 0.2, jersey: C.jerseyA, shorts: C.shorts, skin: C.skin1 },
    { x: 0.6, z: courtZ[1] + 0.45, phase: 1.4, jersey: C.jerseyB, shorts: C.shorts, skin: C.skin2 },
    { x: 1.1, z: courtZ[1] - 0.3, phase: 2.1, jersey: C.jerseyC, shorts: C.shorts, skin: C.skin3 },
    { x: -1.2, z: courtZ[2] + 0.3, phase: 0.8, jersey: C.jerseyB, shorts: C.shorts, skin: C.skin1 },
    { x: 0.35, z: courtZ[2] - 0.5, phase: 2.8, jersey: C.jerseyA, shorts: C.shorts, skin: C.skin2 },
    { x: -0.6, z: courtZ[3] - 0.4, phase: 1.1, jersey: C.jerseyA, shorts: C.shorts, skin: C.skin3 },
    { x: 1.2, z: courtZ[3] + 0.2, phase: 2.4, jersey: C.jerseyB, shorts: C.shorts, skin: C.skin1 },
    { x: -1.1, z: courtZ[4] + 0.4, phase: 0.4, jersey: C.jerseyC, shorts: C.shorts, skin: C.skin2 },
    { x: 0.4, z: courtZ[4] - 0.3, phase: 1.8, jersey: C.jerseyA, shorts: C.shorts, skin: C.skin1 },
    { x: 1.3, z: courtZ[4] + 0.35, phase: 3.1, jersey: C.jerseyB, shorts: C.shorts, skin: C.skin3 }
  ];
  const players = playerConfigs.map(function (p) {
    return { mesh: playerMesh(p.jersey, p.shorts, p.skin), x: p.x, z: p.z, phase: p.phase };
  });

  const ballB = new Builder();
  ballB.sphere(0, 0, 0, 0.09, 12, 8, C.ball, [0.03, 0.015, 0.005]);
  const ballMesh = upload(ballB);
  const ball = { x: 1.55, y: 0.09, z: courtZ[4] - 0.25 };

  const staticMesh = upload(B);
  const topMesh = upload(topB);
  const lineMesh = (function () {
    const pos = new Float32Array(L.p), col = new Float32Array(L.c);
    function buf(d) { const x = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, x); gl.bufferData(gl.ARRAY_BUFFER, d, gl.STATIC_DRAW); return x; }
    return { pos: buf(pos), col: buf(col), count: pos.length / 3 };
  })();
  const pointMesh = (function () {
    const pos = new Float32Array(P.p), col = new Float32Array(P.c);
    function buf(d) { const x = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, x); gl.bufferData(gl.ARRAY_BUFFER, d, gl.STATIC_DRAW); return x; }
    return { pos: buf(pos), col: buf(col), count: pos.length / 3 };
  })();

  const wirePos = [], wireCol = [];
  Object.keys(courtZ).forEach(function (key) {
    wirePos.push(0, 6.88, courtZ[Number(key)], 0, 9.4, courtZ[Number(key)]);
    wireCol.push(0.22, 0.28, 0.24, 0.22, 0.28, 0.24);
  });
  const wireMesh = (function () {
    function buf(d) { const x = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, x); gl.bufferData(gl.ARRAY_BUFFER, d, gl.STATIC_DRAW); return x; }
    return { pos: buf(new Float32Array(wirePos)), col: buf(new Float32Array(wireCol)), count: wirePos.length / 3 };
  })();

  const beamPos = [];
  Object.keys(courtZ).forEach(function (key) {
    const cz = courtZ[Number(key)];
    const N = 12;
    for (let i = 0; i < N; i++) {
      const a0 = i / N * Math.PI * 2, a1 = (i + 1) / N * Math.PI * 2;
      const t0 = [Math.cos(a0) * 0.32, 6.8, cz + Math.sin(a0) * 0.32];
      const t1 = [Math.cos(a1) * 0.32, 6.8, cz + Math.sin(a1) * 0.32];
      const b0 = [Math.cos(a0) * 2.5, 0, cz + Math.sin(a0) * 2.5];
      const b1 = [Math.cos(a1) * 2.5, 0, cz + Math.sin(a1) * 2.5];
      beamPos.push(t0[0], t0[1], t0[2], b0[0], b0[1], b0[2], b1[0], b1[1], b1[2]);
      beamPos.push(t0[0], t0[1], t0[2], b1[0], b1[1], b1[2], t1[0], t1[1], t1[2]);
    }
  });
  const beamBuf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, beamBuf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(beamPos), gl.STATIC_DRAW);

  // ---------- camera ----------
  function eyeFromView() {
    const sp = Math.sin(view.pol), cp = Math.cos(view.pol);
    return [
      view.target[0] + view.dist * sp * Math.sin(view.az),
      view.target[1] + view.dist * cp,
      view.target[2] + view.dist * sp * Math.cos(view.az)
    ];
  }
  function applyCamera() {
    camera.eye = eyeFromView();
    camera.target = view.target.slice();
    cameraUp = [0, 1, 0];
  }
  function defaultPos() {
    const sp = Math.sin(defaultView.pol), cp = Math.cos(defaultView.pol);
    return [
      defaultView.dist * sp * Math.sin(defaultView.az),
      0.8 + defaultView.dist * cp,
      defaultView.dist * sp * Math.cos(defaultView.az)
    ];
  }
  function flyTo(eye, target, dur, up) {
    fly = {
      fromE: camera.eye.slice(), fromT: camera.target.slice(), fromUp: cameraUp.slice(),
      toE: eye.slice(), toT: target.slice(), toUp: (up || [0, 1, 0]).slice(),
      t: 0, dur: dur || 0.95
    };
  }

  // ---------- labels ----------
  function labelStatus(court) {
    const data = GRID['11-06'] || { booked: {}, held: {} };
    const booked = (data.booked[String(court)] || []).concat(data.held[String(court)] || []);
    const freeEvening = HOURS.filter(function (h) { return h >= 18 && booked.indexOf(h) < 0; });
    if (!freeEvening.length) {
      const freeDay = HOURS.filter(function (h) { return h < 18 && booked.indexOf(h) < 0; });
      return freeDay.length ? '白天可订' : '已订满';
    }
    return freeEvening[0] + ':00 后空';
  }
  const labelEls = {};
  [1, 2, 3, 4].forEach(function (court) {
    const b = document.createElement('button');
    b.className = 'court-label' + (labelStatus(court) === '已订满' ? ' busy' : '');
    b.type = 'button';
    b.innerHTML = '<i></i><span><b>' + court + ' 号场</b><small>' + labelStatus(court) + '</small></span>';
    b.addEventListener('click', function (e) { e.stopPropagation(); selectCourt(court); });
    labelsEl.appendChild(b);
    labelEls[court] = b;
  });

  // ---------- sheet / mode ----------
  function selectCourt(court) {
    if (sheet.classList.contains('open') && state.court === court) return;
    lastCourt = court;
    state.court = court;
    state.day = '11-06';
    state.sel = [];
    renderSheet();
    sheet.classList.add('open');
    sheet.setAttribute('aria-hidden', 'false');
    screen.classList.add('sheet-open');
    intro = null;
    const cz = courtZ[court];
    flyTo([0, 9.4, cz + 2.4], [0, 0.02, cz + 2.4], 0.95, [0, 0, -1]);
    window.dispatchEvent(new CustomEvent('lanjie:mode', { detail: { mode: 'court', court: court } }));
  }
  function closeSheet(dispatch) {
    if (!sheet.classList.contains('open')) return;
    sheet.classList.remove('open');
    sheet.setAttribute('aria-hidden', 'true');
    screen.classList.remove('sheet-open');
    view.az = defaultView.az;
    view.pol = defaultView.pol;
    view.dist = defaultView.dist;
    view.target = [0, 0.8, 0];
    intro = null;
    flyTo(defaultPos(), [0, 0.8, 0], 0.95, [0, 1, 0]);
    if (dispatch !== false) window.dispatchEvent(new CustomEvent('lanjie:mode', { detail: { mode: 'home' } }));
  }
  window.lanjieArena = {
    setMode: function (mode) {
      if (mode === 'court') {
        if (!sheet.classList.contains('open')) selectCourt(lastCourt || 1);
      } else {
        closeSheet(false);
      }
    }
  };
  document.getElementById('arenaClose').addEventListener('click', function () { closeSheet(true); });
  document.getElementById('arenaGrab').addEventListener('click', function () { closeSheet(true); });
  if (pendingMode === 'court') selectCourt(1);
  pendingMode = null;

  // ---------- interaction ----------
  const pointers = new Map();
  let dragging = false, lastX = 0, lastY = 0, downX = 0, downY = 0, moved = false, pinchDist = 0;

  canvas.addEventListener('pointerdown', function (e) {
    if (sheet.classList.contains('open')) return;
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 1) {
      dragging = true; moved = false;
      lastX = e.clientX; lastY = e.clientY;
      downX = e.clientX; downY = e.clientY;
      intro = null; fly = null;
      canvas.setPointerCapture(e.pointerId);
    } else if (pointers.size === 2) {
      const pts = Array.from(pointers.values());
      pinchDist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
      dragging = false; moved = true;
    }
  });
  canvas.addEventListener('pointermove', function (e) {
    if (!pointers.has(e.pointerId)) return;
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 2) {
      const pts = Array.from(pointers.values());
      const d = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
      if (pinchDist > 0) {
        view.dist = Math.max(11, Math.min(30, view.dist * (pinchDist / d)));
        applyCamera();
      }
      pinchDist = d;
      return;
    }
    if (!dragging) return;
    const dx = e.clientX - lastX, dy = e.clientY - lastY;
    lastX = e.clientX; lastY = e.clientY;
    if (Math.abs(e.clientX - downX) + Math.abs(e.clientY - downY) > 6) moved = true;
    view.az -= dx * 0.006;
    view.pol = Math.max(0.45, Math.min(1.22, view.pol - dy * 0.004));
    applyCamera();
  });
  function release(e) {
    pointers.delete(e.pointerId);
    if (pointers.size < 2) pinchDist = 0;
    if (!dragging) return;
    dragging = false;
    if (!moved && !sheet.classList.contains('open')) pickCourt(e.clientX, e.clientY);
  }
  canvas.addEventListener('pointerup', release);
  canvas.addEventListener('pointercancel', release);
  canvas.addEventListener('wheel', function (e) {
    if (sheet.classList.contains('open')) return;
    e.preventDefault();
    view.dist = Math.max(11, Math.min(30, view.dist + e.deltaY * 0.012));
    applyCamera();
  }, { passive: false });

  function pickCourt(clientX, clientY) {
    const rect = canvas.getBoundingClientRect();
    const ndcX = ((clientX - rect.left) / rect.width) * 2 - 1;
    const ndcY = -((clientY - rect.top) / rect.height) * 2 + 1;
    const fwd = vnorm(vsub(camera.target, camera.eye));
    const right = vnorm(vcross(fwd, cameraUp));
    const up = vcross(right, fwd);
    const th = Math.tan(FOV * Math.PI / 360);
    const dir = vnorm([
      fwd[0] + right[0] * ndcX * th * aspect + up[0] * ndcY * th,
      fwd[1] + right[1] * ndcX * th * aspect + up[1] * ndcY * th,
      fwd[2] + right[2] * ndcX * th * aspect + up[2] * ndcY * th
    ]);
    if (Math.abs(dir[1]) < 1e-4) return;
    const t = (0.02 - camera.eye[1]) / dir[1];
    if (t <= 0) return;
    const px = camera.eye[0] + dir[0] * t;
    const pz = camera.eye[2] + dir[2] * t;
    const found = [1, 2, 3, 4].filter(function (court) {
      const cz = courtZ[court];
      return px >= -2.4 && px <= 2.4 && pz >= cz - 1.35 && pz <= cz + 1.35;
    })[0];
    if (found) selectCourt(found);
  }

  function resize() {
    const w = screen.clientWidth, h = screen.clientHeight;
    if (!w || !h) return;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    gl.viewport(0, 0, canvas.width, canvas.height);
    aspect = w / h;
    proj = perspective(FOV, aspect, 0.1, 120);
  }
  if (window.ResizeObserver) new ResizeObserver(resize).observe(screen);
  window.addEventListener('resize', resize);
  resize();

  function updateLabels() {
    if (sheet.classList.contains('open')) return;
    const w = screen.clientWidth, h = screen.clientHeight;
    [1, 2, 3, 4].forEach(function (court) {
      const el = labelEls[court];
      const p = projectPoint(0, 0.62, courtZ[court]);
      if (!p) { el.style.display = 'none'; return; }
      const show = p.x > 26 && p.x < w - 26 && p.y > 128 && p.y < h - 174;
      el.style.display = show ? 'flex' : 'none';
      el.style.transform = 'translate(-50%,-50%) translate(' + p.x + 'px,' + p.y + 'px)';
    });
  }

  function bind(mesh) {
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.pos); gl.vertexAttribPointer(main.aPos, 3, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.nrm); gl.vertexAttribPointer(main.aNormal, 3, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.col); gl.vertexAttribPointer(main.aColor, 3, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.emi); gl.vertexAttribPointer(main.aEmi, 3, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, mesh.idx);
  }
  function drawMain(mesh, model) {
    bind(mesh);
    gl.uniformMatrix4fv(main.uModel, false, model);
    gl.drawElements(gl.TRIANGLES, mesh.count, gl.UNSIGNED_SHORT, 0);
  }
  function render() {
    gl.viewport(0, 0, canvas.width, canvas.height);
    gl.clearColor(C.bg[0], C.bg[1], C.bg[2], 1);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    gl.enable(gl.DEPTH_TEST);
    gl.disable(gl.CULL_FACE);

    viewMat = lookAt(camera.eye, camera.target, cameraUp);
    const vp = mul(proj, viewMat);
    const overview = !sheet.classList.contains('open');

    gl.useProgram(main.p);
    gl.uniformMatrix4fv(main.uProj, false, proj);
    gl.uniformMatrix4fv(main.uView, false, viewMat);
    gl.uniform3fv(main.uCam, camera.eye);
    gl.enableVertexAttribArray(main.aPos);
    gl.enableVertexAttribArray(main.aNormal);
    gl.enableVertexAttribArray(main.aColor);
    gl.enableVertexAttribArray(main.aEmi);
    drawMain(staticMesh, translation(0, 0, 0));
    if (overview) drawMain(topMesh, translation(0, 0, 0));
    if (overview) {
      players.forEach(function (p) {
        drawMain(p.mesh, translation(p.x, Math.sin(performance.now() * 0.002 + p.phase) * 0.006, p.z));
      });
    }
    drawMain(ballMesh, translation(ball.x, ball.y, ball.z));

    if (overview) {
      gl.useProgram(beamProg.p);
      gl.uniformMatrix4fv(beamProg.uProj, false, proj);
      gl.uniformMatrix4fv(beamProg.uView, false, viewMat);
      gl.uniform4f(beamProg.uColor, 0.72, 0.9, 0.55, 0.018);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE);
      gl.depthMask(false);
      gl.enableVertexAttribArray(beamProg.aPos);
      gl.bindBuffer(gl.ARRAY_BUFFER, beamBuf);
      gl.vertexAttribPointer(beamProg.aPos, 3, gl.FLOAT, false, 0, 0);
      gl.drawArrays(gl.TRIANGLES, 0, beamPos.length / 3);
      gl.depthMask(true);
      gl.disable(gl.BLEND);
    }

    gl.useProgram(lineProg.p);
    gl.uniformMatrix4fv(lineProg.uProj, false, proj);
    gl.uniformMatrix4fv(lineProg.uView, false, viewMat);
    gl.enableVertexAttribArray(lineProg.aPos);
    gl.enableVertexAttribArray(lineProg.aColor);
    gl.bindBuffer(gl.ARRAY_BUFFER, lineMesh.pos); gl.vertexAttribPointer(lineProg.aPos, 3, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, lineMesh.col); gl.vertexAttribPointer(lineProg.aColor, 3, gl.FLOAT, false, 0, 0);
    gl.drawArrays(gl.LINES, 0, lineMesh.count);
    if (overview) {
      gl.bindBuffer(gl.ARRAY_BUFFER, wireMesh.pos); gl.vertexAttribPointer(lineProg.aPos, 3, gl.FLOAT, false, 0, 0);
      gl.bindBuffer(gl.ARRAY_BUFFER, wireMesh.col); gl.vertexAttribPointer(lineProg.aColor, 3, gl.FLOAT, false, 0, 0);
      gl.drawArrays(gl.LINES, 0, wireMesh.count);
    }

    gl.useProgram(pointProg.p);
    gl.uniformMatrix4fv(pointProg.uProj, false, proj);
    gl.uniformMatrix4fv(pointProg.uView, false, viewMat);
    gl.uniform1f(pointProg.uSize, 3.0 * Math.min(window.devicePixelRatio || 1, 2));
    gl.enableVertexAttribArray(pointProg.aPos);
    gl.enableVertexAttribArray(pointProg.aColor);
    gl.bindBuffer(gl.ARRAY_BUFFER, pointMesh.pos); gl.vertexAttribPointer(pointProg.aPos, 3, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, pointMesh.col); gl.vertexAttribPointer(pointProg.aColor, 3, gl.FLOAT, false, 0, 0);
    gl.drawArrays(gl.POINTS, 0, pointMesh.count);
  }

  function loop() {
    requestAnimationFrame(loop);
    if (!document.getElementById('view-arena').classList.contains('active')) return;
    const now = performance.now();
    const dt = Math.min((now - lastTime) / 1000, 0.05);
    lastTime = now;
    const t = now / 1000;

    if (intro) {
      intro.t += dt / intro.dur;
      const k = Math.min(1, intro.t);
      const e = 1 - Math.pow(1 - k, 3);
      view.az = intro.from.az + (intro.to.az - intro.from.az) * e;
      view.pol = intro.from.pol + (intro.to.pol - intro.from.pol) * e;
      view.dist = intro.from.dist + (intro.to.dist - intro.from.dist) * e;
      applyCamera();
      if (k >= 1) intro = null;
    }
    if (fly) {
      fly.t += dt / fly.dur;
      const k = Math.min(1, fly.t);
      const e = k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
      camera.eye = [
        fly.fromE[0] + (fly.toE[0] - fly.fromE[0]) * e,
        fly.fromE[1] + (fly.toE[1] - fly.fromE[1]) * e,
        fly.fromE[2] + (fly.toE[2] - fly.fromE[2]) * e
      ];
      camera.target = [
        fly.fromT[0] + (fly.toT[0] - fly.fromT[0]) * e,
        fly.fromT[1] + (fly.toT[1] - fly.fromT[1]) * e,
        fly.fromT[2] + (fly.toT[2] - fly.fromT[2]) * e
      ];
      cameraUp = vnorm([
        fly.fromUp[0] + (fly.toUp[0] - fly.fromUp[0]) * e,
        fly.fromUp[1] + (fly.toUp[1] - fly.fromUp[1]) * e,
        fly.fromUp[2] + (fly.toUp[2] - fly.fromUp[2]) * e
      ]);
      if (k >= 1) fly = null;
    }

    ball.y = 0.09 + Math.abs(Math.sin(t * 2.4)) * 0.45;
    ball.x = 1.55 + Math.sin(t * 0.6) * 0.25;
    updateLabels();
    render();
  }

  applyCamera();
  updateLabels();
  loop();
}
