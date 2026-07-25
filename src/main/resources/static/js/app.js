/* Web UI behaviour, extracted from layout.html so the CSP can stay on
   script-src 'self' (no 'unsafe-inline', no 'unsafe-eval').

   Two rules keep it that way — please preserve them when editing templates:
     1. No inline <script> and no on*="" attributes. Mark elements with
        data-* hooks and handle them in the delegated listeners below.
     2. No htmx hx-on / hx-vals='js:...' / hx-trigger event filters — htmx
        evaluates those with new Function(), which needs 'unsafe-eval'.
        htmx.config.allowEval is set to false below so this fails loudly
        instead of silently reintroducing the requirement. */

/* ── Add post form — optimistic UI ── */
function initAddPostForm() {
  var form = document.getElementById('add-post-form');
  if (!form || form._bound) return;
  form._bound = true;

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  form.addEventListener('submit', function (e) {
    e.preventDefault();
    var inp  = document.getElementById('add-post-url');
    var btn  = form.querySelector('button[type=submit]');
    var url  = inp.value.trim();
    if (!url) return;

    inp.disabled = true;
    btn.disabled = true;
    inp.value    = '';

    var domain = url;
    try { domain = new URL(url).hostname.replace(/^www\./, ''); } catch (ex) {}
    var abbr      = esc(domain.substring(0, 2).toUpperCase());
    var domainEsc = esc(domain);

    var tempId   = 'card-optimistic-' + Date.now();
    var tempCard = document.createElement('div');
    tempCard.id        = tempId;
    tempCard.className = 'post-card';
    tempCard.style.opacity = '0.5';
    tempCard.innerHTML =
      '<div class="card-list-view">' +
        '<div style="width:32px;height:32px;flex-shrink:0;border-radius:6px;background:var(--border);' +
             'display:flex;align-items:center;justify-content:center;">' +
          '<span style="font-size:0.62rem;color:var(--muted);font-family:sans-serif;">' + abbr + '</span>' +
        '</div>' +
        '<div style="flex:1;min-width:0;">' +
          '<span class="font-display" style="font-size:1.05rem;font-weight:600;color:var(--text);' +
               'display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' + domainEsc + '</span>' +
          '<p style="color:var(--muted);font-style:italic;font-size:0.82rem;margin:0.15rem 0 0;">Добавляется…</p>' +
        '</div>' +
      '</div>' +
      '<div class="card-grid-view" style="border:1px solid var(--border);border-radius:8px;overflow:hidden;background:var(--bg);width:100%;">' +
        '<div style="aspect-ratio:16/9;background:var(--border);"></div>' +
        '<div class="card-grid-body" style="padding:0.65rem 0.75rem;">' +
          '<span class="font-display" style="font-size:0.88rem;font-weight:600;color:var(--text);' +
               'display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;">' + domainEsc + '</span>' +
          '<p style="font-size:0.72rem;color:var(--muted);font-style:italic;margin:auto 0 0;">Добавляется…</p>' +
        '</div>' +
      '</div>';

    var postList = document.getElementById('post-list');
    if (postList) postList.insertBefore(tempCard, postList.firstChild);

    fetch('/api/v1/posts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'url=' + encodeURIComponent(url)
    })
    .then(function (r) {
      if (!r.ok) throw new Error('error');
      return r.text();
    })
    .then(function (html) {
      var temp = document.getElementById(tempId);
      if (!temp) return;
      var wrapper = document.createElement('div');
      wrapper.innerHTML = html.trim();
      var realCard = wrapper.firstElementChild;
      if (realCard) {
        temp.parentNode.replaceChild(realCard, temp);
        if (typeof htmx !== 'undefined') htmx.process(realCard);
        initFavicons(realCard);
        var pl = document.getElementById('post-list');
        if (pl && pl.classList.contains('view-grid')) initOgImages(realCard);
      }
    })
    .catch(function () {
      var temp = document.getElementById(tempId);
      if (temp) temp.remove();
      inp.value = url;
    })
    .finally(function () {
      inp.disabled = false;
      btn.disabled = false;
      inp.focus();
    });
  });
}

/* ── Theme ── */
function toggleTheme() {
  var isDark = document.documentElement.classList.toggle('dark');
  localStorage.setItem('pochitushki-theme', isDark ? 'dark' : 'sepia');
  var icon = document.getElementById('theme-icon-rail');
  if (icon) icon.textContent = isDark ? '🌙' : '☀️';
}

/* ── Favicon loader ── */
function initFavicons(root) {
  (root || document).querySelectorAll('.post-favicon[data-url]').forEach(function (el) {
    try {
      var domain = new URL(el.dataset.url).hostname;
      var img = el.querySelector('img');
      var fallback = el.querySelector('.fav-fallback');
      fallback.textContent = domain.replace(/^www\./, '').substring(0, 2).toUpperCase();
      img.src = 'https://www.google.com/s2/favicons?domain=' + domain + '&sz=32';
      img.onload  = function () { img.style.display = 'block'; fallback.style.display = 'none'; };
      img.onerror = function () { img.style.display = 'none'; };
    } catch (e) {}
  });
}

