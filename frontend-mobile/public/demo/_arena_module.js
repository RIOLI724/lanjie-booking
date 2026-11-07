let pendingMode = null;
window.lanjieArena = { setMode: function (mode) { pendingMode = mode; } };

const arenaRoot = document.getElementById('lanjie-proto');
const screen = document.getElementById('arenaScreen');
const canvas = document.getElementById('arenaCanvas');
const labelsEl = document.getElementById('courtLabels');
const sheet = document.getElementById('arenaSheet');
const slotsEl = document.getElementById('arenaSlots');
const bar = document.getElementById('arenaBar');
const titleEl = document.getElementById('arenaTitle');
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
  if (pay && !pay.disabled) {
    showToast('已锁位 15 分钟；付款由你本人确认');
  }
});

document.getElementById('arenaClose').addEventListener('click', function () { closeSheet(true); });
document.getElementById('arenaGrab').addEventListener('click', function () { closeSheet(true); });

let THREE = null;
try {
  THREE = await import('https://cdn.jsdelivr.net/npm/three@0.169.0/build/three.module.js');
} catch (err) {
  console.error('Three.js failed to load', err);
  screen.classList.add('no-webgl');
  screen.insertAdjacentHTML('beforeend', '<div class="arena-fallback">3D 场景加载失败，请刷新页面</div>');
  window.lanjieArena = { setMode: function () {} };
}

if (THREE) init();

