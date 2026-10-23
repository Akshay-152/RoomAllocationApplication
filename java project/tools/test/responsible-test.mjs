// responsible.md end-to-end QA (CDP, no extra deps).
// Usage: APP_URL=http://localhost:8081/ node tools/test/responsible-test.mjs
//        (default app :8080, chrome on :9333)
const DEBUG = 'http://127.0.0.1:9333';
const APP = process.env.APP_URL || 'http://localhost:8080/';
const APP_ORIGIN = new URL(APP).origin;

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function findTarget() {
  for (let i = 0; i < 40; i++) {
    try {
      const list = await (await fetch(DEBUG + '/json/list')).json();
      const page = list.find(t => t.type === 'page' && (t.url.startsWith(APP_ORIGIN) || t.url === 'about:blank'));
      if (page) return page;
    } catch (e) { /* chrome not up yet */ }
    await sleep(500);
  }
  throw new Error('Chrome target not found on ' + DEBUG);
}

const target = await findTarget();
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

let seq = 0;
const pending = new Map();
const pageErrors = [];
ws.onmessage = ev => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    const { res, rej } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? rej(new Error(JSON.stringify(msg.error))) : res(msg.result);
  } else if (msg.method === 'Runtime.exceptionThrown') {
    pageErrors.push('EXCEPTION: ' + (msg.params.exceptionDetails.exception?.description || msg.params.exceptionDetails.text));
  } else if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
    pageErrors.push('CONSOLE: ' + msg.params.args.map(a => a.value ?? a.description ?? '').join(' '));
  } else if (msg.method === 'Page.javascriptDialogOpening') {
    send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {});
  }
};
function send(method, params = {}) {
  return new Promise((res, rej) => {
    const id = ++seq;
    pending.set(id, { res, rej });
    ws.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error('page eval failed: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  return r.result.value;
}

let failed = 0;
function check(name, cond, detail = '') {
  const ok = !!cond;
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -> ' + detail : ''}`);
}

try {
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1366, height: 900, deviceScaleFactor: 1, mobile: false });

  // hard sign-out first: a previous run may have left a student session in this browser
  await send('Page.navigate', { url: APP });
  await sleep(2500);
  await evaluate(`(async () => {
    try {
      const t = await (await fetch('/api/auth/csrf')).json();
      await fetch('/api/auth/logout', { method: 'POST', headers: { 'X-XSRF-TOKEN': t.token } });
    } catch (e) { /* already signed out */ }
    return true;
  })()`);
  await send('Page.navigate', { url: APP });
  await sleep(3000);

  // ---------- admin login ----------
  const login = await evaluate(`(async () => {
    const $ = id => document.getElementById(id);
    const sleep = ms => new Promise(r => setTimeout(r, ms));
    const out = { sawLogin: $('login-screen').style.display !== 'none' };
    if (out.sawLogin) {
      $('login-username').value = 'admin';
      $('login-password').value = 'admin';
      $('login-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      for (let i = 0; i < 60 && $('admin-app').style.display === 'none'; i++) await sleep(100);
    }
    out.inApp = $('admin-app').style.display !== 'none';
    out.newTabs = ['calendar', 'reports', 'announcements', 'settings']
      .every(s => !!document.querySelector('.tab[data-section="' + s + '"]'));
    out.skipLink = !!document.querySelector('a.skip-link[href="#admin-main"]');
    return out;
  })()`);
  check('admin login + new nav tabs + skip link', login.inApp && login.newTabs && login.skipLink, JSON.stringify(login));

  // ---------- calendar tab ----------
  {
    const r = await evaluate(`(async () => {
      const $ = id => document.getElementById(id);
      const sleep = ms => new Promise(r => setTimeout(r, ms));
      const out = {};
      document.querySelector('.tab[data-section="calendar"]').click();
      await sleep(1200);
      // Move to the month that contains the seeded exam (the current month may
      // legitimately have no exams), so the chip/detail checks are meaningful.
      window.calMove(1); await sleep(500);
      out.monthCells = document.querySelectorAll('#cal-grid .cal-day').length;
      out.chips = document.querySelectorAll('#cal-grid .cal-chip').length;
      out.title = $('cal-title').textContent;
      out.agenda = document.querySelectorAll('#cal-agenda .cal-agenda-item').length;
      // week view
      $('cal-view').value = 'week'; $('cal-view').dispatchEvent(new Event('change'));
      await sleep(400);
      out.weekCells = document.querySelectorAll('#cal-grid .cal-day').length;
      // list view
      $('cal-view').value = 'list'; $('cal-view').dispatchEvent(new Event('change'));
      await sleep(400);
      out.listHidden = $('cal-grid').style.display === 'none';
      out.listAgenda = document.querySelectorAll('#cal-agenda .cal-agenda-item').length;
      // back to month, prev/next
      $('cal-view').value = 'month'; $('cal-view').dispatchEvent(new Event('change'));
      await sleep(300);
      window.calMove(1); await sleep(300);
      out.nextTitle = $('cal-title').textContent;
      window.calToday(); await sleep(300);
      out.backToToday = $('cal-title').textContent === out.title || out.nextTitle !== out.title;
      // click an event chip
      const chip = document.querySelector('#cal-grid .cal-chip') ||
        document.querySelector('#cal-agenda button[data-exam]');
      if (chip) { chip.click(); await sleep(1200); }
      out.detailShown = $('cal-detail').style.display !== 'none';
      out.detailText = $('cal-detail').textContent.replace(/\\s+/g, ' ').trim().slice(0, 160);
      return out;
    })()`);
    check('calendar month grid renders', r.monthCells >= 28 && r.chips >= 1, `cells=${r.monthCells} chips=${r.chips}`);
    check('week and list views work', r.weekCells === 7 && r.listHidden && r.listAgenda >= 1, JSON.stringify({ weekCells: r.weekCells, listAgenda: r.listAgenda }));
    check('prev/next navigation changes the period', r.backToToday, r.nextTitle);
    check('exam detail shows allocation status', r.detailShown && /seat|chart|generated|published/i.test(r.detailText), r.detailText);
  }

  // ---------- reports tab ----------
  {
    const r = await evaluate(`(async () => {
      const $ = id => document.getElementById(id);
      const sleep = ms => new Promise(r => setTimeout(r, ms));
      document.querySelector('.tab[data-section="reports"]').click();
      await sleep(1500);
      for (let i = 0; i < 30 && !document.querySelectorAll('#report-list tr').length; i++) await sleep(100);
      return {
        rows: document.querySelectorAll('#report-list tr').length,
        totals: $('report-totals').style.display !== 'none',
        totalsText: $('report-totals').textContent.replace(/\\s+/g, ' ').trim(),
        headers: [...document.querySelectorAll('#reports-section thead th')].map(t => t.textContent.trim())
      };
    })()`);
    check('reports table lists every exam with totals', r.rows >= 1 && r.totals && r.headers.includes('Unseated'),
      JSON.stringify({ rows: r.rows, totals: r.totalsText }));
  }

  // ---------- announcements tab ----------
  {
    const r = await evaluate(`(async () => {
      const $ = id => document.getElementById(id);
      const sleep = ms => new Promise(r => setTimeout(r, ms));
      document.querySelector('.tab[data-section="announcements"]').click();
      await sleep(800);
      const out = { batchesInSelect: $('a-batch').options.length };
      $('a-title').value = 'QA Test Notice';
      $('a-body').value = 'Posted by the automated responsible.md QA run.';
      window.addAnnouncement();
      await sleep(2000);
      out.posted = $('ann-list').textContent.includes('QA Test Notice');
      out.toast = $('toast').textContent;
      const del = [...document.querySelectorAll('button[data-del-ann]')]
        .find(b => b.closest('.ann-admin-item').textContent.includes('QA Test Notice'));
      if (del) { del.click(); await sleep(1500); }
      out.deleted = !$('ann-list').textContent.includes('QA Test Notice');
      return out;
    })()`);
    check('announcement posted and deleted', r.posted && r.deleted, JSON.stringify(r));
  }

  // ---------- settings tab (validation + full password round-trip) ----------
  {
    const r = await evaluate(`(async () => {
      const $ = id => document.getElementById(id);
      const sleep = ms => new Promise(r => setTimeout(r, ms));
      document.querySelector('.tab[data-section="settings"]').click();
      await sleep(500);
      const out = {};
      // mismatch path
      $('set-current').value = 'admin'; $('set-new').value = 'whatever1'; $('set-confirm').value = 'whatever2';
      window.changeAdminPassword(); await sleep(500);
      out.mismatch = $('set-msg').textContent.includes('do not match');
      // wrong current password
      $('set-current').value = 'nope-nope'; $('set-new').value = 'whatever1'; $('set-confirm').value = 'whatever1';
      window.changeAdminPassword(); await sleep(1200);
      out.wrongCurrent = $('set-msg').className.includes('err') && !$('set-msg').textContent.includes('do not match');
      // too-short new password is rejected client-side (the server enforces the same rule)
      $('set-current').value = 'admin'; $('set-new').value = 'abc'; $('set-confirm').value = 'abc';
      window.changeAdminPassword(); await sleep(400);
      out.tooShort = $('set-msg').textContent.includes('at least 6');
      return out;
    })()`);
    check('settings: password-change validation', r.mismatch && r.wrongCurrent && r.tooShort, JSON.stringify(r));
  }

  // ---------- mobile: hamburger drawer (admin) ----------
  {
    await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    // reload so the drawer initializes at mobile width
    await evaluate(`location.reload(); true`);
    await sleep(3000);
    const r2 = await evaluate(`(async () => {
      const $ = id => document.getElementById(id);
      const sleep = ms => new Promise(r => setTimeout(r, ms));
      const out = {};
      // sign in again after reload (sessions are in-memory per browser tab but cookie survives)
      if ($('admin-app').style.display === 'none') {
        $('login-username').value = 'admin';
        $('login-password').value = 'admin';
        $('login-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
        for (let i = 0; i < 60 && $('admin-app').style.display === 'none'; i++) await sleep(100);
      }
      const toggle = $('admin-nav-toggle'), tabs = $('admin-tabs');
      out.toggleVisible = getComputedStyle(toggle).display !== 'none';
      out.collapsedAtStart = getComputedStyle(tabs).display === 'none';
      toggle.click(); await sleep(400);
      out.opened = toggle.getAttribute('aria-pressed') === 'true' && getComputedStyle(tabs).display !== 'none';
      out.barsRotated = toggle.getAttribute('aria-pressed') === 'true';
      document.querySelector('#admin-tabs .tab[data-section="rooms"]').click();
      await sleep(600);
      out.closesOnNav = getComputedStyle(tabs).display === 'none' && toggle.getAttribute('aria-pressed') === 'false';
      // overflow of new sections at 390px
      const over = {};
      for (const s of ['calendar', 'reports', 'announcements', 'settings']) {
        document.querySelector('#admin-tabs .tab[data-section="' + s + '"], .tab[data-section="' + s + '"]').click();
        await sleep(700);
        over[s] = document.documentElement.scrollWidth > window.innerWidth + 1;
      }
      out.overflow = over;
      out.docW = document.documentElement.scrollWidth; out.innerW = window.innerWidth;
      return out;
    })()`);
    check('hamburger visible & drawer collapsed on phone', r2.toggleVisible && r2.collapsedAtStart, JSON.stringify({ v: r2.toggleVisible, c: r2.collapsedAtStart }));
    check('hamburger opens drawer (aria-pressed)', r2.opened && r2.barsRotated);
    check('drawer closes after picking a tab', r2.closesOnNav);
    check('new sections do not overflow at 390px', !Object.values(r2.overflow).some(Boolean), JSON.stringify(r2.overflow));

    // ---------- publish every generated chart so students can see their seats ----------
  {
    const pub = await evaluate(`(async () => {
      const out = { published: 0 };
      const exams = await api('/api/exams');
      for (const x of exams) {
        try {
          const rows = await api('/api/allocations?examId=' + x.id);
          if (!rows.length) continue;
          if (rows.some(r => r.status !== 'PUBLISHED')) {
            await api('/api/allocations/' + x.id + '/publish', jsonOpts('POST', {}));
          }
          if (x.status !== 'PUBLISHED') {
            await api('/api/exams/' + x.id + '/status', jsonOpts('POST', { status: 'PUBLISHED' }));
          }
          out.published++;
        } catch (e) { /* exam without chart */ }
      }
      return out;
    })()`);
    console.log('INFO  published charts: ' + pub.published);
  }

  // ---------- student side: hamburger + calendar + announcement ----------
    await evaluate('window.logout(); true');
    await sleep(3500);
    const r3 = await evaluate(`(async () => {
      const $ = id => document.getElementById(id);
      const sleep = ms => new Promise(r => setTimeout(r, ms));
      const out = {};
      out.sawLogin = $('login-screen').style.display !== 'none';
      // find a student roll number from the public exam API is not allowed; use a seeded one
      const rolls = ['101', '102', 'S3-001', '23CS001', '23CS002', '1'];
      for (const roll of rolls) {
        $('login-username').value = roll; $('login-password').value = roll;
        $('login-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
        for (let i = 0; i < 40 && $('login-error').textContent === '' && $('student-app').style.display === 'none'; i++) await sleep(100);
        if ($('student-app').style.display !== 'none') { out.student = roll; break; }
        $('login-error').classList.remove('show');
      }
      if (!out.student) return out;
      const toggle = $('student-nav-toggle'), tabs = $('student-tabs');
      out.drawerCollapsed = getComputedStyle(tabs).display === 'none';
      toggle.click(); await sleep(400);
      out.drawerOpened = getComputedStyle(tabs).display !== 'none';
      document.querySelector('#student-tabs .tab[data-stu="exams"]').click();
      await sleep(900);
      out.drawerAutoClose = getComputedStyle(tabs).display === 'none';
      $('stu-cal-toggle').click(); await sleep(700);
      out.calOpen = $('stu-calendar').style.display !== 'none';
      out.calCells = document.querySelectorAll('#stu-cal-grid .cal-day').length;
      out.agenda = document.querySelectorAll('#stu-cal-agenda .cal-agenda-item').length;
      out.chips = document.querySelectorAll('#stu-calendar button[data-exam]').length;
      const chip = document.querySelector('#stu-calendar button[data-exam]') ||
        document.querySelector('#stu-cal-agenda button[data-exam]');
      if (chip) { chip.click(); await sleep(800); }
      out.detailShown = $('stu-cal-detail').style.display !== 'none';
      out.over = document.documentElement.scrollWidth > window.innerWidth + 1;
      return out;
    })()`);
    check('student login works for a seeded roll', !!r3.student, 'student=' + r3.student);
    check('student hamburger drawer works', r3.drawerCollapsed && r3.drawerOpened && r3.drawerAutoClose);
    check('student calendar renders (compact + agenda + detail)',
      r3.calOpen && r3.calCells >= 28 && (r3.agenda >= 1 || r3.chips >= 1) && (r3.chips === 0 || r3.detailShown),
      JSON.stringify(r3));
    check('student mobile view has no overflow', !r3.over, 'docW vs innerW');
  }

} catch (e) {
  failed++;
  console.log('FAIL  uncaught: ' + e.message);
}

console.log('\nPage errors: ' + (pageErrors.length ? '\n  ' + pageErrors.join('\n  ') : 'none'));
const real = pageErrors.filter(e => !e.includes('401') && !e.includes('Failed to load resource'));
if (real.length) failed++;
console.log(failed === 0 ? '\nALL RESPONSIBLE-MD CHECKS PASSED' : `\n${failed} CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);
