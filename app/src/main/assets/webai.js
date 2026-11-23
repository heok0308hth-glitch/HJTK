/* HJTK web AI adapter — 로그인한 AI 웹(Claude·ChatGPT·Gemini·Grok)을 키 없이 쓰기 위한 얇은 손.
   한 번 넣으면 window.__hjtkAi 가 생긴다. 설정(CFG)은 앱이 사이트별로 앞에 붙여 준다.
   원칙: 화면에 보이는 입력창에 글을 넣고 보내기를 누른 뒤, 마지막 답 요소의 글자를 읽는다. 클릭은 보내기 한 번뿐. */
(function () {
  if (window.__hjtkAi && window.__hjtkAi.v === 4) { window.__hjtkAi.cfg = window.__HJTK_CFG || window.__hjtkAi.cfg; return; }
  var cfg = window.__HJTK_CFG || {};
  function q(list) {
    for (var i = 0; i < (list || []).length; i++) {
      try { var els = document.querySelectorAll(list[i]); for (var k = els.length - 1; k >= 0; k--) { if (vis(els[k])) return els[k]; } } catch (e) {}
    }
    return null;
  }
  function all(list) {
    for (var i = 0; i < (list || []).length; i++) {
      try { var els = document.querySelectorAll(list[i]); if (els.length) return els; } catch (e) {}
    }
    return [];
  }
  /* 답 요소: 사이트별 자리 → 못 찾으면 흔한 마크다운 답 모양(사용자 말풍선은 제외) */
  function answers() {
    var els = all(api.cfg.answer); if (els.length) return els;
    var gen = document.querySelectorAll('[data-message-author-role="assistant"], .markdown, .prose, [class*="markdown"]');
    var out = [];
    for (var i = 0; i < gen.length; i++) { var e = gen[i]; if (e.closest('[data-message-author-role="user"], [class*="user-message"], [class*="UserMessage"], form, textarea')) continue; if (out.length && out[out.length - 1].contains(e)) continue; out.push(e); }
    return out;
  }
  function vis(el) { if (!el) return false; var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  function text(el) { return el ? (el.innerText || el.textContent || '').replace(/ /g, ' ').trim() : ''; }
  function loginish() {
    var h = location.href;
    if (/login|signin|sign-in|auth|accounts\.google|oauth|identifier/i.test(h)) return true;
    return !!document.querySelector('input[type=password], input[type=email], input[name=email], input[autocomplete=username]');
  }
  var api = {
    v: 4, cfg: cfg,
    input: function () {
      var el = q(api.cfg.input); if (el) return el;
      // 사이트가 바뀌어 지정한 자리를 못 찾으면: 화면 아래쪽의 넓은 입력칸(채팅 입력창의 공통 모양)
      var c = document.querySelectorAll('textarea, [contenteditable="true"], [role="textbox"]');
      var best = null, area = 0, vh = window.innerHeight || 800;
      for (var i = 0; i < c.length; i++) { var r = c[i].getBoundingClientRect(); if (r.width < 150 || r.height < 18 || r.bottom < vh * 0.35) continue; var a = r.width * r.height; if (a > area) { area = a; best = c[i]; } }
      return best;
    },
    state: function () {
      if (document.readyState === 'loading') return 'LOADING';
      if (api.input()) return 'READY';
      if (/challenge|cf-chl|captcha/i.test(document.documentElement.innerHTML.slice(0, 4000)) && /just a moment|확인|verify/i.test(document.title + text(document.body).slice(0, 200))) return 'CHALLENGE';
      if (loginish()) return 'LOGIN';
      return 'LOADING';
    },
    count: function () { return answers().length; },
    last: function () { var els = answers(); return els.length ? text(els[els.length - 1]) : ''; },
    /* 3.4: 보내기 전에 지금 있는 답에 표시 → 그 뒤에 생긴 답만 '새 답'. 새 대화 창에서 이전 답이 잘못 읽히는 일을 막는다 */
    mark: function () { var els = answers(); for (var i = 0; i < els.length; i++) els[i].setAttribute('data-hjtk-seen', '1'); return String(els.length); },
    fresh: function () { var els = answers(), n = 0; for (var i = 0; i < els.length; i++) if (!els[i].closest('[data-hjtk-seen]')) n++; return String(n); },
    lastFresh: function () { var els = answers(); for (var i = els.length - 1; i >= 0; i--) if (!els[i].closest('[data-hjtk-seen]')) return text(els[i]); return ''; },
    /* 멈춤: 생성 중이면 사이트의 '중지' 버튼을 누른다 */
    stop: function () { var b = q(api.cfg.stop); if (b) { b.click(); return 'STOPPED'; } return 'IDLE'; },
    busy: function () {
      if (q(api.cfg.stop)) return true;
      var b = api.cfg.streaming; if (b) { try { if (document.querySelector(b)) return true; } catch (e) {} }
      return false;
    },
    put: function (t) {
      var el = api.input(); if (!el) return 'NO_INPUT';
      el.focus();
      if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
        var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, t);
        el.dispatchEvent(new Event('input', { bubbles: true }));
      } else {
        try { document.execCommand('selectAll', false, null); document.execCommand('insertText', false, t); } catch (e) {}
        if (text(el).length < Math.min(8, t.length)) {           // insertText를 막는 편집기: 붙여넣기 이벤트로
          var dt = new DataTransfer(); dt.setData('text/plain', t);
          el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
        }
      }
      return text(el).length || (el.value || '').length ? 'OK' : 'EMPTY';
    },
    submit: function () {
      var b = q(api.cfg.send);
      if (!b) {   // 입력창 근처의 '보내기'처럼 생긴 버튼
        var el = api.input(); var form = el && (el.closest('form') || el.closest('fieldset') || el.parentElement && el.parentElement.parentElement);
        if (form) { var bs = form.querySelectorAll('button'); for (var i = bs.length - 1; i >= 0; i--) { var l = (bs[i].getAttribute('aria-label') || '') + ' ' + text(bs[i]); if (/send|submit|보내|전송|제출/i.test(l) && vis(bs[i])) { b = bs[i]; break; } } }
      }
      if (b && !b.disabled) { b.click(); return 'CLICK'; }
      var el2 = api.input(); if (!el2) return 'NO_INPUT';
      ['keydown', 'keypress', 'keyup'].forEach(function (k) { el2.dispatchEvent(new KeyboardEvent(k, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true })); });
      return 'ENTER';
    },
    /* 모델 고르기: 모델 버튼을 눌러 메뉴를 열고, 이름이 들어간 항목을 누른다(사이트마다 모양이 달라 글자로 찾는다) */
    model: function (name) {
      var re = /(opus|sonnet|haiku|claude|gpt|o\d|chatgpt|gemini|flash|pro|grok|fast|expert|thinking|auto|instant|모델)/i;
      var btn = q(api.cfg.modelBtn);
      if (!btn) { var bs = document.querySelectorAll('button,[role=button]'); for (var i = 0; i < bs.length; i++) { var t = text(bs[i]); if (t.length < 40 && re.test(t + ' ' + (bs[i].getAttribute('aria-label') || '')) && vis(bs[i])) { btn = bs[i]; break; } } }
      if (!btn) return 'NO_BUTTON';
      if (text(btn).toLowerCase().indexOf(name.toLowerCase()) >= 0) return 'ALREADY';
      btn.click();
      return 'OPENED';
    },
    choose: function (name) {
      var items = document.querySelectorAll('[role=menuitem],[role=menuitemradio],[role=option],[data-testid*=model],li button,li');
      var n = name.toLowerCase();
      for (var i = 0; i < items.length; i++) { if (vis(items[i]) && text(items[i]).toLowerCase().indexOf(n) >= 0) { items[i].click(); return 'OK'; } }
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      return 'NOT_FOUND';
    },
    /* 구글 계정으로 이어서 로그인: 'Google로 계속' → 계정 고르기 → 계속/허용. 한 번 부를 때 한 단계만 누른다 */
    sso: function (provider) {
      var h = location.host;
      function clickText(re, scope) {
        var els = (scope || document).querySelectorAll('button,a,[role=button],[role=link],div[data-identifier],li');
        for (var i = 0; i < els.length; i++) { var t = (text(els[i]) + ' ' + (els[i].getAttribute('aria-label') || '')).trim(); if (t.length < 80 && re.test(t) && vis(els[i])) { els[i].click(); return t; } }
        return null;
      }
      if (/accounts\.google\.com/.test(h)) {
        var acc = document.querySelector('[data-identifier],[data-email]');
        if (acc && vis(acc)) { acc.click(); return 'ACCOUNT'; }
        var c = clickText(/^(continue|계속|허용|allow|다음|next|확인|confirm)$/i) || clickText(/(continue|계속|allow|허용)/i);
        return c ? 'CONSENT:' + c : 'GOOGLE_WAIT';
      }
      if (provider === 'naver') { var n = clickText(/(네이버|naver).{0,6}(로그인|계속|login|continue)|(로그인|login).{0,6}(네이버|naver)/i); return n ? 'CLICK:' + n : 'WAIT'; }
      var g = clickText(/(google|구글).{0,12}(계속|로그인|continue|sign|log)|(continue|sign in|log in|계속|로그인).{0,12}(google|구글)/i);
      if (g) return 'CLICK:' + g;
      var login = clickText(/^(log in|sign in|로그인|시작하기|get started|sign up)$/i);
      return login ? 'LOGIN_PAGE:' + login : 'WAIT';
    },
    pending: function () { var el = api.input(); return el ? (text(el) || el.value || '').length : -1; }
  };
  window.__hjtkAi = api;
})();
