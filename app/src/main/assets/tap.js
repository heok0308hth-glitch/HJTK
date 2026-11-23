// HJTK tap.js — document-start 주입. 페이지의 fetch/XHR 스트림을 '복제'만 읽는다(원본은 페이지가 소비).
// 이 스크립트는 네이티브 권한이 없다. HJTKTap 브리지는 텍스트 수신기 5개 메서드뿐.
(function () {
  if (window.__hjtkTap) return; window.__hjtkTap = 1;
  var B = window.HJTKTap; if (!B) return;
  var CAP = 6 << 20, buf = {}, timers = {}, seen = {};
  function flush(id) { if (buf[id]) { var s = buf[id]; buf[id] = ''; try { B.chunk(id, s); } catch (e) {} } }
  function push(id, s) {
    seen[id] = (seen[id] || 0) + s.length; if (seen[id] > CAP) return;   // 상한은 스트림마다(페이지 전체가 아니라)
    buf[id] = (buf[id] || '') + s;
    if (buf[id].length > 8192) flush(id);
    else if (!timers[id]) timers[id] = setTimeout(function () { timers[id] = 0; flush(id); }, 40);
  }
  function done(id, why) { flush(id); delete seen[id]; delete buf[id]; try { B.end(id, why); } catch (e) {} }
  function interesting(url, ct, m) {
    if (m !== 'POST') return false;
    if (!/^https:\/\/chat\.deepseek\.com\//.test(String(url || ''))) return false;
    if (/event-stream/i.test(ct || '')) return true;
    return /completion|chat\/.*stream|regenerate|continue/i.test(String(url)) && /ndjson|json/i.test(ct || '');
  }
  // 관찰은 '통과형'이다. clone()은 긴 답의 사본을 렌더러 힙에 하나 더 쌓아 WebView가 메모리로 죽는 원인이 된다.
  // 페이지가 읽는 바로 그 조각을 지나가며 본다.
  var of = window.fetch;
  if (of) window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : (input && input.url) || '';
    var m = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();
    return of.apply(this, arguments).then(function (res) {
      try {
        var ct = res.headers.get('content-type') || '';
        if (!interesting(url, ct, m) || !res.body || typeof ReadableStream === 'undefined') return res;
        var id = 'f' + Math.random().toString(36).slice(2, 10);
        B.open(id, String(url), ct, res.status);
        var rd = res.body.getReader(), dec = new TextDecoder('utf-8'), closed = false;
        var body = new ReadableStream({
          pull: function (ctl) {
            return rd.read().then(function (x) {
              if (x.done) { if (!closed) { closed = true; done(id, 'eof'); } ctl.close(); return; }
              try { push(id, dec.decode(x.value, { stream: true })); } catch (e) {}
              ctl.enqueue(x.value);
            }, function (err) { if (!closed) { closed = true; done(id, 'err'); } ctl.error(err); });
          },
          cancel: function (r) { if (!closed) { closed = true; done(id, 'cancel'); } try { return rd.cancel(r); } catch (e) {} }
        });
        var out = new Response(body, { status: res.status, statusText: res.statusText, headers: res.headers });
        try { Object.defineProperty(out, 'url', { value: res.url }); Object.defineProperty(out, 'redirected', { value: res.redirected }); } catch (e) {}
        return out;
      } catch (e) { try { B.end('?', 'hookfail'); } catch (e2) {} return res; }
    });
  };
  var OX = window.XMLHttpRequest;
  if (OX) {
    var oOpen = OX.prototype.open, oSend = OX.prototype.send;
    OX.prototype.open = function (m, u) { this.__h = { m: String(m).toUpperCase(), u: String(u), n: 0 }; return oOpen.apply(this, arguments); };
    OX.prototype.send = function () {
      var x = this, c = x.__h; if (!c || c.m !== 'POST' || !/^https:\/\/chat\.deepseek\.com\//.test(c.u)) return oSend.apply(this, arguments);
      var id = 'x' + Math.random().toString(36).slice(2, 10), opened = false;
      x.addEventListener('progress', function () {
        try {
          var ct = x.getResponseHeader('content-type') || '';
          if (!opened) { if (!interesting(c.u, ct, 'POST')) return; opened = true; B.open(id, c.u, ct, x.status); }
          var t = x.responseText || ''; if (t.length > c.n) { push(id, t.slice(c.n)); c.n = t.length; }
        } catch (e) {}
      });
      x.addEventListener('loadend', function () { if (opened) done(id, 'xhr'); });
      return oSend.apply(this, arguments);
    };
  }
  try {
    var cb = navigator.clipboard;
    if (cb && cb.writeText) { var w = cb.writeText.bind(cb); cb.writeText = function (t) { try { B.clip(String(t)); } catch (e) {} return w(t); }; }
    document.addEventListener('copy', function () { try { var s = String(window.getSelection() || ''); if (s.length > 1) B.clip(s); } catch (e) {} }, true);
  } catch (e) {}

  // 입력·전송(네이티브가 호출). React 제어 컴포넌트는 value setter + input 이벤트가 필요하다.
  window.__hjtkSend = function (text) {
    var box = document.querySelector('textarea');
    if (box) {
      var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
      box.focus(); setter.call(box, text);
      box.dispatchEvent(new Event('input', { bubbles: true }));
      return 'OK';
    }
    var ce = document.querySelector('[contenteditable="true"]');
    if (ce) { ce.focus(); document.execCommand('selectAll', false, null); document.execCommand('insertText', false, text); return 'OK_CE'; }
    return 'NO_INPUT';
  };
  window.__hjtkEnter = function () {
    var box = document.querySelector('textarea') || document.querySelector('[contenteditable="true"]');
    if (!box) return 'NO_INPUT';
    box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
    return 'ENTER';
  };
  window.__hjtkClickSend = function () {
    var box = document.querySelector('textarea');
    var all = [].slice.call(document.querySelectorAll('div[role="button"],button')).filter(function (b) {
      if (b.getAttribute('aria-disabled') === 'true' || b.disabled) return false;
      var r = b.getBoundingClientRect(); return r.width > 0 && r.height > 0;
    });
    var lab = all.filter(function (b) { return /send|전송|보내기|发送/i.test((b.getAttribute('aria-label') || '') + ' ' + (b.title || '')); });
    var btn = lab[0];
    if (!btn && box) {
      var br = box.getBoundingClientRect(), best = null, bd = 1e9;
      all.forEach(function (b) {
        var r = b.getBoundingClientRect();
        if (r.top >= br.top - 20 && r.left > br.left + br.width * 0.6 && !(b.innerText || '').trim()) {
          var d = Math.abs(r.right - br.right) + Math.abs(r.bottom - br.bottom);
          if (d < bd) { bd = d; best = b; }
        }
      });
      btn = best;
    }
    if (btn) { btn.click(); return 'CLICK'; }
    return 'NONE';
  };
  window.__hjtkState = function () {
    var p = location.pathname || '';
    try { B.href && B.href(location.href); } catch (e) {}
    var head = document.documentElement ? document.documentElement.innerHTML.slice(0, 20000) : '';
    if (/challenges\.cloudflare\.com|Just a moment|cf-chl/i.test(head)) return 'CHALLENGE';
    if (/sign_in|sign_up|signin|login|register/i.test(p)) return 'LOGIN';
    if (document.querySelector('input[type="password"]')) return 'LOGIN';
    var box = document.querySelector('textarea') || document.querySelector('[contenteditable="true"]');
    if (box) return 'READY';
    if (document.readyState === 'complete') {
      // 입력창이 없고 로그인 문구·전화/메일 입력만 있으면 로그인 화면이다(비밀번호 칸이 없는 인증코드 로그인 포함)
      var tel = document.querySelector('input[type="tel"],input[type="email"],input[autocomplete*="username"]');
      var t = (document.body && document.body.innerText || '').slice(0, 3000);
      if (tel || /登录|Log in|Sign in|로그인/i.test(t)) return 'LOGIN';
    }
    return 'LOADING';
  };
  // 답 노드: 생각(THINK) 블록 안의 마크다운은 제외한다
  function answers() {
    var n = document.querySelectorAll('.ds-markdown');
    if (!n.length) n = document.querySelectorAll('[class*="markdown"]');
    return [].filter.call(n, function (x) { return !(x.closest && x.closest('[class*="think"]')); });
  }
  function boxLen() {
    var b = document.querySelector('textarea'); if (b) return b.value.length;
    var c = document.querySelector('[contenteditable="true"]'); return c ? (c.innerText || '').trim().length : -1;
  }
  window.__hjtkLastAnswer = function () { var n = answers(); return n.length ? (n[n.length - 1].innerText || '') : ''; };
  window.__hjtkBox = boxLen;
  /** 보내기 직전의 기준점: 답 개수와 마지막 답 꼬리. 이후 '새 답'만 인정한다(이전 답을 새 답으로 착각하지 않게) */
  window.__hjtkMark = function () {
    var n = answers();
    return JSON.stringify({ n: n.length, last: n.length ? (n[n.length - 1].innerText || '').slice(-300) : '', box: boxLen() });
  };
  window.__hjtkNewAnswer = function (n0, last0) {
    var n = answers(); if (!n.length) return '';
    var t = n[n.length - 1].innerText || '';
    if (n.length > n0) return t;
    if (n.length === n0 && n0 > 0 && t.slice(-300) !== last0) return t;
    return '';
  };
  /** 작업 중지: 생성 중이면 '중지' 버튼(이름표가 있는 것만)을 누른다 */
  window.__hjtkStopGen = function () {
    var bs = [].slice.call(document.querySelectorAll('div[role="button"],button'));
    for (var i = 0; i < bs.length; i++) { var l = (bs[i].getAttribute('aria-label') || '') + ' ' + (bs[i].title || ''); if (/stop|중지|停止/i.test(l)) { bs[i].click(); return 'STOPPED'; } }
    return 'IDLE';
  };
  /** 페이지 새로고침 없이 새 대화(SPA 버튼). 못 찾으면 NONE → 앱이 주소를 다시 연다 */
  window.__hjtkNewChat = function () {
    var re = /^(开启新对话|新对话|New chat|새 채팅|새로운 채팅|새 대화|開啟新對話)$/i;
    var all = document.querySelectorAll('div,a,button,span');
    for (var i = 0; i < all.length; i++) {
      var el = all[i], t = (el.innerText || '').trim();
      if (t.length < 20 && re.test(t)) { var r = el.getBoundingClientRect(); if (r.width > 0 && r.height > 0) { el.click(); return 'OK'; } }
    }
    return 'NONE';
  };
  /** 서버에 저장된 이 대화방 기록(사용자 본인 세션의 페이지 요청). 결과는 브리지 hist로 돌아온다 */
  window.__hjtkHistory = function () {
    try {
      var m = location.pathname.match(/\/s\/([\w-]+)/); if (!m) { B.hist(''); return 'NOID'; }
      var tok = ''; var raw = localStorage.getItem('userToken') || '';
      try { var o = JSON.parse(raw); tok = (o && o.value) || ''; } catch (e) { tok = raw; }
      var h = {}; if (tok) h.authorization = 'Bearer ' + tok;
      of.call(window, '/api/v0/chat/history_messages?chat_session_id=' + encodeURIComponent(m[1]), { headers: h, credentials: 'include' })
        .then(function (r) { return r.text(); }).then(function (t) { B.hist(t.slice(0, 800000)); })
        .catch(function () { B.hist(''); });
      return 'OK';
    } catch (e) { try { B.hist(''); } catch (e2) {} return 'ERR'; }
  };
})();
