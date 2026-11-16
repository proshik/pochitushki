/* Mini App entry (/tg): sign in with Telegram.WebApp.initData, then go to the site.
   No inline JS anywhere (CSP script-src 'self' + telegram.org on this page only). */
(function () {
  function show(id) {
    ['tg-loading', 'tg-browser', 'tg-outside', 'tg-error'].forEach(function (name) {
      var el = document.getElementById(name);
      if (el) el.hidden = name !== id;
    });
  }

  var tg = window.Telegram && window.Telegram.WebApp;
  if (!tg || !tg.initData) {
    // Opened in a normal browser: there is no signed launch data to log in with.
    show('tg-outside');
    return;
  }
  tg.ready();
  tg.expand();

  document.getElementById('tg-open-browser').addEventListener('click', function () {
    tg.openLink(location.origin + '/');
  });

  var page = new URLSearchParams(location.search).get('p');
  fetch('/auth/telegram/webapp', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ initData: tg.initData, page: page })
  })
    .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
    .then(function (data) {
      // Telegram Web frames the page cross-site, and a SameSite=Lax cookie does not survive
      // that: check it came back before navigating into a login loop.
      return fetch('/auth/telegram/webapp/session', { credentials: 'same-origin' }).then(function (s) {
        if (s.status === 204) location.replace(data.redirect);
        else show('tg-browser');
      });
    })
    .catch(function () { show('tg-error'); });
})();
