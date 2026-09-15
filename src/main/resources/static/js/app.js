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

/* ── Labels ──
   The label list belongs to the user, not to a card, so it is fetched once and
   shared by every picker on the page rather than rendered into twenty cards. */
var labelCache = null;

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function loadLabels(force) {
  if (labelCache && !force) return Promise.resolve(labelCache);
  return fetch('/api/v1/labels', { headers: { 'Accept': 'application/json' } })
    .then(function (r) { return r.ok ? r.json() : []; })
    .then(function (list) { labelCache = list; return list; });
}

/* The chips under the card, kept in step with the picker without a re-render. */
function cardChipRow(card) {
  return card.querySelector('.cell-meta') || card.querySelector('.row-sub');
}

function addChip(card, label) {
  var row = cardChipRow(card);
  if (!row || row.querySelector('[data-label-id="' + label.id + '"]')) return;
  var chip = document.createElement('a');
  chip.className = 'label-tag';
  chip.href = '/labels/' + label.id;
  chip.textContent = '#' + label.name;
  chip.setAttribute('data-label-id', label.id);
  row.appendChild(chip);
}

function removeChip(card, labelId) {
  var row = cardChipRow(card);
  var chip = row && row.querySelector('[data-label-id="' + labelId + '"]');
  if (chip) chip.remove();
}

function renderLabelPanel(panel, labels) {
  var attached = {};
  var card = panel.closest('.post-card');
  var row = cardChipRow(card);
  if (row) {
    row.querySelectorAll('.label-tag').forEach(function (chip) {
      var name = chip.textContent.replace(/^#/, '');
      attached[name] = true;
    });
  }

  var rows = labels.map(function (l) {
    return '<button type="button" class="label-opt' + (attached[l.name] ? ' on' : '') +
      '" data-label-id="' + l.id + '" data-label-name="' + esc(l.name) + '">' +
      '<span class="label-opt-box" aria-hidden="true"></span>' + esc(l.name) + '</button>';
  }).join('');

  panel.innerHTML =
    '<div class="label-opts">' + rows + '</div>' +
    '<form class="label-panel-add">' +
      '<input type="text" maxlength="40" required placeholder="' +
        esc(i18n('newLabel', 'New label')) + '">' +
    '</form>';
}

function labelRequest(method, postId, postType, labelId) {
  return fetch('/api/v1/posts/' + postId + '/labels/' + labelId + '?type=' + postType, { method: method });
}

function initLabelPicker() {
  document.addEventListener('click', function (e) {
    /* Open / close */
    var opener = e.target.closest('[data-action="labels-show"]');
    if (opener) {
      var card = opener.closest('.post-card');
      var panel = card.querySelector('.label-panel');
      var opening = !card.classList.contains('labelling');
      document.querySelectorAll('.post-card.labelling').forEach(function (c) {
        c.classList.remove('labelling');
      });
      if (!opening) return;
      card.classList.add('labelling');
      loadLabels(false).then(function (labels) { renderLabelPanel(panel, labels); });
      return;
    }

    /* Toggle one label on this post */
    var opt = e.target.closest('.label-opt');
    if (opt) {
      var panel2 = opt.closest('.label-panel');
      var card2 = opt.closest('.post-card');
      var on = opt.classList.contains('on');
      var labelId = opt.dataset.labelId;
      opt.disabled = true;
      labelRequest(on ? 'DELETE' : 'POST', panel2.dataset.postId, panel2.dataset.postType, labelId)
        .then(function (r) {
          if (!r.ok) return;
          opt.classList.toggle('on', !on);
          if (on) removeChip(card2, labelId);
          else addChip(card2, { id: labelId, name: opt.dataset.labelName });
        })
        .finally(function () { opt.disabled = false; });
      return;
    }

    /* Clicking anywhere else closes an open picker */
    if (!e.target.closest('.label-panel')) {
      document.querySelectorAll('.post-card.labelling').forEach(function (c) {
        c.classList.remove('labelling');
      });
    }
  });

  /* Create-and-attach from inside the picker */
  document.addEventListener('submit', function (e) {
    var form = e.target.closest('.label-panel-add');
    if (!form) return;
    e.preventDefault();
    var input = form.querySelector('input');
    var name = input.value.trim();
    if (!name) return;
    var panel = form.closest('.label-panel');
    var card = panel.closest('.post-card');
    input.disabled = true;

    fetch('/api/v1/labels', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: name })
    })
      .then(function (r) {
        if (!r.ok) throw new Error('create failed');
        return r.json();
      })
      .then(function (label) {
        return labelRequest('POST', panel.dataset.postId, panel.dataset.postType, label.id)
          .then(function () {
            addChip(card, label);
            input.value = '';
            return loadLabels(true);
          })
          .then(function (labels) { renderLabelPanel(panel, labels); });
      })
      .catch(function () { /* the input keeps the text so it can be retried */ })
      .finally(function () { input.disabled = false; });
  });
}

