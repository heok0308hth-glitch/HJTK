/* HJTK web AI adapter — 로그인한 AI 웹(Claude·ChatGPT·Gemini·Grok)을 키 없이 쓰기 위한 얇은 손.
   한 번 넣으면 window.__hjtkAi 가 생긴다. 설정(CFG)은 앱이 사이트별로 앞에 붙여 준다.
   원칙: 화면에 보이는 입력창에 글을 넣고 보내기를 누른 뒤, 마지막 답 요소의 글자를 읽는다. 클릭은 보내기 한 번뿐. */
(function () {
  if (window.__hjtkAi && window.__hjtkAi.v === 3) { window.__hjtkAi.cfg = window.__HJTK_CFG || window.__hjtkAi.cfg; return; }
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
  function vis(el) { if (!el) return false; var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  function text(el) { return el ? (el.innerText || el.textContent || '').replace(/ /g, ' ').trim() : ''; }
  function loginish() {
    var h = location.href;
    if (/login|signin|sign-in|auth|accounts\.google|oauth|identifier/i.test(h)) return true;
    return !!document.querySelector('input[type=password], input[type=email], input[name=email], input[autocomplete=username]');
  }
  var api = {
    v: 3, cfg: cfg,
    input: function () { return q(api.cfg.input); },
    state: function () {
      if (document.readyState === 'loading') return 'LOADING';
      if (api.input()) return 'READY';
      if (/challenge|cf-chl|captcha/i.test(document.documentElement.innerHTML.slice(0, 4000)) && /just a moment|확인|verify/i.test(document.title + text(document.body).slice(0, 200))) return 'CHALLENGE';
      if (loginish()) return 'LOGIN';
      return 'LOADING';
    },
    count: function () { return all(api.cfg.answer).length; },
    last: function () { var els = all(api.cfg.answer); return els.length ? text(els[els.length - 1]) : ''; },
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
    pending: function () { var el = api.input(); return el ? (text(el) || el.value || '').length : -1; }
  };
  window.__hjtkAi = api;
})();
