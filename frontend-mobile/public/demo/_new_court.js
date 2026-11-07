  var courtState = { court: 1, day: '11-06', sel: [] };
  var courtSlots = root.querySelector('#courtSlots');
  var courtBar = root.querySelector('#courtBar');

  function courtBooked(dayKey, court) {
    var data = GRID[dayKey] || { booked: {}, held: {} };
    return {
      booked: data.booked[String(court)] || [],
      held: data.held[String(court)] || []
    };
  }

  function renderCourt() {
    var day = DAYS.filter(function (d) { return d.key === courtState.day; })[0];
    var court = courtState.court;
    var data = courtBooked(courtState.day, court);
    var label = day.label;
    root.querySelector('.court-hero-num').textContent = '0' + court;
    root.querySelector('.court-sheet .sheet-head h1').textContent = court + ' 号场';
    root.querySelector('.court-sheet .sheet-head .eyebrow').textContent = 'COURT 0' + court + ' · 木地板';
    var freeCount = HOURS.length - data.booked.length - data.held.length;
    root.querySelector('.court-sheet .sheet-head p').textContent =
      label + ' 还剩 ' + freeCount + ' 个时段 · 4 片全场';
    root.querySelectorAll('[data-court-day]').forEach(function (b) {
      b.classList.toggle('active', b.dataset.courtDay === courtState.day);
    });
    var html = '';
    HOURS.forEach(function (h) {
      var booked = data.booked.indexOf(h) >= 0;
      var held = data.held.indexOf(h) >= 0;
      var sel = courtState.sel.indexOf(h) >= 0;
      var price = priceFor(day, h);
      var cls = booked ? 'booked' : held ? 'held' : sel ? 'sel' : 'free';
      var status = booked ? '已订' : held ? '待支付' : '空';
      html += '<button class="court-slot ' + cls + '" data-hour="' + h + '"' +
        (booked || held ? ' disabled' : '') + ' aria-pressed="' + (sel ? 'true' : 'false') +
        ' aria-label="' + h + ':00，' + status + '，' + price + ' 元">' +
        '<b>' + h + ':00</b><small>¥' + price + ' · ' + status + '</small></button>';
    });
    courtSlots.innerHTML = html;
    var pn = root.querySelectorAll('.price-note > span');
    if (pn.length >= 2) {
      pn[0].querySelector('b').textContent = '¥' + (day.weekend ? 140 : 120) + '/时';
      pn[1].querySelector('b').textContent = '¥160/时';
    }
    renderCourtBar();
  }

  function renderCourtBar() {
    var day = DAYS.filter(function (d) { return d.key === courtState.day; })[0];
    var title = courtBar.querySelector('.bb-title');
    var sub = courtBar.querySelector('.bb-sub');
    var price = courtBar.querySelector('.bb-price');
    var btn = courtBar.querySelector('.bb-btn');
    var hours = courtState.sel.slice().sort(function (a, b) { return a - b; });
    if (!hours.length) {
      title.textContent = '选一个时段';
      sub.textContent = '点上面的时段，可连选';
      price.textContent = '—';
      btn.disabled = true;
      return;
    }
    var total = hours.reduce(function (s, h) { return s + priceFor(day, h); }, 0);
    var contiguous = hours.every(function (h, i) { return i === 0 || h === hours[i - 1] + 1; });
    var range = contiguous
      ? hours[0] + ':00–' + (hours[hours.length - 1] + 1) + ':00'
      : hours.map(function (h) { return h + ':00'; }).join('、');
    title.textContent = courtState.court + ' 号场 · ' + hours.length + ' 小时';
    sub.textContent = day.date + ' ' + day.week + ' · ' + range;
    price.textContent = '¥' + total;
    btn.disabled = false;
  }

  function toggleCourtSlot(hour) {
    var i = courtState.sel.indexOf(hour);
    if (i >= 0) courtState.sel.splice(i, 1);
    else courtState.sel.push(hour);
    renderCourt();
  }

  function openCourt(court) {
    courtState.court = court;
    courtState.day = '11-06';
    courtState.sel = [];
    renderCourt();
    showView('court');
  }

