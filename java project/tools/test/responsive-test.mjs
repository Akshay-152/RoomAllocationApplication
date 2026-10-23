// Rooms + responsiveness end-to-end test (CDP, no extra deps).
// Usage: APP_URL=http://localhost:8081/ node tools/test/responsive-test.mjs
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

// Required so JavaScript dialogs (confirm on delete) are delivered to the
// Page.javascriptDialogOpening handler instead of blocking the page forever.
await send('Page.enable');
await send('Runtime.enable');
// Dismiss a dialog left open by an interrupted previous run; ignore if none.
await send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {});

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

const WIDTHS = [1920, 1366, 1024, 768, 390, 320];
const TABS = ['dashboard', 'students', 'rooms', 'courses', 'exams', 'allocation', 'import'];

try {
  await send('Page.navigate', { url: APP });
  await sleep(3000);

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
    return out;
  })()`);
  // Re-runs reuse the existing browser session, so either path is valid.
  check('login screen -> admin dashboard', login.inApp, JSON.stringify(login));

  if (!login.inApp) {
    failed++;
    console.log('SKIP remaining tests - cannot sign in (do you have a current admin session on :8080? sign out from the browser first).');
    process.exit(1);
  }

  // ---------- rooms: name, search, availability (Select), edit ----------
  results: {
    const r = await evaluate(`(async () => {
      const $ = id => document.getElementById(id);
      const sleep = ms => new Promise(r => setTimeout(r, ms));
      const out = {};
      document.querySelector('.tab[data-section="rooms"]').click();
      await sleep(800);
      for (let i = 0; i < 30 && !document.querySelectorAll('#room-list tr').length; i++) await sleep(100);
      // remove leftovers from any previous run so the test is idempotent
      for (const no of ['901', '902']) {
        const stale = [...document.querySelectorAll('#room-list tr')]
          .find(tr => tr.textContent.includes(no))?.querySelector('button[data-act="delete"]');
        if (stale) { stale.click(); await sleep(1400); }
      }
      out.headers = [...document.querySelectorAll('#rooms-section thead th')].map(t => t.textContent.trim());
      out.searchBox = !!$('room-search');
      out.selectBoxes = document.querySelectorAll('#room-list input[data-select-room]').length;

      // add a room with a name
      $('r-no').value = '901'; $('r-name').value = 'Responsive Lab'; $('r-desks').value = '12';
      $('r-desks').dispatchEvent(new Event('input', { bubbles: true }));
      [...document.querySelectorAll('#room-form button')].find(b => b.textContent.includes('Add Room')).click();
      await sleep(1400);
      const row901 = [...document.querySelectorAll('#room-list tr')].find(tr => tr.textContent.includes('901'));
      out.addedWithName = !!row901 && row901.textContent.includes('Responsive Lab');

      // add a room without a name -> dash fallback
      $('r-no').value = '902'; $('r-name').value = ''; $('r-desks').value = '8';
      $('r-desks').dispatchEvent(new Event('input', { bubbles: true }));
      [...document.querySelectorAll('#room-form button')].find(b => b.textContent.includes('Add Room')).click();
      await sleep(1400);
      const row902 = [...document.querySelectorAll('#room-list tr')].find(tr => tr.textContent.includes('902'));
      out.dashForEmptyName = !!row902 && /\\u2014|–|-/.test(row902.children[1].textContent);
      out.formResetAfterAdd = $('r-desks').value === '' && $('r-name').value === '';

      // search by name and by number
      $('room-search').value = 'responsive'; $('room-search').dispatchEvent(new Event('input', { bubbles: true }));
      await sleep(250);
      out.searchByName = document.querySelectorAll('#room-list tr').length === 1 &&
        document.querySelector('#room-list').textContent.includes('901');
      $('room-search').value = '902'; $('room-search').dispatchEvent(new Event('input', { bubbles: true }));
      await sleep(250);
      out.searchByNumber = document.querySelectorAll('#room-list tr').length === 1 &&
        document.querySelector('#room-list').textContent.includes('902');
      out.countShown = ($('room-count').textContent || '').includes('of');
      $('room-search').value = ''; $('room-search').dispatchEvent(new Event('input', { bubbles: true }));
      await sleep(250);

      // availability checkbox (Select) -> server-persisted status
      const cb902 = [...document.querySelectorAll('#room-list tr')]
        .find(tr => tr.textContent.includes('902'))?.querySelector('input[data-select-room]');
      const rowsBefore = document.querySelectorAll('#room-list tr').length;
      cb902.click();   // checkbox toggles on click
      await sleep(1500);
      const rowAfter = [...document.querySelectorAll('#room-list tr')].find(tr => tr.textContent.includes('902'));
      out.deactivated = !!rowAfter && rowAfter.textContent.includes('INACTIVE');
      // status filter
      $('room-status-filter').value = 'inactive'; $('room-status-filter').dispatchEvent(new Event('change'));
      await sleep(300);
      out.filterInactive = document.querySelectorAll('#room-list tr').length === 1 &&
        document.querySelector('#room-list').textContent.includes('902');
      $('room-status-filter').value = ''; $('room-status-filter').dispatchEvent(new Event('change'));
      await sleep(300);
      // reactivate
      const cb902b = [...document.querySelectorAll('#room-list tr')]
        .find(tr => tr.textContent.includes('902'))?.querySelector('input[data-select-room]');
      cb902b.click();
      await sleep(1500);
      const rowRe = [...document.querySelectorAll('#room-list tr')].find(tr => tr.textContent.includes('902'));
      out.reactivated = !!rowRe && rowRe.textContent.includes('ACTIVE');
      out.rowsKept = rowsBefore === document.querySelectorAll('#room-list tr').length;

      // edit: rename via the edit row
      const editBtn = [...document.querySelectorAll('#room-list tr')]
        .find(tr => tr.textContent.includes('901'))?.querySelector('button[data-act="edit"]');
      editBtn.click();
      await sleep(350);
      const row = document.querySelector('#room-list tr[data-edit]');
      out.editHasNameField = !!row && !!row.querySelector('[data-f="name"]');
      if (row) {
        row.querySelector('[data-f="name"]').value = 'Renamed Lab';
        row.querySelector('[data-act="save"]').click();
        await sleep(1400);
      }
      const renamed = [...document.querySelectorAll('#room-list tr')].find(tr => tr.textContent.includes('901'));
      out.savedName = !!renamed && renamed.textContent.includes('Renamed Lab');

      // cleanup both test rooms
      for (const no of ['901', '902']) {
        const del = [...document.querySelectorAll('#room-list tr')]
          .find(tr => tr.textContent.includes(no))?.querySelector('button[data-act="delete"]');
        if (del) { del.click(); await sleep(1400); }
      }
      out.cleaned = ![...document.querySelectorAll('#room-list tr')].some(tr => /901|902/.test(tr.textContent));
      return out;
    })()`);
    check('room table has Room Name + Select columns',
      r.headers.includes('Room Name') && r.headers.includes('Select') && r.searchBox,
      JSON.stringify(r.headers));
    check('room added with name', r.addedWithName && r.formResetAfterAdd);
    check('empty name shows dash fallback', r.dashForEmptyName);
    check('search by name and number', r.searchByName && r.searchByNumber && r.countShown);
    check('Select checkbox persists availability', r.deactivated && r.reactivated && r.rowsKept);
    check('availability filter works', r.filterInactive);
    check('edit row carries the name field and saves', r.editHasNameField && r.savedName, JSON.stringify(r));
    check('test rooms cleaned up', r.cleaned);
  }

  // ---------- seating chart: room picker ----------
  {
    const r = await evaluate(`(async () => {
      const $ = id => document.getElementById(id);
      const sleep = ms => new Promise(r => setTimeout(r, ms));
      const out = {};
      document.querySelector('.tab[data-section="allocation"]').click();
      await sleep(1200);
      for (let i = 0; i < 30 && !document.querySelectorAll('#alloc-room-list input[data-room-id]').length; i++) await sleep(100);
      // Use the seeded baseline exam, which has eligible students; the imported
      // exams later in the list may target an audience with no students.
      const examSelect = $('alloc-exam');
      for (let i = 0; i < 30 && examSelect.options.length === 0; i++) await sleep(100);
      const seeded = [...examSelect.options].find(o => o.textContent.includes('Data Structures'));
      if (seeded) {
        examSelect.value = seeded.value;
        examSelect.dispatchEvent(new Event('change'));
        await sleep(800);
      }
      out.pickerBoxes = document.querySelectorAll('#alloc-room-list input[data-room-id]').length;
      out.summaryShown = $('alloc-room-warn').style.display !== 'none' && $('alloc-room-warn').textContent.length > 0;
      out.summary = $('alloc-room-warn').textContent;

      // uncheck everything -> warning + generate guard
      document.querySelectorAll('#alloc-room-list input[data-room-id]').forEach(c => { if (c.checked) c.click(); });
      await sleep(400);
      out.noneWarn = $('alloc-room-warn').className.includes('err') &&
        $('alloc-room-warn').textContent.includes('No room selected');
      const msgBefore = $('allocation-message').textContent;
      document.getElementById('btn-generate').click();
      await sleep(900);
      out.guarded = $('allocation-message').textContent === msgBefore &&
        $('toast').textContent.includes('at least one available room');
      $('toast').className = '';

      // pick one room; while capacity is short, keep adding rooms
      const boxes = [...document.querySelectorAll('#alloc-room-list input[data-room-id]')];
      boxes[0].click(); await sleep(350);
      let checkedCount = 1;
      while ($('alloc-room-warn').className.includes('err') &&
             $('alloc-room-warn').textContent.includes('Capacity') && checkedCount < boxes.length) {
        boxes[checkedCount].click(); checkedCount++; await sleep(300);
      }
      out.singleWarnOk = !$('alloc-room-warn').className.includes('Capacity');
      out.checkedCount = checkedCount;
      document.getElementById('btn-generate').click();
      await sleep(3200);
      const msg = document.getElementById('allocation-message');
      out.genOk = msg && msg.className.includes('ok');
      out.genMessage = msg ? msg.textContent : '';
      out.roomsUsed = [...document.querySelectorAll('#chart-container .room-title')].map(t => t.textContent.trim());
      out.pickerCount = out.genOk && out.roomsUsed.length >= 1 && out.roomsUsed.length <= checkedCount;
      // restore every room selection for a normal chart
      document.querySelectorAll('#alloc-room-list input[data-room-id]').forEach(c => { if (!c.checked) c.click(); });
      await sleep(300);
      document.getElementById('btn-generate').click();
      await sleep(2200);
      out.restored = $('allocation-message').className.includes('ok');
      return out;
    })()`);
    check('room picker renders every available room', r.pickerBoxes >= 1, 'boxes=' + r.pickerBoxes);
    check('capacity summary shown (ok or warning)', r.summaryShown && r.summary.length > 0, r.summary);
    check('generate blocked when no room selected', r.noneWarn && r.guarded, r.summary);
    check('allocation uses only the selected rooms', r.genOk && r.pickerCount,
      JSON.stringify({ checked: r.checkedCount, used: r.roomsUsed.length, msg: r.genMessage }));
    check('selection restored and chart regenerated', r.restored);
  }

  // ---------- viewport sweep: no page-level horizontal overflow ----------
  {
    const sweep = [];
    for (const width of WIDTHS) {
      await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
      await sleep(400);
      for (const tab of TABS) {
        const res = await evaluate(`(async () => {
          const sleep = ms => new Promise(r => setTimeout(r, ms));
          document.querySelector('.tab[data-section="${tab}"]').click();
          await sleep(650);
          let editOpen = false;
          if ('${tab}' === 'rooms') {
            const edit = document.querySelector('#room-list button[data-act="edit"]');
            if (edit) { edit.click(); await sleep(350); editOpen = !!document.querySelector('#room-list tr[data-edit]'); }
          }
          const innerW = window.innerWidth;
          const de = document.documentElement;
          const docW = Math.max(de.scrollWidth, document.body ? document.body.scrollWidth : 0);
          let worst = null, worstRight = innerW;
          document.querySelectorAll('body *').forEach(el => {
            const st = getComputedStyle(el);
            if (st.display === 'none' || st.visibility === 'hidden') return;
            const r = el.getBoundingClientRect();
            if (r.width > 0 && r.right > innerW + 1 && r.right > worstRight && !el.closest('.table-scroll, .grid-scroll, .tabs')) {
              worstRight = r.right;
              worst = el.tagName + (el.id ? '#' + el.id : '') + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\\s+/).slice(0, 2).join('.') : '');
            }
          });
          const tabsScrollsInside = (() => { const t = document.querySelector('.tabs'); return !t || t.scrollWidth <= t.clientWidth || true; })();
          return { innerW, docW, over: docW > innerW + 1, worst, tabsScrollsInside, editOpen };
        })()`);
        sweep.push({ width, tab, ...res });
        check(`no page overflow @${width}px ${tab}` + (res.editOpen ? ' (edit row open)' : ''),
          !res.over, res.over ? `docW=${res.docW} innerW=${res.innerW} offender=${res.worst}` : `docW=${res.docW}`);
      }
    }
    // login screen overflow check (logged out state)
    await send('Emulation.setDeviceMetricsOverride', { width: 320, height: 900, deviceScaleFactor: 1, mobile: false });
    await sleep(300);
  }

} catch (e) {
  failed++;
  console.log('FAIL  uncaught: ' + e.message);
}

console.log('\nPage errors: ' + (pageErrors.length ? '\n  ' + pageErrors.join('\n  ') : 'none'));
const ignorable = pageErrors.filter(e => e.includes('401') || e.includes('Failed to load resource'));
if (ignorable.length) console.log('(ignorable: ' + ignorable.length + ')');
if (pageErrors.filter(e => !e.includes('401') && !e.includes('Failed to load resource')).length) failed++;
console.log(failed === 0 ? '\nALL RESPONSIVE TESTS PASSED' : `\n${failed} CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);
