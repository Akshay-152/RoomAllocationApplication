// UI end-to-end test driven through Chrome DevTools Protocol (no extra deps).
// Usage: node tools/test/cdp-test.mjs
const DEBUG = 'http://127.0.0.1:9333';
const APP = 'http://localhost:8081/';
const XLSX = 'C:\\programing\\java project\\tools\\test\\test-students.xlsx';
const EXAM_XLSX = 'C:\\programing\\java project\\tools\\test\\test-exams.xlsx';

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function findTarget() {
  for (let i = 0; i < 40; i++) {
    try {
      const list = await (await fetch(DEBUG + '/json/list')).json();
      const page = list.find(t => t.type === 'page' && t.url.startsWith('http://localhost:8081'));
      if (page) return page;
    } catch (e) { /* chrome not up yet */ }
    await sleep(500);
  }
  throw new Error('Chrome target not found - did Chrome start with --remote-debugging-port=9333?');
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

const results = {};
let failed = 0;
function check(name, cond, detail = '') {
  const ok = !!cond;
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -> ' + detail : ''}`);
}

try {
  await send('Page.enable');
  await send('Runtime.enable');

  // Reload to start from a clean state (previous run may have left a tab open).
  await send('Page.navigate', { url: APP });
  await sleep(3500);

  // ---------- sign in (sessions are server-side; the suite starts anonymous) ----------
  const loginOk = await evaluate(`(async () => {
    const $ = id => document.getElementById(id);
    const sleep = ms => new Promise(r => setTimeout(r, ms));
    if ($('login-screen').style.display === 'none') return 'already-signed-in';
    $('login-username').value = 'admin';
    $('login-password').value = 'admin';
    $('login-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    for (let i = 0; i < 60 && $('admin-app').style.display === 'none'; i++) await sleep(100);
    return $('admin-app').style.display !== 'none' ? 'logged-in' : 'FAILED';
  })()`);
  check('admin login before UI suite', loginOk !== 'FAILED', loginOk);

  // ---------- dashboard ----------
  results.dashboard = await evaluate(`(async () => {
    const $ = id => document.getElementById(id);
    for (let i = 0; i < 50 && $('stat-students').textContent.trim() === '\\u2013'; i++) await new Promise(r => setTimeout(r, 100));
    return { students: $('stat-students').textContent, rooms: $('stat-rooms').textContent,
             exams: $('stat-exams').textContent, seats: $('stat-seats').textContent };
  })()`);
  check('dashboard stats load', results.dashboard.students > 0, JSON.stringify(results.dashboard));

  // ---------- students: tabs, Enter navigation, add, edit, delete ----------
  results.students = await evaluate(`(async () => {
    const $ = id => document.getElementById(id);
    const sleep = ms => new Promise(r => setTimeout(r, ms));
    const out = {};

    document.querySelector('.tab[data-section="students"]').click();
    await sleep(700);
    out.rows = document.querySelectorAll('#student-list tr').length;
    for (let i = 0; i < 30 && !$('s-batch').options.length; i++) await sleep(100); // batch dropdown seeded
    out.batchOptions = $('s-batch').options.length;

    // Enter in first field moves to second field
    $('s-roll').focus();
    $('s-roll').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    out.enterMovesToName = document.activeElement.id === 's-name';

    // Fill form and press Enter in the last field -> adds the student
    $('s-roll').value = '555'; $('s-name').value = 'UI Test'; $('s-branch').value = 'CSE'; $('s-batch').value = 'C';
    $('s-batch').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await sleep(1200);
    out.added = document.querySelector('#student-list').textContent.includes('555');
    out.formCleared = $('s-roll').value === '';

    // Edit flow
    const editBtn = [...document.querySelectorAll('#student-list button')].find(b => b.textContent === 'Edit');
    editBtn.click(); await sleep(250);
    const row = document.querySelector('#student-list tr[data-edit]');
    out.editRendered = !!row;
    if (row) {
      row.querySelector('[data-f="name"]').value = 'Edited Name';
      const inputs = [...row.querySelectorAll('input')];
      inputs[0].focus();
      inputs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      out.enterMovesInEditRow = document.activeElement === inputs[1];
      row.querySelector('[data-act="save"]').click();
      await sleep(1200);
      out.saved = document.querySelector('#student-list').textContent.includes('Edited Name');
    }

    // Delete the student we created (confirm dialog auto-accepted by CDP)
    const delBtn = [...document.querySelectorAll('#student-list tr')].find(tr => tr.textContent.includes('555'))
      ?.querySelector('button[data-act="delete"]');
    if (delBtn) { delBtn.click(); await sleep(1200); }
    out.deleted = !document.querySelector('#student-list').textContent.includes('555');
    return out;
  })()`);
  check('students table renders', results.students.rows > 0, 'rows=' + results.students.rows);
  check('batch dropdown has defaults A-G', results.students.batchOptions >= 7, 'options=' + results.students.batchOptions);
  check('Enter moves to next field', results.students.enterMovesToName);
  check('Enter in last field adds student', results.students.added && results.students.formCleared);
  check('edit row renders with inputs', results.students.editRendered);
  check('Enter moves between edit inputs', results.students.enterMovesInEditRow);
  check('save updates student', results.students.saved);
  check('delete removes student', results.students.deleted);

  // ---------- rooms: desk prediction, capacity auto-fill, add/delete ----------
  results.rooms = await evaluate(`(async () => {
    const $ = id => document.getElementById(id);
    const sleep = ms => new Promise(r => setTimeout(r, ms));
    const out = {};
    document.querySelector('.tab[data-section="rooms"]').click();
    await sleep(800);
    out.rows = document.querySelectorAll('#room-list tr').length;
    out.hasGrid = document.querySelector('#room-list').textContent.includes('121');

    // Type a desk count -> rows/columns should be predicted (12 -> 3 x 4)
    $('r-no').value = '200';
    $('r-desks').value = '12';
    $('r-desks').dispatchEvent(new Event('input', { bubbles: true }));
    out.predRows = $('r-rows').value;
    out.predCols = $('r-cols').value;

    // Students per desk -> capacity auto-fills (12 x 2 = 24)
    $('r-per').value = '2';
    $('r-per').dispatchEvent(new Event('input', { bubbles: true }));
    out.autoCap = $('r-cap').value;

    [...document.querySelectorAll('#room-form button')].find(b => b.textContent.includes('Add Room')).click();
    await sleep(1300);
    out.added = document.querySelector('#room-list').textContent.includes('200');
    out.formReset = $('r-desks').value === '' && $('r-per').value === '3';

    // cleanup: delete the room created by this test
    const del = [...document.querySelectorAll('#room-list tr')]
      .find(tr => tr.textContent.includes('200'))?.querySelector('button[data-act="delete"]');
    if (del) { del.click(); await sleep(1300); }
    out.deleted = !document.querySelector('#room-list').textContent.includes('200');

    // Wide-and-shallow prediction:24 desks ->3x8,20 ->4x5,36 ->3x12
    const typeDesks = v => {
      $('r-desks').value = v;
      $('r-desks').dispatchEvent(new Event('input', { bubbles: true }));
      return $('r-rows').value + 'x' + $('r-cols').value;
    };
    out.pred24 = typeDesks('24');
    out.pred20 = typeDesks('20');
    out.pred36 = typeDesks('36');
    // reset the form so later tests see a clean state
    ['r-no', 'r-rows', 'r-cols', 'r-desks', 'r-cap'].forEach(id => $(id).value = '');
    return out;
  })()`);
  check('rooms tab lists rooms', results.rooms.rows >= 1, JSON.stringify({ rows: results.rooms.rows }));
  check('desk count predicts rows x columns', results.rooms.predRows === '3' && results.rooms.predCols === '4',
        `${results.rooms.predRows}x${results.rooms.predCols}`);
  check('grid stays wide & shallow (3-4 rows max)',
        results.rooms.pred24 === '3x8' && results.rooms.pred20 === '4x5' && results.rooms.pred36 === '3x12',
        `24=${results.rooms.pred24} 20=${results.rooms.pred20} 36=${results.rooms.pred36}`);
  check('capacity auto = desks x students/desk', results.rooms.autoCap === '24', 'cap=' + results.rooms.autoCap);
  check('room created & form reset', results.rooms.added && results.rooms.formReset);
  check('test room cleaned up', results.rooms.deleted);

  // ---------- courses / batches: defaults, add custom, delete ----------
  results.batches = await evaluate(`(async () => {
    const $ = id => document.getElementById(id);
    const sleep = ms => new Promise(r => setTimeout(r, ms));
    const out = {};
    document.querySelector('.tab[data-section="courses"]').click();
    await sleep(800);
    for (let i = 0; i < 30 && !document.querySelectorAll('#batch-list tr').length; i++) await sleep(100);
    out.rows = document.querySelectorAll('#batch-list tr').length;
    const names = [...document.querySelectorAll('#batch-list tr')].map(tr => (tr.querySelector('td')?.textContent || '').trim());
    out.defaults = ['A','B','C','D','E','F','G'].every(x => names.includes(x));

    $('c-name').value = 'Z';
    [...document.querySelectorAll('#batch-form button')].find(b => b.textContent.includes('Add Course')).click();
    await sleep(1300);
    const namesAfter = [...document.querySelectorAll('#batch-list tr')].map(tr => (tr.querySelector('td')?.textContent || '').trim());
    out.added = namesAfter.includes('Z');
    out.selectHasZ = [...$('s-batch').options].some(o => o.value === 'Z');
    out.examSelectHasZ = [...$('e-course').options].some(o => o.value === 'Z');

    const del = [...document.querySelectorAll('#batch-list tr')]
      .find(tr => (tr.querySelector('td')?.textContent || '').trim() === 'Z')
      ?.querySelector('button[data-act="delete-batch"]');
    if (del) { del.click(); await sleep(1300); }
    const namesFinal = [...document.querySelectorAll('#batch-list tr')].map(tr => (tr.querySelector('td')?.textContent || '').trim());
    out.deleted = !namesFinal.includes('Z');
    return out;
  })()`);
  check('batches tab lists defaults A-G', results.batches.rows >= 7 && results.batches.defaults,
        JSON.stringify({ rows: results.batches.rows, defaults: results.batches.defaults }));
  check('Add Course creates a custom batch available to forms',
        results.batches.added && results.batches.selectHasZ && results.batches.examSelectHasZ,
        JSON.stringify({ added: results.batches.added, studentSelect: results.batches.selectHasZ, examSelect: results.batches.examSelectHasZ }));
  check('custom batch deleted', results.batches.deleted);

  // ---------- exams: create with auto application id ----------
  results.exams = await evaluate(`(async () => {
    const $ = id => document.getElementById(id);
    const sleep = ms => new Promise(r => setTimeout(r, ms));
    const out = {};
    document.querySelector('.tab[data-section="exams"]').click();
    await sleep(700);
    out.rows = document.querySelectorAll('#exam-list tr').length;
    for (let i = 0; i < 30 && !$('e-course').options.length; i++) await sleep(100); // batch dropdown seeded

    // remove leftovers from previous suite runs so "no clash" stays meaningful
    for (const x of await api('/api/exams')) {
      if (x.subjectName === 'Physics' && x.examDate === '2026-12-01') {
        await api('/api/exams/' + x.id, jsonOpts('DELETE', {}));
      }
    }

    $('e-subject').value = 'Physics';
    $('e-course').value = 'B';
    $('e-date').value = '2026-12-01';
    $('e-time').value = '09:30';
    [...document.querySelectorAll('#exam-form button')].find(b => b.textContent.includes('Add Exam')).click();
    await sleep(1200);
    out.rowsAfter = document.querySelectorAll('#exam-list tr').length;
    const text = document.querySelector('#exam-list').textContent;
    out.created = text.includes('Physics') && text.includes('B');
    out.appIdAuto = /APP-B-20261201-\\d{3}/.test(text);
    out.noConflictsShown = $('exam-conflicts').style.display === 'none' ||
      !$('exam-conflicts').textContent.includes('2026-12-01');
    return out;
  })()`);
  check('exams tab lists exams', results.exams.rows >= 1, 'rows=' + results.exams.rows);
  check('exam created via form', results.exams.created);
  check('application ID auto-generated', results.exams.appIdAuto);
  check('no conflict warning for non-clashing exam', results.exams.noConflictsShown);

  // ---------- seating chart ----------
  results.chart = await evaluate(`(async () => {
    const $ = id => document.getElementById(id);
    const sleep = ms => new Promise(r => setTimeout(r, ms));
    const out = {};
    document.querySelector('.tab[data-section="allocation"]').click();
    await sleep(900);
    out.examOptions = $('alloc-exam').options.length;
    $('alloc-exam').value = '1';
    $('alloc-exam').dispatchEvent(new Event('change'));
    await sleep(800);
    document.getElementById('btn-generate').click();
    await sleep(1500);
    out.message = $('allocation-message').textContent;
    out.messageOk = $('allocation-message').className.includes('ok');
    out.seatCells = document.querySelectorAll('#chart-container .seat').length;
    out.vacantCells = document.querySelectorAll('#chart-container .seat.vacant').length;
    out.legend = $('chart-legend').textContent.trim();
    out.unallocShown = $('unallocated-block').style.display !== 'none';
    out.statsVisible = $('alloc-stats').style.display !== 'none';
    out.pdfReachable = await fetch('/api/chart/pdf?examId=1').then(r => r.status).catch(() => 0);
    return out;
  })()`);
  check('exam dropdown filled', results.chart.examOptions >= 2, 'options=' + results.chart.examOptions);
  check('allocation generates chart', results.chart.messageOk && results.chart.seatCells >= 1,
        results.chart.message + ' cells=' + results.chart.seatCells);
  check('course legend rendered', results.chart.legend.includes('CSE'), results.chart.legend);
  check('stats row visible', results.chart.statsVisible);
  check('chart PDF reachable from browser', results.chart.pdfReachable === 200, 'status=' + results.chart.pdfReachable);

  // ---------- shared desks: students per desk = 2 ----------
  results.shared = await evaluate(`(async () => {
    const sleep = ms => new Promise(r => setTimeout(r, ms));
    const out = {};
    document.querySelector('.tab[data-section="rooms"]').click();
    await sleep(700);
    const edit = [...document.querySelectorAll('#room-list tr')]
      .find(tr => tr.children.length > 1 && tr.querySelector('button[data-act="edit"]'))
      ?.querySelector('button[data-act="edit"]');
    if (!edit) return { editRendered: false, note: 'no room row to edit' };
    edit.click();
    await sleep(300);
    const row = document.querySelector('#room-list tr[data-edit]');
    out.editRendered = !!row;
    row.querySelector('[data-f="studentsPerDesk"]').value = '2';
    row.querySelector('[data-act="save"]').click();
    await sleep(1400);
    out.saved = !document.querySelector('#room-list tr[data-edit]');

    document.querySelector('.tab[data-section="allocation"]').click();
    await sleep(800);
    document.getElementById('btn-generate').click();
    await sleep(1600);
    out.message = document.getElementById('allocation-message').textContent;
    out.multiCells = document.querySelectorAll('#chart-container .seat.multi').length;
    const first = document.querySelector('#chart-container .seat.multi');
    if (first) {
      out.firstCell = first.textContent;
      out.slots = first.querySelectorAll('.slot').length;
      out.fullSlots = first.querySelectorAll('.slot:not(.empty)').length;
    }
    out.statsText = document.getElementById('alloc-stats').textContent.replace(/\\s+/g, ' ').trim();
    out.pdf = await fetch('/api/chart/pdf?examId=1').then(r => r.status).catch(() => 0);
    const labels = [...first.querySelectorAll('.slot i')].map(i => i.textContent.trim());
    out.groupsDistinct = labels.length === 2 && labels[0] !== labels[1];
    out.rolls = [...first.querySelectorAll('.slot b')].map(b => b.textContent.trim()).join(',');
    return out;
  })()`);
  check('room saved with students per desk = 2', results.shared.editRendered && results.shared.saved);
  check('chart renders shared desks (multi-slot cells)', results.shared.multiCells >= 1,
        'multi=' + results.shared.multiCells);
  check('first shared desk holds exactly 2 students',
        results.shared.slots === 2 && results.shared.fullSlots === 2,
        'rolls=' + results.shared.rolls + ' labels=' + (results.shared.groupsDistinct ? '2 groups' : '1 group'));
  check('stats count desks', (results.shared.statsText || '').includes('desks'), results.shared.statsText);
  check('chart PDF works for shared desks', results.shared.pdf === 200, 'status=' + results.shared.pdf);

  // ---------- import ----------
  results.import = await evaluate(`(async () => {
    const sleep = ms => new Promise(r => setTimeout(r, ms));
    document.querySelector('.tab[data-section="import"]').click();
    await sleep(500);
    return true;
  })()`);
  const doc = await send('DOM.getDocument', { depth: 0 });
  const fileNode = await send('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '#import-file' });
  await send('DOM.setFileInputFiles', { files: [XLSX], nodeId: fileNode.nodeId });
  results.importResult = await evaluate(`(async () => {
    const $ = id => document.getElementById(id);
    [...document.querySelectorAll('#import-section button')].find(b => b.textContent.includes('Upload')).click();
    for (let i = 0; i < 40 && $('import-result').style.display === 'none'; i++) await new Promise(r => setTimeout(r, 250));
    return {
      visible: $('import-result').style.display !== 'none',
      counts: $('import-counts').textContent.replace(/\\s+/g, ' ').trim(),
      notes: $('import-notes').textContent.replace(/\\s+/g, ' ').trim(),
      previewRows: document.querySelectorAll('#import-preview tr').length
    };
  })()`);
  check('import UI shows result', results.importResult.visible && results.importResult.previewRows > 0,
        JSON.stringify(results.importResult));

  // ---------- exam data upload (Excel) + validation + conflict detection ----------
  await evaluate(`(async () => {
    [...document.querySelectorAll('#import-section button')].find(b => b.textContent.includes('Exam Data')).click();
  })()`);
  await sleep(400);
  const doc2 = await send('DOM.getDocument', { depth: 0 });
  const examFileNode = await send('DOM.querySelector', { nodeId: doc2.root.nodeId, selector: '#exam-import-file' });
  await send('DOM.setFileInputFiles', { files: [EXAM_XLSX], nodeId: examFileNode.nodeId });
  results.examImport = await evaluate(`(async () => {
    const $ = id => document.getElementById(id);
    [...document.querySelectorAll('#import-mode-exams button')].find(b => b.textContent.includes('Upload Exam Data')).click();
    for (let i = 0; i < 40 && $('exam-import-result').style.display === 'none'; i++) await new Promise(r => setTimeout(r, 250));
    return {
      visible: $('exam-import-result').style.display !== 'none',
      counts: $('exam-import-counts').textContent.replace(/\\s+/g, ' ').trim(),
      notes: $('exam-import-notes').textContent.replace(/\\s+/g, ' ').trim(),
      conflicts: $('exam-import-conflicts').textContent.replace(/\\s+/g, ' ').trim(),
      previewRows: document.querySelectorAll('#exam-import-preview tr').length
    };
  })()`);
  check('exam import UI shows result', results.examImport.visible && results.examImport.previewRows > 0,
        JSON.stringify(results.examImport));
  check('invalid exam row rejected', /1 invalid/.test(results.examImport.counts), results.examImport.counts);
  check('exam import reports scheduling clash', results.examImport.conflicts.includes('Clash'),
        results.examImport.conflicts);

  // ---------- allocation conflict warnings (double-booked students/rooms) ----------
  results.allocConflict = await evaluate(`(async () => {
    const $ = id => document.getElementById(id);
    const sleep = ms => new Promise(r => setTimeout(r, ms));
    const out = {};
    document.querySelector('.tab[data-section="allocation"]').click();
    await sleep(1000);
    const opts = [...$('alloc-exam').options];
    const math = opts.find(o => o.textContent.includes('Mathematics'));
    const phys = opts.find(o => o.textContent.includes('Physics') && o.textContent.includes('2026-12-05'));
    out.hasExams = !!math && !!phys;
    if (!out.hasExams) return out;

    $('alloc-exam').value = math.value;
    $('alloc-exam').dispatchEvent(new Event('change'));
    await sleep(800);
    document.getElementById('btn-generate').click();
    await sleep(1600);
    out.firstOk = $('allocation-message').className.includes('ok');

    $('alloc-exam').value = phys.value;
    $('alloc-exam').dispatchEvent(new Event('change'));
    await sleep(800);
    document.getElementById('btn-generate').click();
    await sleep(1800);
    out.message = $('allocation-message').textContent;
    out.ok = $('allocation-message').className.includes('ok');
    out.conflictsShown = $('alloc-conflicts').style.display !== 'none';
    out.conflicts = $('alloc-conflicts').textContent.replace(/\\s+/g, ' ').trim();
    return out;
  })()`);
  check('both clashing exams can be allocated',
        results.allocConflict.hasExams && results.allocConflict.firstOk && results.allocConflict.ok,
        results.allocConflict.message);
  check('allocation shows double-booking warnings',
        results.allocConflict.conflictsShown && results.allocConflict.conflicts.includes('seated in both'),
        results.allocConflict.conflicts);

} catch (e) {
  failed++;
  console.log('FAIL  uncaught: ' + e.message);
}

console.log('\nPage errors: ' + (pageErrors.length ? '\n  ' + pageErrors.join('\n  ') : 'none'));
if (pageErrors.length) failed++;
console.log(failed === 0 ? '\nALL UI TESTS PASSED' : `\n${failed} CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);
