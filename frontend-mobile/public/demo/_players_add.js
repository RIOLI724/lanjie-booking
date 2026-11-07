  // player sprites (billboards)
  const spriteProg = program(
    'attribute vec2 aCorner; uniform mat4 uProj; uniform mat4 uView; uniform vec3 uCenter; uniform vec3 uRight; uniform vec3 uUp; uniform vec3 uCam; uniform float uW; uniform float uH; varying vec2 vUv; varying float vFog;' +
    'void main(){ vec3 world = uCenter + uRight * (aCorner.x * uW) + uUp * (aCorner.y * uH);' +
    ' vUv = vec2(aCorner.x * 0.5 + 0.5, 1.0 - aCorner.y);' +
    ' vFog = clamp((length(world - uCam) - 12.0) / 26.0, 0.0, 0.72);' +
    ' gl_Position = uProj * uView * vec4(world, 1.0); }',
    'precision mediump float; uniform sampler2D uTex; varying vec2 vUv; varying float vFog;' +
    'void main(){ vec4 c = texture2D(uTex, vUv); if (c.a < 0.35) discard;' +
    ' gl_FragColor = vec4(mix(c.rgb, vec3(0.024,0.063,0.043), vFog), 1.0); }',
    ['aCorner'],
    ['uProj', 'uView', 'uCenter', 'uRight', 'uUp', 'uCam', 'uW', 'uH', 'uTex']
  );

  function playerTexture(jersey, skin, shorts, shoe) {
    const c = document.createElement('canvas');
    c.width = 128; c.height = 256;
    const g = c.getContext('2d');
    const col = function (rgb) {
      return 'rgb(' + Math.round(rgb[0] * 255) + ',' + Math.round(rgb[1] * 255) + ',' + Math.round(rgb[2] * 255) + ')';
    };
    const stroke = 'rgba(6,16,11,.55)';
    g.clearRect(0, 0, 128, 256);
    g.lineJoin = 'round'; g.lineCap = 'round';
    g.fillStyle = col(skin); g.strokeStyle = stroke; g.lineWidth = 2.5;
    g.beginPath(); g.ellipse(64, 36, 14, 16, 0, 0, Math.PI * 2); g.fill(); g.stroke();
    g.fillStyle = '#15100C';
    g.beginPath(); g.ellipse(64, 28, 15, 10, 0, Math.PI, Math.PI * 2); g.fill();
    g.fillStyle = col(skin); g.fillRect(59, 49, 10, 10);
    g.strokeStyle = col(skin); g.lineWidth = 8;
    g.beginPath(); g.moveTo(47, 75); g.lineTo(31, 54); g.lineTo(35, 28); g.stroke();
    g.beginPath(); g.arc(35, 26, 5, 0, Math.PI * 2); g.fill();
    g.beginPath(); g.moveTo(81, 75); g.lineTo(104, 99); g.lineTo(115, 94); g.stroke();
    g.beginPath(); g.arc(116, 93, 5, 0, Math.PI * 2); g.fill();
    g.fillStyle = col(jersey); g.strokeStyle = stroke; g.lineWidth = 2.5;
    g.beginPath();
    g.moveTo(43, 68);
    g.quadraticCurveTo(64, 60, 85, 68);
    g.lineTo(78, 136);
    g.quadraticCurveTo(64, 142, 50, 136);
    g.closePath(); g.fill(); g.stroke();
    g.fillStyle = 'rgba(255,255,255,.9)'; g.font = 'bold 20px sans-serif';
    g.textAlign = 'center'; g.textBaseline = 'middle';
    g.fillText('8', 64, 102);
    g.fillStyle = col(shorts); g.strokeStyle = stroke;
    g.beginPath();
    g.moveTo(50, 134); g.lineTo(78, 134); g.lineTo(80, 172);
    g.lineTo(66, 172); g.lineTo(64, 156); g.lineTo(62, 172);
    g.lineTo(48, 172); g.closePath(); g.fill(); g.stroke();
    g.strokeStyle = col(skin); g.lineWidth = 9;
    g.beginPath(); g.moveTo(55, 168); g.lineTo(49, 212); g.lineTo(53, 240); g.stroke();
    g.beginPath(); g.moveTo(73, 168); g.lineTo(82, 210); g.lineTo(78, 240); g.stroke();
    g.fillStyle = col(shoe); g.strokeStyle = stroke; g.lineWidth = 2.5;
    g.beginPath(); g.ellipse(52, 246, 11, 6, 0, 0, Math.PI * 2); g.fill(); g.stroke();
    g.beginPath(); g.ellipse(79, 246, 11, 6, 0, 0, Math.PI * 2); g.fill(); g.stroke();

    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, c);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.generateMipmap(gl.TEXTURE_2D);
    return tex;
  }

  const texA = playerTexture(C.jerseyA, C.skin1, C.shorts, C.shoe);
  const texB = playerTexture(C.jerseyB, C.skin2, C.shorts, C.shoe);
  const texC = playerTexture(C.jerseyC, C.skin3, C.shorts, C.shoe);

  const playerDefs = [
    { x: -0.9, z: courtZ[1] - 0.4, phase: 0.2, tex: texA },
    { x: 0.6, z: courtZ[1] + 0.45, phase: 1.4, tex: texB },
    { x: 1.1, z: courtZ[1] - 0.3, phase: 2.1, tex: texC },
    { x: -1.2, z: courtZ[2] + 0.3, phase: 0.8, tex: texB },
    { x: 0.35, z: courtZ[2] - 0.5, phase: 2.8, tex: texA },
    { x: -0.6, z: courtZ[3] - 0.4, phase: 1.1, tex: texA },
    { x: 1.2, z: courtZ[3] + 0.2, phase: 2.4, tex: texB },
    { x: -1.1, z: courtZ[4] + 0.4, phase: 0.4, tex: texC },
    { x: 0.4, z: courtZ[4] - 0.3, phase: 1.8, tex: texA },
    { x: 1.3, z: courtZ[4] + 0.35, phase: 3.1, tex: texB }
  ];
  const players = playerDefs.map(function (p) {
    return { x: p.x, z: p.z, phase: p.phase, tex: p.tex };
  });

  const shadowB = new Builder();
  playerDefs.forEach(function (p) {
    shadowB.cylinder(p.x, 0.03, p.z, 0.07, 0.002, 10, [0.02, 0.03, 0.025], [0, 0, 0]);
  });
  const shadowMesh = upload(shadowB);

  const spriteQuad = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, spriteQuad);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, 0, 1, 0, 1, 1, -1, 0, 1, 1, -1, 1]), gl.STATIC_DRAW);

