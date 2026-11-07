(function () {
  var root = document.getElementById('lanjie-proto');
  var pickup = document.getElementById('view-pickup');
  var me = document.getElementById('view-me');
  if (!root || !pickup || !me) return;

  function toast(view, msg) {
    var el = view.querySelector('.page-toast');
    if (!el) return;
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(el._toastTimer);
    el._toastTimer = setTimeout(function () { el.classList.remove('show'); }, 2300);
  }

  /* pickup filters */
  pickup.querySelectorAll('[data-format]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var format = btn.dataset.format;
      pickup.querySelectorAll('[data-format]').forEach(function (b) {
        b.classList.toggle('active', b === btn);
      });
      pickup.querySelectorAll('[data-card-format]').forEach(function (card) {
        card.style.display = (format === 'all' || card.dataset.cardFormat === format) ? '' : 'none';
      });
    });
  });

  /* join */
  pickup.querySelectorAll('[data-join]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      if (btn.classList.contains('joined')) return;
      btn.classList.add('joined');
      btn.textContent = '已加入';
      toast(pickup, '已加入；开场前 24 小时可退');
    });
  });

  /* create sheet */
  var pickupSheet = document.getElementById('pickupSheet');
  var pickupVeil = document.getElementById('pickupVeil');
  function openCreate() {
    pickupSheet.classList.add('open');
    pickupSheet.setAttribute('aria-hidden', 'false');
    pickupVeil.classList.add('show');
  }
  function closeCreate() {
    pickupSheet.classList.remove('open');
    pickupSheet.setAttribute('aria-hidden', 'true');
    pickupVeil.classList.remove('show');
  }
  document.getElementById('pickupCreate').addEventListener('click', openCreate);
  pickupVeil.addEventListener('click', closeCreate);
  document.getElementById('pickupCancel').addEventListener('click', closeCreate);
  pickupSheet.querySelector('[data-pickup-close]').addEventListener('click', closeCreate);
  document.getElementById('publishPickup').addEventListener('click', function () {
    closeCreate();
    toast(pickup, '已发布：1 号场 今晚 20:00–22:00，缺 2 人');
  });
  pickupSheet.querySelectorAll('[data-pickup-option]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var group = btn.parentElement;
      group.querySelectorAll('button').forEach(function (b) {
        b.classList.toggle('on', b === btn);
      });
    });
  });
  pickup.querySelectorAll('[data-pickup-link]').forEach(function (btn) {
    btn.addEventListener('click', function () { toast(pickup, '选场在正式版里从这里展开'); });
  });
  pickup.querySelectorAll('[data-pickup-manage]').forEach(function (btn) {
    btn.addEventListener('click', function () { toast(pickup, '管理拼场在正式版里展开'); });
  });

  /* recharge */
  var meSheet = document.getElementById('rechargeSheet');
  var rechargeVeil = document.getElementById('rechargeVeil');
  function openRecharge() {
    meSheet.classList.add('open');
    meSheet.setAttribute('aria-hidden', 'false');
    rechargeVeil.classList.add('show');
  }
  function closeRecharge() {
    meSheet.classList.remove('open');
    meSheet.setAttribute('aria-hidden', 'true');
    rechargeVeil.classList.remove('show');
  }
  me.querySelectorAll('[data-recharge]').forEach(function (btn) {
    btn.addEventListener('click', openRecharge);
  });
  rechargeVeil.addEventListener('click', closeRecharge);
  ['rechargeCancel', 'rechargeCancel2', 'rechargeGrab'].forEach(function (id) {
    var el = document.getElementById(id);
    if (el) el.addEventListener('click', closeRecharge);
  });

  var rechargeAmount = 500;
  meSheet.querySelectorAll('[data-amount]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      rechargeAmount = Number(btn.dataset.amount);
      meSheet.querySelectorAll('[data-amount]').forEach(function (b) {
        b.classList.toggle('on', b === btn);
      });
    });
  });
  document.getElementById('doRecharge').addEventListener('click', function () {
    closeRecharge();
    var el = document.getElementById('walletAmount');
    var next = (Number(el.dataset.value) || 240) + rechargeAmount;
    el.dataset.value = String(next);
    el.innerHTML = '¥' + next + '<span>.00</span>';
    toast(me, '充值成功，余额 ¥' + next + '.00（原型演示）');
  });

  /* me links */
  me.querySelectorAll('[data-me-link]').forEach(function (btn) {
    btn.addEventListener('click', function () { toast(me, '这个入口在正式版里展开'); });
  });
  me.querySelectorAll('[data-me-settings]').forEach(function (btn) {
    btn.addEventListener('click', function () { toast(me, '设置页在正式版里展开'); });
  });
  me.querySelectorAll('[data-wallet-detail]').forEach(function (btn) {
    btn.addEventListener('click', function () { toast(me, '钱包明细在正式版里展开'); });
  });

  /* order filters */
  me.querySelectorAll('[data-order-filter]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var filter = btn.dataset.orderFilter;
      me.querySelectorAll('[data-order-filter]').forEach(function (b) {
        b.classList.toggle('active', b === btn);
      });
      me.querySelectorAll('.order-row').forEach(function (row) {
        row.style.display = (filter === 'all' || row.dataset.status === filter) ? '' : 'none';
      });
    });
  });

  /* qr */
  var qrModal = document.getElementById('qrModal');
  var qrVeil = document.getElementById('qrVeil');
  function openQr() {
    qrModal.classList.add('open');
    qrModal.setAttribute('aria-hidden', 'false');
    qrVeil.classList.add('show');
  }
  function closeQr() {
    qrModal.classList.remove('open');
    qrModal.setAttribute('aria-hidden', 'true');
    qrVeil.classList.remove('show');
  }
  me.querySelectorAll('[data-qr]').forEach(function (btn) {
    btn.addEventListener('click', openQr);
  });
  qrVeil.addEventListener('click', closeQr);
  document.getElementById('qrClose').addEventListener('click', closeQr);

  /* fake qr grid */
  var qrGrid = document.getElementById('qrGrid');
  if (qrGrid) {
    function finder(x, y, ox, oy) {
      var dx = x - ox, dy = y - oy;
      if (dx < 0 || dy < 0 || dx > 6 || dy > 6) return null;
      if (dx === 0 || dx === 6 || dy === 0 || dy === 6) return 1;
      if (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4) return 1;
      return 0;
    }
    for (var y = 0; y < 21; y++) {
      for (var x = 0; x < 21; x++) {
        var f = finder(x, y, 0, 0);
        if (f === null) f = finder(x, y, 14, 0);
        if (f === null) f = finder(x, y, 0, 14);
        var on = f === null ? (((x * 7 + y * 11 + x * y) % 7) < 3) : f === 1;
        var cell = document.createElement('span');
        if (on) cell.className = 'on';
        qrGrid.appendChild(cell);
      }
    }
  }
})();