/* ── OG image loader (grid mode only) ── */
function initOgImages(root) {
  (root || document).querySelectorAll('.post-og[data-id]:not([data-og-loaded])').forEach(function (el) {
    el.setAttribute('data-og-loaded', '1');
    var id = el.dataset.id;
    var type = el.dataset.type || 'unread';
    fetch('/api/v1/posts/' + id + '/og-image?type=' + type)
      .then(function (r) { return r.ok ? r.json() : Promise.reject(); })
      .then(function (data) {
        var img = el.querySelector('img.og-img');
        var fallback = el.querySelector('.og-fallback');
        img.src = data.ogImageUrl;
        img.onload = function () {
          img.style.display = 'block';
          if (fallback) fallback.style.display = 'none';
        };
        img.onerror = function () { img.style.display = 'none'; };
      })
      .catch(function () { /* keep fallback visible */ });
  });
}

/* ── Favorite toggle (client-side only, no card re-render) ── */
function toggleFavBtn(btn) {
  btn.closest('.post-card').querySelectorAll('[data-fav-btn]').forEach(function (b) {
    b.textContent = b.textContent.trim() === '☆' ? '⭐' : '☆';
  });
}

/* ── View mode ── */
function setViewMode(mode) {
  var postList = document.getElementById('post-list');
  if (!postList) return;
  postList.classList.toggle('view-grid', mode === 'grid');
  document.getElementById('view-list-btn')?.classList.toggle('active', mode === 'list');
  document.getElementById('view-grid-btn')?.classList.toggle('active', mode === 'grid');
  localStorage.setItem('pochitushki-view', mode);
  document.cookie = 'pochitushki-view=' + mode + '; path=/; max-age=31536000; SameSite=Lax';
  if (mode === 'grid') initOgImages();
}

/* ── Infinite scroll ── */
function initScrollSentinel() {
  var sentinel = document.querySelector('.load-more-sentinel:not([data-sentinel-init])');
  if (!sentinel) return;
  sentinel.setAttribute('data-sentinel-init', '1');

  function doLoad() {
    if (sentinel.dataset.loading) return;
    sentinel.dataset.loading = '1';
    htmx.trigger(sentinel, 'loadMore');
  }

  var observer = new IntersectionObserver(function (entries, obs) {
    if (!entries[0].isIntersecting) return;
    var userScrolled = window.scrollY > 50;
    var pageShort    = document.body.scrollHeight <= window.innerHeight + 100;
    if (userScrolled || pageShort) {
      obs.disconnect();
      doLoad();
    } else {
      /* sentinel is already visible but user hasn't scrolled — wait for real scroll */
      obs.disconnect();
      window.addEventListener('scroll', function onScroll() {
        if (sentinel.getBoundingClientRect().top < window.innerHeight) {
          window.removeEventListener('scroll', onScroll);
          doLoad();
        }
      }, { passive: true });
    }
  }, { threshold: 0.1 });

  observer.observe(sentinel);
}

/* ── Delegated clicks ──
   One listener on document, so it also covers cards HTMX inserts later.
   Replaces the former on*="" attributes. */
document.addEventListener('click', function (e) {
  var viewBtn = e.target.closest('[data-view-mode]');
  if (viewBtn) {
    setViewMode(viewBtn.dataset.viewMode);
    return;
  }

  var action = e.target.closest('[data-action]');
  if (!action) return;

  switch (action.dataset.action) {
    case 'toggle-theme':
      toggleTheme();
      break;
    case 'delete-show':
      action.closest('.post-card')?.classList.add('confirming');
      break;
    case 'delete-hide':
      action.closest('.post-card')?.classList.remove('confirming');
      break;
  }
});

/* ── Delegated htmx completions ──
   Replaces hx-on--after-request, which htmx evaluates via new Function(). */
document.addEventListener('htmx:afterRequest', function (e) {
  if (!e.detail.successful) return;
  var el = e.detail.elt;
  if (!el || !el.matches) return;

  if (el.matches('[data-fav-btn]')) {
    toggleFavBtn(el);
  } else if (el.matches('[data-reload-on-success]')) {
    location.reload();
  } else if (el.matches('[data-remove-on-success]')) {
    el.remove();
  }
});

document.addEventListener('DOMContentLoaded', function () {
  /* Fail loudly if an hx-on / js: expression sneaks back in, rather than
     quietly needing 'unsafe-eval' again. */
  if (typeof htmx !== 'undefined') htmx.config.allowEval = false;

  var icon = document.getElementById('theme-icon-rail');
  if (icon) icon.textContent = document.documentElement.classList.contains('dark') ? '🌙' : '☀️';
  initFavicons();
  initAddPostForm();
  /* view-grid class is already applied server-side from cookie — only sync button states */
  var postList = document.getElementById('post-list');
  var mode = postList && postList.classList.contains('view-grid') ? 'grid' : 'list';
  document.getElementById('view-list-btn')?.classList.toggle('active', mode === 'list');
  document.getElementById('view-grid-btn')?.classList.toggle('active', mode === 'grid');
  if (mode === 'grid') initOgImages();
  initScrollSentinel();
});

document.addEventListener('htmx:afterSwap', function (e) {
  /* For outerHTML swaps the target is already detached; search from its
     parent so the newly inserted element is found. Fall back to document. */
  var root = document.contains(e.detail.target)
    ? e.detail.target
    : (e.detail.target.parentElement || document);
  initFavicons(root);
  var postList = document.getElementById('post-list');
  if (postList && postList.classList.contains('view-grid')) {
    initOgImages(root);
  }
  initScrollSentinel();
});
