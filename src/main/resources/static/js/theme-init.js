/* Anti-flash: apply the saved theme before the first paint.
   Loaded synchronously (no defer) from <head> on every page, so it must stay
   tiny. Shared by layout.html and login.html. */
(function () {
  if (localStorage.getItem('pochitushki-theme') === 'dark') {
    document.documentElement.classList.add('dark');
  }
})();
