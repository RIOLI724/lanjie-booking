(function () {
  var page = document.getElementById('view-coach');
  if (!page) return;

  function toast(msg) {
    var el = page.querySelector('.page-toast');
    if (!el) return;
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(el._toastTimer);
    el._toastTimer = setTimeout(function () { el.classList.remove('show'); }, 2400);
  }

  page.querySelectorAll('[data-coach-tab]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      page.querySelectorAll('[data-coach-tab]').forEach(function (b) {
        b.classList.toggle('active', b === btn);
      });
      page.querySelectorAll('[data-coach-list]').forEach(function (list) {
        list.hidden = list.dataset.coachList !== btn.dataset.coachTab;
      });
    });
  });

  var sheet = document.getElementById('coachSheet');
  var veil = document.getElementById('coachVeil');
  var state = { coach: '张教练', meta: '前省队 · 基础 / 步法 · 4.9', price: 300, pack: 1, day: '今天', time: '19:00', court: '含场地' };

  function packPrice(pack) {
    if (pack === 1) return state.price;
    var rate = pack === 5 ? 0.95 : pack === 10 ? 0.9 : 0.85;
    return Math.round(state.price * pack * rate);
  }

  function render() {
    document.getElementById('coachSheetName').textContent = state.coach;
    document.getElementById('coachSheetMeta').textContent = state.meta;
    page.querySelectorAll('[data-pack]').forEach(function (btn) {
      var pack = Number(btn.dataset.pack);
      btn.querySelector('small').textContent = '¥' + packPrice(pack);
      btn.classList.toggle('on', pack === state.pack);
    });
    page.querySelectorAll('[data-coach-day]').forEach(function (btn) {
      btn.classList.toggle('on', btn.dataset.coachDay === state.day);
    });
    page.querySelectorAll('[data-coach-time]').forEach(function (btn) {
      btn.classList.toggle('on', btn.dataset.coachTime === state.time);
    });
    page.querySelectorAll('[data-coach-court]').forEach(function (btn) {
      btn.classList.toggle('on', btn.dataset.coachCourt === state.court);
    });
    var label = state.pack === 1 ? '单节' : state.pack + ' 节包';
    document.getElementById('coachBarTitle').textContent = state.coach + ' · ' + label;
    document.getElementById('coachBarSub').textContent = state.day + ' ' + state.time + ' · ' + state.court;
    document.getElementById('coachBarPrice').textContent = '¥' + packPrice(state.pack);
  }

  function open(coach, meta, price) {
    state.coach = coach;
    state.meta = meta;
    state.price = price;
    state.pack = 1;
    state.day = '今天';
    state.time = '19:00';
    state.court = '含场地';
    render();
    sheet.classList.add('open');
    sheet.setAttribute('aria-hidden', 'false');
    veil.classList.add('show');
  }

  function close() {
    sheet.classList.remove('open');
    sheet.setAttribute('aria-hidden', 'true');
    veil.classList.remove('show');
  }

  page.querySelectorAll('[data-coach]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      open(btn.dataset.coach, btn.dataset.meta, Number(btn.dataset.price));
    });
  });
  page.querySelector('[data-coach-close]').addEventListener('click', close);
  document.getElementById('coachCancel').addEventListener('click', close);
  veil.addEventListener('click', close);

  page.querySelectorAll('[data-pack]').forEach(function (btn) {
    btn.addEventListener('click', function () { state.pack = Number(btn.dataset.pack); render(); });
  });
  page.querySelectorAll('[data-coach-day]').forEach(function (btn) {
    btn.addEventListener('click', function () { state.day = btn.dataset.coachDay; render(); });
  });
  page.querySelectorAll('[data-coach-time]').forEach(function (btn) {
    btn.addEventListener('click', function () { state.time = btn.dataset.coachTime; render(); });
  });
  page.querySelectorAll('[data-coach-court]').forEach(function (btn) {
    btn.addEventListener('click', function () { state.court = btn.dataset.coachCourt; render(); });
  });

  document.getElementById('coachReserve').addEventListener('click', function () {
    close();
    if (state.pack === 1) toast('已锁位 15 分钟；提前 24 小时可免费取消');
    else toast('已预约 ' + state.pack + ' 节包；提前 24 小时可免费改 / 取消');
  });

  page.querySelectorAll('[data-group]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var wait = btn.dataset.group === '0';
      btn.classList.add('joined');
      btn.textContent = wait ? '已候补' : '已报名';
      toast(wait ? '已加入候补；有人退会通知你' : '已报名；4 人开班，未满提前 24h 取消');
    });
  });

  page.querySelectorAll('[data-coach-link]').forEach(function (btn) {
    btn.addEventListener('click', function () { toast('筛选在正式版里展开'); });
  });
})();