/* ── /labels page ── */
function initLabelsPage() {
  var box = document.getElementById('labels-box');
  if (!box) return;

  box.addEventListener('click', function (e) {
    var row = e.target.closest('.labels-row');

    var renameBtn = e.target.closest('[data-action="label-rename"]');
    if (renameBtn) {
      startRename(row, renameBtn.dataset.name);
      return;
    }

    var deleteBtn = e.target.closest('[data-action="label-delete"]');
    if (deleteBtn) {
      row.classList.add('confirming');
      return;
    }

    if (e.target.closest('[data-action="label-delete-no"]')) {
      row.classList.remove('confirming');
      return;
    }

    if (e.target.closest('[data-action="label-delete-yes"]')) {
      fetch('/api/v1/labels/' + row.dataset.labelId, { method: 'DELETE' })
        .then(function (r) { if (r.ok) row.remove(); });
    }
  });

  function showRowError(row, message) {
    clearRowError(row);
    var note = document.createElement('span');
    note.className = 'labels-error';
    note.setAttribute('role', 'alert');
    note.textContent = message;
    row.appendChild(note);
  }

  function clearRowError(row) {
    var note = row.querySelector('.labels-error');
    if (note) note.remove();
  }

  function startRename(row, currentName) {
    if (row.querySelector('.labels-rename')) return;
    var nameEl = row.querySelector('.labels-name');
    var form = document.createElement('form');
    form.className = 'labels-rename';
    form.innerHTML = '<input type="text" maxlength="40" required>';
    var input = form.querySelector('input');
    input.value = currentName;
    nameEl.hidden = true;
    row.insertBefore(form, nameEl.nextSibling);
    input.focus();
    input.select();

    function cancel() {
      form.remove();
      clearRowError(row);
      nameEl.hidden = false;
    }

    input.addEventListener('keydown', function (ev) {
      if (ev.key === 'Escape') cancel();
    });

    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var name = input.value.trim();
      if (!name || name === currentName) { cancel(); return; }
      input.disabled = true;
      fetch('/api/v1/labels/' + row.dataset.labelId, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: name })
      }).then(function (r) {
        if (r.ok) {
          nameEl.textContent = '#' + name;
          row.querySelectorAll('[data-name]').forEach(function (b) { b.dataset.name = name; });
          labelCache = null;
          cancel();
        } else {
          /* 409: the user already has that name. A native validation bubble vanishes on
             its own, so the message stays in the row until the text changes. */
          showRowError(row, r.status === 409 ? (box.dataset.taken || 'Name taken') : ' ');
          input.disabled = false;
          input.focus();
          input.addEventListener('input', function clear() {
            clearRowError(row);
            input.removeEventListener('input', clear);
          });
        }
      });
    });
  }

  var createForm = document.getElementById('label-create-form');
  if (createForm) {
    createForm.addEventListener('submit', function (e) {
      e.preventDefault();
      var input = document.getElementById('label-create-name');
      var name = input.value.trim();
      if (!name) return;
      input.disabled = true;
      fetch('/api/v1/labels', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: name })
      })
        .then(function (r) { if (!r.ok) throw new Error('create failed'); return r.json(); })
        .then(function () { location.reload(); })
        .catch(function () { input.disabled = false; });
    });
  }
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

/* ── Failed htmx requests ──
   Without this a failed archive/delete/favourite click did nothing at all: the
   afterRequest chain above returns early on !successful, so the card stayed put
   and the reader had no way to tell a lost request from an ignored click. */
document.addEventListener('htmx:responseError', function (e) {
  var status = e.detail.xhr && e.detail.xhr.status;
  if (status === 401) { location.href = '/login'; return; }
  showActionError(status === 409 ? 'conflict' : 'error');
});

document.addEventListener('htmx:sendError', function () { showActionError('error'); });

function showActionError(kind) {
  var i18n = document.getElementById('app-i18n');
  var msg = (i18n && (kind === 'conflict' ? i18n.dataset.errorConflict : i18n.dataset.errorGeneric))
    || (kind === 'conflict' ? 'Уже перенесено' : 'Не получилось, попробуйте ещё раз');
  document.querySelectorAll('.add-error').forEach(function (t) { t.remove(); });
  var toast = document.createElement('div');
  toast.className = 'add-error';
  toast.setAttribute('role', 'alert');
  toast.textContent = msg;
  document.body.appendChild(toast);
  setTimeout(function () { toast.remove(); }, 4000);
}

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
  initLabelPicker();
  initLabelsPage();
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