function init() {
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x06100B);
  scene.fog = new THREE.FogExp2(0x06100B, 0.02);

  const camera = new THREE.PerspectiveCamera(58, 1, 0.1, 120);
  const renderer = new THREE.WebGLRenderer({ canvas: canvas, antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.15;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;

  const courtZ = { 1: -4.95, 2: -1.65, 3: 1.65, 4: 4.95 };
  const courtMeshes = [];
  const players = [];
  const spots = [];

  function woodTexture() {
    const c = document.createElement('canvas');
    c.width = 256; c.height = 256;
    const g = c.getContext('2d');
    const grad = g.createLinearGradient(0, 0, 256, 0);
    grad.addColorStop(0, '#5E361E');
    grad.addColorStop(0.45, '#7A4A2A');
    grad.addColorStop(1, '#643A20');
    g.fillStyle = grad; g.fillRect(0, 0, 256, 256);
    for (let i = 0; i < 90; i++) {
      g.strokeStyle = 'rgba(26,12,5,' + (0.03 + Math.random() * 0.08) + ')';
      g.lineWidth = 1 + Math.random() * 2;
      const x = Math.random() * 256;
      g.beginPath();
      g.moveTo(x, 0);
      g.bezierCurveTo(x + 9, 70, x - 7, 170, x + 5, 256);
      g.stroke();
    }
    const t = new THREE.CanvasTexture(c);
    t.wrapS = t.wrapT = THREE.RepeatWrapping;
    t.repeat.set(2, 1);
    t.colorSpace = THREE.SRGBColorSpace;
    t.anisotropy = 4;
    return t;
  }

  const woodMat = new THREE.MeshStandardMaterial({ map: woodTexture(), roughness: 0.52, metalness: 0.08 });
  const darkMat = new THREE.MeshStandardMaterial({ color: 0x08130E, roughness: 0.88, metalness: 0.08 });
  const seatMat = new THREE.MeshStandardMaterial({ color: 0x17271F, roughness: 0.82, metalness: 0.08 });
  const steelMat = new THREE.MeshStandardMaterial({ color: 0x25362C, roughness: 0.34, metalness: 0.62 });
  const boardMat = new THREE.MeshPhysicalMaterial({ color: 0xEAF3E8, transparent: true, opacity: 0.24, roughness: 0.12, metalness: 0 });
  const rimMat = new THREE.MeshStandardMaterial({ color: 0xF5B942, roughness: 0.35, metalness: 0.55 });
  const lineMat = new THREE.LineBasicMaterial({ color: 0xE8F5D8, transparent: true, opacity: 0.72 });
  const limeLineMat = new THREE.LineBasicMaterial({ color: 0xCDFB5A, transparent: true, opacity: 0.6 });
  const playerMat = new THREE.MeshStandardMaterial({ color: 0x0E1C15, roughness: 0.82 });
  const jerseyMat = new THREE.MeshStandardMaterial({ color: 0x1E4A38, roughness: 0.7 });
  const ballMat = new THREE.MeshStandardMaterial({ color: 0xD9772E, roughness: 0.5 });

  // arena floor
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(11, 17), darkMat);
  floor.rotation.x = -Math.PI / 2;
  floor.position.y = 0;
  floor.receiveShadow = true;
  scene.add(floor);

  const glowMat = new THREE.MeshBasicMaterial({ color: 0xCDFB5A, transparent: true, opacity: 0.035, blending: THREE.AdditiveBlending, depthWrite: false });
  const line = function (pts) {
    const geo = new THREE.BufferGeometry().setFromPoints(pts.map(function (p) {
      return new THREE.Vector3(p[0], 0.045, p[1]);
    }));
    const l = new THREE.Line(geo, lineMat);
    scene.add(l);
    return l;
  };

  Object.keys(courtZ).forEach(function (key) {
    const court = Number(key);
    const cz = courtZ[court];

    const glow = new THREE.Mesh(new THREE.PlaneGeometry(5.6, 3.3), glowMat);
    glow.rotation.x = -Math.PI / 2;
    glow.position.set(0, 0.012, cz);
    scene.add(glow);

    const courtFloor = new THREE.Mesh(new THREE.PlaneGeometry(4.8, 2.7), woodMat);
    courtFloor.rotation.x = -Math.PI / 2;
    courtFloor.position.set(0, 0.02, cz);
    courtFloor.receiveShadow = true;
    courtFloor.userData.court = court;
    scene.add(courtFloor);
    courtMeshes.push(courtFloor);

    line([[-2.4, cz - 1.35], [2.4, cz - 1.35], [2.4, cz + 1.35], [-2.4, cz + 1.35], [-2.4, cz - 1.35]]);
    line([[0, cz - 1.35], [0, cz + 1.35]], limeLineMat);

    const ring = new THREE.Mesh(new THREE.RingGeometry(0.43, 0.46, 40), new THREE.MeshBasicMaterial({ color: 0xE8F5D8, transparent: true, opacity: 0.7, side: THREE.DoubleSide }));
    ring.rotation.x = -Math.PI / 2;
    ring.position.set(0, 0.045, cz);
    scene.add(ring);

    line([[1.35, cz - 0.5], [2.4, cz - 0.5], [2.4, cz + 0.5], [1.35, cz + 0.5], [1.35, cz - 0.5]]);
    line([[-1.35, cz - 0.5], [-2.4, cz - 0.5], [-2.4, cz + 0.5], [-1.35, cz + 0.5], [-1.35, cz - 0.5]]);

    const ft1 = new THREE.Mesh(new THREE.RingGeometry(0.38, 0.41, 28), new THREE.MeshBasicMaterial({ color: 0xE8F5D8, transparent: true, opacity: 0.55, side: THREE.DoubleSide }));
    ft1.rotation.x = -Math.PI / 2;
    ft1.position.set(1.35, 0.045, cz);
    scene.add(ft1);
    const ft2 = ft1.clone();
    ft2.position.x = -1.35;
    scene.add(ft2);

    [-1, 1].forEach(function (side) {
      const g = new THREE.Group();
      const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.045, 0.045, 1.1, 8), steelMat);
      pole.position.set(side * 2.62, 0.55, cz);
      pole.castShadow = true;
      g.add(pole);
      const arm = new THREE.Mesh(new THREE.BoxGeometry(0.3, 0.045, 0.045), steelMat);
      arm.position.set(side * 2.48, 1.0, cz);
      g.add(arm);
      const board = new THREE.Mesh(new THREE.BoxGeometry(0.04, 0.48, 0.68), boardMat);
      board.position.set(side * 2.4, 1.05, cz);
      g.add(board);
      const edges = new THREE.LineSegments(new THREE.EdgesGeometry(board.geometry), limeLineMat);
      edges.position.copy(board.position);
      g.add(edges);
      const rim = new THREE.Mesh(new THREE.TorusGeometry(0.15, 0.012, 8, 24), rimMat);
      rim.position.set(side * 2.26, 0.92, cz);
      rim.rotation.x = Math.PI / 2;
      g.add(rim);
      const net = new THREE.Mesh(new THREE.CylinderGeometry(0.15, 0.1, 0.3, 8, 1, true), new THREE.MeshBasicMaterial({ color: 0xE8F5D8, wireframe: true, transparent: true, opacity: 0.26 }));
      net.position.set(side * 2.26, 0.75, cz);
      g.add(net);
      scene.add(g);
    });
  });

  // stands
  const seatPositions = [];
  const limePositions = [];
  [-1, 1].forEach(function (side) {
    for (let row = 0; row < 3; row++) {
      const box = new THREE.Mesh(new THREE.BoxGeometry(0.55, 0.32, 15), seatMat);
      box.position.set(side * (4.75 + row * 0.42), 0.16 + row * 0.32, 0);
      box.receiveShadow = true;
      scene.add(box);
      for (let i = 0; i < 54; i++) {
        const z = -7.3 + i * 0.275;
        const arr = Math.random() < 0.12 ? limePositions : seatPositions;
        arr.push(side * (4.75 + row * 0.42), 0.36 + row * 0.32, z);
      }
    }
  });
  for (let row = 0; row < 2; row++) {
    const box = new THREE.Mesh(new THREE.BoxGeometry(9.5, 0.3, 0.5), seatMat);
    box.position.set(0, 0.15 + row * 0.3, -7.9);
    scene.add(box);
    for (let i = 0; i < 48; i++) seatPositions.push(-4.6 + i * 0.195, 0.32 + row * 0.3, -7.9);
  }
  const seatGeo = new THREE.BufferGeometry();
  seatGeo.setAttribute('position', new THREE.Float32BufferAttribute(seatPositions, 3));
  scene.add(new THREE.Points(seatGeo, new THREE.PointsMaterial({ size: 0.055, color: 0x9FB8A8, transparent: true, opacity: 0.55, depthWrite: false })));
  const limeGeo = new THREE.BufferGeometry();
  limeGeo.setAttribute('position', new THREE.Float32BufferAttribute(limePositions, 3));
  scene.add(new THREE.Points(limeGeo, new THREE.PointsMaterial({ size: 0.06, color: 0xCDFB5A, transparent: true, opacity: 0.7, depthWrite: false })));

  // lights
  scene.add(new THREE.AmbientLight(0x89A291, 0.3));
  scene.add(new THREE.HemisphereLight(0xDFF7B0, 0x06100B, 0.42));
  Object.keys(courtZ).forEach(function (key) {
    const cz = courtZ[Number(key)];
    const s = new THREE.SpotLight(0xFFF2D8, 145, 18, 0.55, 0.55, 1.2);
    s.position.set(0, 6.8, cz);
    s.target.position.set(0, 0, cz);
    s.castShadow = true;
    s.shadow.mapSize.set(512, 512);
    s.shadow.bias = -0.0004;
    scene.add(s); scene.add(s.target); spots.push(s);
    const disc = new THREE.Mesh(new THREE.CylinderGeometry(0.32, 0.32, 0.07, 20), new THREE.MeshBasicMaterial({ color: 0xFFF6E4 }));
    disc.position.set(0, 6.85, cz);
    scene.add(disc);
    const beam = new THREE.Mesh(
      new THREE.ConeGeometry(2.1, 6.7, 24, 1, true),
      new THREE.MeshBasicMaterial({ color: 0xF4FFD0, transparent: true, opacity: 0.02, side: THREE.DoubleSide, depthWrite: false, blending: THREE.AdditiveBlending })
    );
    beam.position.set(0, 3.4, cz);
    scene.add(beam);
  });
  const limeLight = new THREE.PointLight(0xCDFB5A, 16, 14, 2);
  limeLight.position.set(-4, 3.2, 5);
  scene.add(limeLight);

  // players + ball
  function addPlayer(x, z, phase, jersey) {
    const g = new THREE.Group();
    const body = new THREE.Mesh(new THREE.CylinderGeometry(0.055, 0.075, 0.3, 6), playerMat);
    body.position.y = 0.15; body.castShadow = true; g.add(body);
    const head = new THREE.Mesh(new THREE.SphereGeometry(0.07, 8, 8), playerMat);
    head.position.y = 0.35; head.castShadow = true; g.add(head);
    const jerseyMesh = new THREE.Mesh(new THREE.CylinderGeometry(0.062, 0.07, 0.11, 6), jersey ? jerseyMat : playerMat);
    jerseyMesh.position.y = 0.24; g.add(jerseyMesh);
    g.position.set(x, 0, z);
    g.userData.phase = phase;
    scene.add(g);
    players.push(g);
  }
  addPlayer(-0.9, courtZ[1] - 0.4, 0.2, true);
  addPlayer(0.6, courtZ[1] + 0.45, 1.4, false);
  addPlayer(1.1, courtZ[1] - 0.3, 2.1, true);
  addPlayer(-1.2, courtZ[2] + 0.3, 0.8, false);
  addPlayer(0.35, courtZ[2] - 0.5, 2.8, true);
  addPlayer(-0.6, courtZ[3] - 0.4, 1.1, true);
  addPlayer(1.2, courtZ[3] + 0.2, 2.4, false);
  addPlayer(-1.1, courtZ[4] + 0.4, 0.4, false);
  addPlayer(0.4, courtZ[4] - 0.3, 1.8, true);
  addPlayer(1.3, courtZ[4] + 0.35, 3.1, true);

  const ball = new THREE.Mesh(new THREE.SphereGeometry(0.09, 16, 16), ballMat);
  ball.castShadow = true;
  ball.position.set(1.55, 0.09, courtZ[4] - 0.25);
  scene.add(ball);

  // labels
  const labelStatus = function (court) {
    const data = (GRID['11-06'] || { booked: {}, held: {} });
    const booked = (data.booked[String(court)] || []).concat(data.held[String(court)] || []);
    const evening = HOURS.filter(function (h) { return h >= 18; });
    const freeEvening = evening.filter(function (h) { return booked.indexOf(h) < 0; });
    if (!freeEvening.length) {
      const freeDay = HOURS.filter(function (h) { return h < 18 && booked.indexOf(h) < 0; });
      return freeDay.length ? '白天可订' : '已订满';
    }
    return freeEvening[0] + ':00 后空';
  };
  const labelEls = {};
  [1, 2, 3, 4].forEach(function (court) {
    const b = document.createElement('button');
    b.className = 'court-label';
    b.type = 'button';
    b.innerHTML = '<i></i><span><b>' + court + ' 号场</b><small>' + labelStatus(court) + '</small></span>';
    if (labelStatus(court) === '已订满') b.classList.add('busy');
    b.addEventListener('click', function (e) { e.stopPropagation(); selectCourt(court); });
    labelsEl.appendChild(b);
    labelEls[court] = b;
  });

  // camera
  const view = { az: 0.78, pol: 0.95, dist: 21, target: new THREE.Vector3(0, 0.2, 0) };
  const defaultView = { az: 0.78, pol: 0.95, dist: 21 };
  let fly = null;
  let intro = { t: 0, dur: 1.6, from: { az: 0.5, pol: 1.12, dist: 26 }, to: { az: 0.78, pol: 0.95, dist: 21 } };
  const pointers = new Map();
  let dragging = false;
  let lastX = 0, lastY = 0, downX = 0, downY = 0, moved = false, pinchDist = 0;

  function applyCamera() {
    const sp = Math.sin(view.pol), cp = Math.cos(view.pol);
    camera.position.set(
      view.target.x + view.dist * sp * Math.sin(view.az),
      view.target.y + view.dist * cp,
      view.target.z + view.dist * sp * Math.cos(view.az)
    );
    camera.lookAt(view.target);
  }

  function defaultPos() {
    const sp = Math.sin(defaultView.pol), cp = Math.cos(defaultView.pol);
    return new THREE.Vector3(
      defaultView.dist * sp * Math.sin(defaultView.az),
      0.2 + defaultView.dist * cp,
      defaultView.dist * sp * Math.cos(defaultView.az)
    );
  }

  function flyTo(pos, target, dur) {
    fly = {
      fromP: camera.position.clone(),
      fromT: view.target.clone(),
      toP: pos.clone(),
      toT: target.clone(),
      t: 0,
      dur: dur || 0.9
    };
  }

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
    flyTo(new THREE.Vector3(0, 7.2, cz + 5.2), new THREE.Vector3(0, 0.2, cz), 0.95);
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
    view.target.set(0, 0.2, 0);
    intro = null;
    flyTo(defaultPos(), new THREE.Vector3(0, 0.2, 0), 0.95);
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
  if (pendingMode === 'court') selectCourt(1);
  pendingMode = null;

  // interaction
  canvas.addEventListener('pointerdown', function (e) {
    if (sheet.classList.contains('open')) return;
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 1) {
      dragging = true;
      moved = false;
      lastX = e.clientX;
      lastY = e.clientY;
      downX = e.clientX;
      downY = e.clientY;
      intro = null;
      fly = null;
      canvas.setPointerCapture(e.pointerId);
    } else if (pointers.size === 2) {
      const pts = Array.from(pointers.values());
      pinchDist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
      dragging = false;
      moved = true;
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
    const dx = e.clientX - lastX;
    const dy = e.clientY - lastY;
    lastX = e.clientX;
    lastY = e.clientY;
    if (Math.abs(e.clientX - downX) + Math.abs(e.clientY - downY) > 6) moved = true;
    view.az -= dx * 0.006;
    view.pol = Math.max(0.45, Math.min(1.22, view.pol - dy * 0.004));
    applyCamera();
  });

  function releasePointer(e) {
    pointers.delete(e.pointerId);
    if (pointers.size < 2) pinchDist = 0;
    if (!dragging) return;
    dragging = false;
    if (!moved && !sheet.classList.contains('open')) {
      pickCourt(e.clientX, e.clientY);
    }
  }
  canvas.addEventListener('pointerup', releasePointer);
  canvas.addEventListener('pointercancel', releasePointer);

  canvas.addEventListener('wheel', function (e) {
    if (sheet.classList.contains('open')) return;
    e.preventDefault();
    view.dist = Math.max(11, Math.min(30, view.dist + e.deltaY * 0.012));
    applyCamera();
  }, { passive: false });

  const raycaster = new THREE.Raycaster();
  const ndc = new THREE.Vector2();
  function pickCourt(clientX, clientY) {
    const rect = canvas.getBoundingClientRect();
    ndc.x = ((clientX - rect.left) / rect.width) * 2 - 1;
    ndc.y = -((clientY - rect.top) / rect.height) * 2 + 1;
    raycaster.setFromCamera(ndc, camera);
    const hits = raycaster.intersectObjects(courtMeshes, false);
    if (hits.length) selectCourt(hits[0].object.userData.court);
  }

  function resize() {
    const w = screen.clientWidth;
    const h = screen.clientHeight;
    if (!w || !h) return;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }
  if (window.ResizeObserver) new ResizeObserver(resize).observe(screen);
  window.addEventListener('resize', resize);
  resize();

  const clock = new THREE.Clock();
  function updateLabels() {
    if (sheet.classList.contains('open')) return;
    const w = screen.clientWidth;
    const h = screen.clientHeight;
    if (!w || !h) return;
    [1, 2, 3, 4].forEach(function (court) {
      const el = labelEls[court];
      const v = new THREE.Vector3(0, 0.62, courtZ[court]).project(camera);
      const x = (v.x * 0.5 + 0.5) * w;
      const y = (-v.y * 0.5 + 0.5) * h;
      const visible = v.z < 1 && x > 26 && x < w - 26 && y > 128 && y < h - 174;
      el.style.display = visible ? 'flex' : 'none';
      el.style.transform = 'translate(-50%,-50%) translate(' + x + 'px,' + y + 'px)';
    });
  }

  function loop() {
    requestAnimationFrame(loop);
    const active = document.getElementById('view-arena').classList.contains('active');
    if (!active) return;
    const dt = Math.min(clock.getDelta(), 0.05);
    const t = clock.elapsedTime;

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
      camera.position.lerpVectors(fly.fromP, fly.toP, e);
      const tgt = new THREE.Vector3().lerpVectors(fly.fromT, fly.toT, e);
      camera.lookAt(tgt);
      if (k >= 1) {
        view.target.copy(fly.toT);
        camera.position.copy(fly.toP);
        camera.lookAt(fly.toT);
        fly = null;
      }
    }

    players.forEach(function (p) {
      p.position.y = Math.sin(t * 2 + p.userData.phase) * 0.006;
    });
    ball.position.y = 0.09 + Math.abs(Math.sin(t * 2.4)) * 0.45;
    ball.position.x = 1.55 + Math.sin(t * 0.6) * 0.25;

    spots.forEach(function (s, i) {
      s.intensity = 138 + Math.sin(t * 0.9 + i) * 10;
    });

    updateLabels();
    renderer.render(scene, camera);
  }

  applyCamera();
  updateLabels();
  loop();
}
