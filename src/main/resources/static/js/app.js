/* Web UI behaviour, kept out of templates so the CSP can stay on
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

  function showAddError() {
    var msg = form.dataset.errorText || 'Error';
    var toast = document.createElement('div');
    toast.className = 'add-error';
    toast.setAttribute('role', 'alert');
    toast.textContent = msg;
    document.body.appendChild(toast);
    setTimeout(function () { toast.remove(); }, 4000);
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
    var adding    = esc(form.dataset.addingText || '…');

    var postList = document.getElementById('post-list');
    var shelf    = postList && postList.classList.contains('shelf');

    var tempId   = 'card-optimistic-' + Date.now();
    var tempCard = document.createElement('div');
    tempCard.id        = tempId;
    tempCard.className = 'post-card';
    tempCard.style.opacity = '0.55';
    /* Simplified cover placeholder — the real card fragment replaces it on success. */
    tempCard.innerHTML = shelf
      ? '<span class="cover co-mono cv-5">' +
          '<span class="cover-dom">' + domainEsc + '</span>' +
          '<span class="cover-big">' + abbr + '</span>' +
          '<span class="cover-cap">' + adding + '</span>' +
        '</span>' +
        '<span class="cell-title">' + domainEsc + '</span>' +
        '<span class="cell-meta">' + adding + '</span>'
      : '<span class="mini-cover cv-5">' + abbr + '</span>' +
        '<span style="min-width:0;">' +
          '<span class="row-title">' + domainEsc + '</span>' +
          '<span class="row-sub">' + adding + '</span>' +
        '</span>' +
        '<span></span>';

    if (postList) postList.insertBefore(tempCard, postList.firstChild);

    fetch('/api/v1/posts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'url=' + encodeURIComponent(url) + '&view=' + (shelf ? 'shelf' : 'list')
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
      }
    })
    .catch(function () {
      var temp = document.getElementById(tempId);
      if (temp) temp.remove();
      inp.value = url;
      showAddError();
    })
    .finally(function () {
      inp.disabled = false;
      btn.disabled = false;
      inp.focus();
    });
  });
}

/* ── Undo toast after archiving ──
   Archiving re-inserts the row under a new id, so the id to undo comes back in
   the response body. The hero button reloads the page (the "next to read" card
   has to change), which would throw the toast away — so that path parks the id
   in sessionStorage and the toast is raised again after the reload. */
var UNDO_KEY = 'pochitushki-undo';

function i18n(name, fallback) {
  var el = document.getElementById('app-i18n');
  return (el && el.dataset[name]) || fallback;
}

function archivedIdFrom(xhr) {
  try {
    var id = JSON.parse(xhr.responseText).archivedId;
    return typeof id === 'number' ? id : null;
  } catch (ex) {
    return null;
  }
}

function showUndoToast(archivedId) {
  document.querySelectorAll('.toast').forEach(function (t) { t.remove(); });

  var toast = document.createElement('div');
  toast.className = 'toast';
  toast.setAttribute('role', 'status');

  var label = document.createElement('span');
  label.textContent = i18n('archived', 'Archived');

  var undo = document.createElement('button');
  undo.type = 'button';
  undo.className = 'toast-action';
  undo.textContent = i18n('undo', 'Undo');
  undo.addEventListener('click', function () {
    undo.disabled = true;
    fetch('/api/v1/posts/' + archivedId + '/unread', { method: 'POST' })
      .then(function (r) {
        if (!r.ok) throw new Error('undo failed');
        location.reload();
      })
      .catch(function () {
        undo.disabled = false;
      });
  });

  toast.appendChild(label);
  toast.appendChild(undo);
  document.body.appendChild(toast);

  setTimeout(function () { toast.remove(); }, 7000);
}

function stashUndo(archivedId) {
  try {
    sessionStorage.setItem(UNDO_KEY, JSON.stringify({ id: archivedId, at: Date.now() }));
  } catch (ex) { /* private mode — the toast is a nicety, not a requirement */ }
}

function popStashedUndo() {
  var raw;
  try {
    raw = sessionStorage.getItem(UNDO_KEY);
    sessionStorage.removeItem(UNDO_KEY);
  } catch (ex) {
    return;
  }
  if (!raw) return;
  try {
    var stash = JSON.parse(raw);
    /* Only for the reload it was parked across, not for a page opened later. */
    if (stash && stash.id && Date.now() - stash.at < 5000) showUndoToast(stash.id);
  } catch (ex) { /* ignore malformed stash */ }
}

/* ── Theme — icon swap is pure CSS (html.dark .icon-sun / .icon-moon) ── */
function toggleTheme() {
  var isDark = document.documentElement.classList.toggle('dark');
  localStorage.setItem('pochitushki-theme', isDark ? 'dark' : 'sepia');
}

/* ── Favorite toggle (client-side only, no card re-render) ── */
function toggleFavBtn(btn) {
  btn.closest('.post-card').querySelectorAll('[data-fav-btn]').forEach(function (b) {
    var fav = b.classList.toggle('is-fav');
    b.setAttribute('aria-pressed', fav ? 'true' : 'false');
  });
}

/* ── View mode — the server renders one markup per mode, so switching reloads.
   Cookie value keeps the legacy names: list | grid (grid == shelf). ── */
function setViewMode(mode) {
  localStorage.setItem('pochitushki-view', mode);
  document.cookie = 'pochitushki-view=' + mode + '; path=/; max-age=31536000; SameSite=Lax';
  location.reload();
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

/* ── Broken og:image → typographic fallback (error doesn't bubble, so capture) ── */
document.addEventListener('error', function (e) {
  var t = e.target;
  if (t && t.matches && t.matches('img.cover-img')) {
    var cover = t.closest('.cover');
    if (cover) cover.classList.add('og-failed');
  }
}, true);

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

  /* Deliberately not part of the chain below: the hero button is both an
     archive trigger and a reload trigger, and needs both to run. */
  if (el.matches('[data-archive-undo]')) {
    var archivedId = archivedIdFrom(e.detail.xhr);
    if (archivedId) {
      if (el.matches('[data-reload-on-success]')) stashUndo(archivedId);
      else showUndoToast(archivedId);
    }
  }

  if (el.matches('[data-unfav-remove]')) {
    /* Favorites showcase: removing the star removes the card right away. */
    el.closest('.post-card')?.remove();
  } else if (el.matches('[data-fav-btn]')) {
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

  /* Covers whose og image failed BEFORE this (deferred) script attached the
     capture listener above. */
  document.querySelectorAll('img.cover-img').forEach(function (img) {
    if (img.complete && img.naturalWidth === 0) {
      img.closest('.cover')?.classList.add('og-failed');
    }
  });

  popStashedUndo();
  initAddPostForm();
  /* The mode class is applied server-side from the cookie — only sync button states. */
  var postList = document.getElementById('post-list');
  var mode = postList && postList.classList.contains('shelf') ? 'grid' : 'list';
  document.getElementById('view-list-btn')?.classList.toggle('active', mode === 'list');
  document.getElementById('view-grid-btn')?.classList.toggle('active', mode === 'grid');
  initScrollSentinel();
});

document.addEventListener('htmx:afterSwap', function () {
  initScrollSentinel();
});
