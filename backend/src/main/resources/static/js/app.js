/**
 * Spends Predictor — Lightweight HTMX-compatible hypermedia enhancer & CSRF helper.
 * Works 100% offline with zero external CDN dependencies while supporting progressive enhancement.
 */
(function () {
  'use strict';

  function getCsrfToken() {
    const match = document.cookie
      .split('; ')
      .find((row) => row.startsWith('sp_csrf='));
    return match ? decodeURIComponent(match.split('=')[1]) : '';
  }

  // Copy SHA-256 audit seal to clipboard
  window.copyAuditSeal = function (btn, sealText) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(sealText).then(function () {
        const orig = btn.textContent;
        btn.textContent = 'Copied!';
        setTimeout(function () {
          btn.textContent = orig;
        }, 2000);
      });
    }
  };

  // Read selected CSV file into hidden textarea for CSV statement import
  window.handleCsvFileSelect = function (fileInput, targetTextareaId) {
    const file = fileInput.files && fileInput.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = function (evt) {
      const textarea = document.getElementById(targetTextareaId);
      if (textarea && typeof evt.target.result === 'string') {
        textarea.value = evt.target.result;
      }
    };
    reader.readAsText(file);
  };

  // Progressive HTMX fragment swap handler for elements with [hx-get] or [hx-post]
  async function executeHxRequest(el, event) {
    const getUrl = el.getAttribute('hx-get');
    const postUrl = el.getAttribute('hx-post');
    const method = postUrl ? 'POST' : 'GET';
    let url = postUrl || getUrl;
    if (!url) return;

    if (event) {
      event.preventDefault();
    }

    const targetSelector = el.getAttribute('hx-target');
    const swapMode = el.getAttribute('hx-swap') || 'innerHTML';
    const pushUrl = el.getAttribute('hx-push-url');

    const headers = {
      'HX-Request': 'true',
      'X-CSRF-Token': getCsrfToken(),
    };

    let body = undefined;
    if (method === 'POST') {
      if (el.tagName === 'FORM') {
        body = new URLSearchParams(new FormData(el));
      } else if (el.tagName === 'SELECT' || el.tagName === 'INPUT') {
        const params = new URLSearchParams();
        params.set(el.name || 'value', el.value);
        body = params;
      } else {
        body = new URLSearchParams();
      }
      headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
    } else if (el.tagName === 'FORM') {
      const params = new URLSearchParams(new FormData(el)).toString();
      if (params) {
        url += (url.includes('?') ? '&' : '?') + params;
      }
    } else if (el.tagName === 'SELECT' || el.tagName === 'INPUT') {
      const form = el.closest('form');
      if (form) {
        const params = new URLSearchParams(new FormData(form)).toString();
        if (params) {
          url += (url.includes('?') ? '&' : '?') + params;
        }
      }
    }

    el.setAttribute('aria-busy', 'true');
    try {
      const resp = await fetch(url, {
        method: method,
        credentials: 'same-origin',
        headers: headers,
        body: body,
      });

      const hxRedirect = resp.headers.get('HX-Redirect');
      if (hxRedirect) {
        window.location.href = hxRedirect;
        return;
      }

      const html = await resp.text();
      if (targetSelector) {
        const targetEl = document.querySelector(targetSelector);
        if (targetEl) {
          if (swapMode === 'outerHTML') {
            targetEl.outerHTML = html;
          } else {
            targetEl.innerHTML = html;
          }
        }
      } else {
        document.open();
        document.write(html);
        document.close();
      }

      if (pushUrl === 'true' && method === 'GET') {
        window.history.pushState({}, '', url);
      }
    } finally {
      el.removeAttribute('aria-busy');
    }
  }

  document.addEventListener('click', function (e) {
    const el = e.target.closest('[hx-get], [hx-post]');
    if (!el || el.tagName === 'FORM' || el.tagName === 'SELECT' || el.tagName === 'INPUT') {
      return;
    }
    executeHxRequest(el, e);
  });

  document.addEventListener('change', function (e) {
    const el = e.target.closest('select[hx-post], select[hx-get], input[hx-get]');
    if (!el) return;
    executeHxRequest(el, e);
  });

  document.addEventListener('submit', function (e) {
    const form = e.target.closest('form[hx-post], form[hx-get]');
    if (!form) return;
    executeHxRequest(form, e);
  });
})();
