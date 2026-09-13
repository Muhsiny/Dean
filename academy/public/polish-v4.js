(() => {
  const APP = document.getElementById('app');
  if (!APP) return;

  const icons = {
    dashboard: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3.5 10.5 12 3l8.5 7.5"/><path d="M5.5 9.5V21h13V9.5"/><path d="M9.5 21v-6h5v6"/></svg>',
    course: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 4.5h12.5A1.5 1.5 0 0 1 19 6v14H6.5A1.5 1.5 0 0 1 5 18.5z"/><path d="M5 18.5A1.5 1.5 0 0 1 6.5 17H19"/><path d="M8 8h7M8 11h7"/></svg>',
    review: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 7v5h-5"/><path d="M19 12a7 7 0 1 1-2.05-4.95L20 10"/></svg>',
    coach: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 1.25 3.75L17 8l-3.75 1.25L12 13l-1.25-3.75L7 8l3.75-1.25z"/><path d="m18 14 .75 2.25L21 17l-2.25.75L18 20l-.75-2.25L15 17l2.25-.75z"/><path d="m6 14 .6 1.8L8.4 16.4l-1.8.6L6 18.8 5.4 17l-1.8-.6 1.8-.6z"/></svg>',
    profile: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="3.5"/><path d="M5 20c.6-4 3-6 7-6s6.4 2 7 6"/></svg>'
  };
  const labels = { dashboard:'Home', course:'Course', review:'Review', coach:'Coach', profile:'Profile' };

  function activeRoute() {
    const title = (APP.querySelector('.topbar-left h1')?.textContent || '').trim().toLowerCase();
    if (title.includes('course')) return 'course';
    if (title.includes('review')) return 'review';
    if (title.includes('coach')) return 'coach';
    if (title.includes('profile')) return 'profile';
    if (title.includes('evidence')) return 'profile';
    if (title.includes('lesson')) return 'course';
    return 'dashboard';
  }

  function ensureBottomNav() {
    let nav = document.querySelector('.bottom-app-nav');
    if (!nav) {
      nav = document.createElement('nav');
      nav.className = 'bottom-app-nav';
      nav.setAttribute('aria-label','Primary navigation');
      nav.innerHTML = ['dashboard','course','review','coach','profile'].map(route =>
        `<button type="button" data-bottom-route="${route}" aria-label="${labels[route]}">${icons[route]}<span>${labels[route]}</span></button>`
      ).join('');
      nav.addEventListener('click', event => {
        const button = event.target.closest('[data-bottom-route]');
        if (!button) return;
        const route = button.dataset.bottomRoute;
        const target = APP.querySelector(`[data-nav="${route}"]`);
        if (target) target.click();
      });
      document.body.appendChild(nav);
    }
    const active = activeRoute();
    nav.querySelectorAll('[data-bottom-route]').forEach(b => b.classList.toggle('active', b.dataset.bottomRoute === active));
    const authenticated = !!APP.querySelector('.app-shell');
    nav.style.display = authenticated && matchMedia('(max-width:1000px)').matches ? '' : 'none';
  }

  function enhanceCoach() {
    const title = (APP.querySelector('.topbar-left h1')?.textContent || '').trim();
    const isCoach = title === 'Study Coach';
    document.body.classList.toggle('coach-view', isCoach);
    if (!isCoach) return;

    const header = APP.querySelector('.curriculum-header');
    if (header && !header.dataset.polished) {
      header.dataset.polished = '1';
      const p = header.querySelector('p');
      if (p) p.textContent = 'Your next priorities, calculated from completed work, review due dates and recorded errors.';
    }
    const h2 = header?.querySelector('h2');
    if (h2) h2.style.display = matchMedia('(max-width:1000px)').matches ? 'none' : '';

    const metrics = [...APP.querySelectorAll('.grid.grid-3 .metric-value')].map(n => Number(n.textContent.trim()) || 0);
    const metricsGrid = APP.querySelector('.grid.grid-3');
    const detailGrid = metricsGrid?.nextElementSibling;
    if (metricsGrid && !APP.querySelector('.coach-good')) {
      const good = document.createElement('div');
      good.className = 'coach-good';
      const allZero = metrics.length >= 3 && metrics.every(v => v === 0);
      good.innerHTML = allZero
        ? `<div class="coach-badge">✓</div><div style="flex:1"><strong>You’re on track</strong><span>No urgent review or repeated mistake is waiting. Continue the next lesson.</span></div><div class="coach-action"><button type="button" class="btn btn-primary" data-polish-route="dashboard">Continue</button></div>`
        : `<div class="coach-badge">→</div><div style="flex:1"><strong>Focus on the highest-impact item first</strong><span>Your queue below is ordered from current learning evidence.</span></div>`;
      metricsGrid.insertAdjacentElement('afterend', good);
      good.querySelector('[data-polish-route]')?.addEventListener('click', () => APP.querySelector('[data-nav="dashboard"]')?.click());
    }
    if (detailGrid) detailGrid.classList.add('coach-details');
  }

  function makeMenuLessDominant() {
    APP.querySelectorAll('.mobile-menu').forEach(b => {
      b.setAttribute('aria-label','Open menu');
      b.setAttribute('title','Menu');
    });
  }

  function polish() {
    ensureBottomNav();
    enhanceCoach();
    makeMenuLessDominant();
  }

  const observer = new MutationObserver(() => requestAnimationFrame(polish));
  observer.observe(APP, { childList:true, subtree:true });
  addEventListener('resize', () => requestAnimationFrame(polish), { passive:true });
  polish();
})();
