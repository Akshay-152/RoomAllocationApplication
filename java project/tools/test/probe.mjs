const DEBUG = 'http://127.0.0.1:9333';
const list = await (await fetch(DEBUG + '/json/list')).json();
const page = list.find(t => t.type === 'page' && (t.url.startsWith('http://localhost:8080') || t.url === 'about:blank'));
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let seq = 0; const pending = new Map();
ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); }
};
const send = (method, params = {}) => new Promise((res, rej) => { const id = ++seq; pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method, params })); });
const sleep = ms => new Promise(r => setTimeout(r, ms));

await send('Runtime.enable');
await send('Page.navigate', { url: 'http://localhost:8080/' });
await sleep(3000);
async function ev(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error('eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  return r.result.value;
}
const out = await ev(`(async () => {
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const $ = id => document.getElementById(id);
  const out = {};
  out.sawLogin = $('login-screen').style.display !== 'none';
  if (out.sawLogin) {
    $('login-username').value = 'admin';
    $('login-password').value = 'admin';
    $('login-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    for (let i = 0; i < 60 && $('admin-app').style.display === 'none'; i++) await sleep(100);
  }
  document.querySelector('.tab[data-section="rooms"]').click();
  await sleep(1200);
  out.rows = [...document.querySelectorAll('#room-list tr')].map(tr => ({
    cells: tr.children.length,
    first: (tr.children[0]?.textContent || '').trim().slice(0, 30),
    hasEdit: !!tr.querySelector('button[data-act="edit"]'),
    isEditRow: tr.hasAttribute('data-edit'),
    text: tr.textContent.replace(/\\s+/g, ' ').trim().slice(0, 120)
  }));
  const found = [...document.querySelectorAll('#room-list tr')].find(tr => tr.textContent.includes('901'));
  out.found901 = found ? { hasEdit: !!found.querySelector('button[data-act="edit"]'), html: found.outerHTML } : 'NOT FOUND';
  out.roomSearchValue = $('room-search') ? $('room-search').value : 'MISSING';
  out.statusFilter = $('room-status-filter') ? $('room-status-filter').value : 'MISSING';
  out.selectCount = document.querySelectorAll('#room-list input[data-select-room]').length;
  return out;
})()`);
console.log(JSON.stringify(out, null, 2));
process.exit(0);
