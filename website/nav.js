/* Shared navigation + footer injector */
function initSite() {
  const PAGES = [
    { href: 'index.html',     label: '首页',     icon: 'home' },
    { href: 'features.html',  label: '功能特性', icon: 'stars' },
    { href: 'download.html',  label: '下载',     icon: 'download' },
    { href: 'changelog.html', label: '更新日志', icon: 'history' },
    { href: 'community.html', label: '社区',     icon: 'groups' },
  ];

  const current = location.pathname.split('/').pop() || 'index.html';

  /* ── Top App Bar ── */
  const bar = document.querySelector('.top-app-bar');
  if (bar) {
    bar.innerHTML = `
      <a class="top-bar-brand" href="index.html">
        <img src="icon.png" alt="MD3L"/>
        <span class="top-bar-title">MD3L</span>
      </a>
      <span class="top-bar-spacer"></span>
      <nav class="top-bar-nav">
        ${PAGES.map(p => `
          <a class="nav-item${current === p.href ? ' active' : ''}" href="${p.href}">
            <span class="ms">${p.icon}</span>${p.label}
          </a>`).join('')}
      </nav>
      <button class="hamburger" onclick="toggleMobileNav()" aria-label="菜单">
        <span class="ms">menu</span>
      </button>`;
  }

  /* ── Mobile nav ── */
  const mobileNav = document.querySelector('.mobile-nav');
  if (mobileNav) {
    mobileNav.innerHTML = PAGES.map(p => `
      <a class="nav-item${current === p.href ? ' active' : ''}" href="${p.href}">
        <span class="ms">${p.icon}</span>${p.label}
      </a>`).join('');
  }

  /* ── Footer ── */
  const footer = document.querySelector('.site-footer .container');
  if (footer) {
    footer.innerHTML = `
      <div class="footer-grid">
        <div>
          <div class="footer-brand">
            <img src="icon.png" alt="MD3L"/>
            <span>MD3L 启动器</span>
          </div>
          <p class="footer-tagline">
            开源免费的 Minecraft 启动器<br/>同时支持 Java 版与基岩版<br/>
            基于 Kotlin + Compose Desktop · Material Design 3
          </p>
        </div>
        <div class="footer-group">
          <h4>产品</h4>
          ${PAGES.map(p=>`<a class="footer-link" href="${p.href}">${p.label}</a>`).join('')}
        </div>
        <div class="footer-group">
          <h4>社区</h4>
          <a class="footer-link" href="https://github.com/zhou1844/MD3L" target="_blank">GitHub</a>
          <a class="footer-link" href="https://space.bilibili.com/1340292263" target="_blank">Bilibili</a>
          <a class="footer-link" href="https://www.ifdian.net/a/zzh10086" target="_blank">爱发电</a>
          <a class="footer-link" href="community.html">QQ 群 1102400426</a>
        </div>
      </div>
      <div class="footer-bottom">
        <span class="footer-copy">© 2026 MD3L · 基于开源协议发布</span>
        <span class="footer-copy">Made with Material Design 3</span>
      </div>`;
  }

  /* ── Scroll reveal ── */
  // Elements already in viewport get visible immediately;
  // below-fold elements animate in on scroll.
  const ro = new IntersectionObserver(entries => {
    entries.forEach((e, i) => {
      if (e.isIntersecting) {
        setTimeout(() => e.target.classList.add('visible'), i * 60);
        ro.unobserve(e.target);
      }
    });
  }, { threshold: 0, rootMargin: '0px 0px -40px 0px' });

  // Short delay so layout paint is complete before observing
  setTimeout(() => {
    document.querySelectorAll('.reveal').forEach(el => ro.observe(el));
  }, 80);

  /* ── Lightbox ── */
  window.openLightbox = src => {
    const lb = document.getElementById('lightbox');
    if (!lb) return;
    lb.querySelector('img').src = src;
    lb.classList.add('open');
  };
  window.closeLightbox = () => {
    const lb = document.getElementById('lightbox');
    if (lb) lb.classList.remove('open');
  };
  document.addEventListener('keydown', e => { if (e.key === 'Escape') window.closeLightbox(); });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initSite);
} else {
  initSite();
}

function toggleMobileNav() {
  document.querySelector('.mobile-nav')?.classList.toggle('open');
}
