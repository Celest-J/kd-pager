// Puts the pager button into KD's own nav, right after the <KD/> logo, using KD's own button class.
// Next.js re-renders the nav, so an observer puts it back whenever it disappears.
(() => {
  const ICON = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="2" y="6" width="20" height="12" rx="2"></rect><rect x="5" y="9" width="9" height="4" rx="1"></rect><line x1="17" y1="10" x2="19" y2="10"></line><line x1="17" y1="14" x2="19" y2="14"></line></svg>';
  const put = () => {
    const box = document.querySelector('.nav-logo-container');
    if (!box || box.querySelector('#kdapp-pager')) return;
    box.style.display = 'flex'; box.style.alignItems = 'center'; box.style.gap = '10px';
    const b = document.createElement('button');
    b.id = 'kdapp-pager'; b.type = 'button'; b.className = 'nav-btn-circle';
    b.title = 'Pager'; b.setAttribute('aria-label', 'Pager settings');
    b.innerHTML = ICON;
    // state dot: green = pager on, grey = off. Native updates it through __kdappSetPager after a handover or logout.
    b.style.position = 'relative';
    const dot = document.createElement('span');
    dot.id = 'kdapp-pager-dot';
    dot.style.cssText = 'position:absolute;top:2px;right:2px;width:8px;height:8px;border-radius:50%;border:1.5px solid #000;';
    b.appendChild(dot);
    b.onclick = () => window.KDApp.openSettings();
    box.appendChild(b);
    window.__kdappSetPager(window.KDApp.pagerOn());
  };
  window.__kdappSetPager = (on) => {
    const d = document.getElementById('kdapp-pager-dot');
    if (d) d.style.background = on ? '#22c55e' : '#6b7280';
    const b = document.getElementById('kdapp-pager');
    if (b) b.title = on ? 'Pager on' : 'Pager off';
  };
  put();
  if (!window.__kdappObs) {
    window.__kdappObs = new MutationObserver(put);
    // the root, not <body>: Next.js can swap <body> out, which would leave the observer watching a dead node
    window.__kdappObs.observe(document.documentElement, { childList: true, subtree: true });
  }
})();
