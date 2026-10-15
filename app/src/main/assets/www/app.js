'use strict';
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const q = new URLSearchParams(location.search);
const store = { get: k => { try { return localStorage.getItem(k) || ''; } catch { return ''; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* bỏ qua */ } } };
// Cho phép mở bằng ?api=http://127.0.0.1:3000&token=... (lưu lại để lần sau không cần nhập)
if (q.get('token')) store.set('tk', q.get('token'));
if (q.get('api')) store.set('api', q.get('api'));
if (q.get('token') || q.get('api')) history.replaceState(null, '', location.pathname);
let TOKEN = store.get('tk');
// Backend: mặc định cùng nguồn (chạy bằng npm start); khi chạy từ web/app đóng gói zip thì dùng địa chỉ đã lưu
let API_BASE = store.get('api').replace(/\/+$/, '');
let connected = false, curHost = '', curState = 'disconnected';

const NATIVE = typeof window.NativeBridge !== 'undefined'; // chạy trong app Android: gọi thẳng Kotlin, không cần backend/token
const pending = {}; let nid = 0;
window.__nativeResolve = (id, s) => { const f = pending[id]; delete pending[id]; if (f) f(JSON.parse(s)); };
if (NATIVE) {
  // Ẩn các mục chỉ dùng với bản Node/ADB
  ['#method', '#port', '#pport', '#pcode', '#btn-pair', '#btn-backend'].forEach(sel => {
    const el = $(sel); if (!el) return;
    const prev = el.previousElementSibling; if (prev && prev.tagName === 'LABEL') prev.style.display = 'none';
    if (el.id === 'btn-backend') el.closest('.card').style.display = 'none'; else el.style.display = 'none';
  });
  $$('details p.sub').forEach(p => { if (/^ADB/.test(p.textContent.trim())) p.style.display = 'none'; });
  const t = $('#txt'); if (t) t.closest('.card').style.display = 'none';
}
async function api(path, body) {
  if (NATIVE) return new Promise(res => {
    const id = ++nid; pending[id] = res;
    window.NativeBridge.call(id, body ? 'POST' : 'GET', path, body ? JSON.stringify(body) : '');
  });
  try {
    const r = await fetch(API_BASE + '/api' + path, { method: body ? 'POST' : 'GET',
      headers: { 'x-token': TOKEN, 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
    return await r.json();
  } catch {
    if (!backdlgShown && !$('#backdlg').open) { backdlgShown = true; openBackDlg(); }
    return { ok: false, error: 'backend_down', message: 'Không kết nối được backend. Hãy chạy "npm start" rồi nhập địa chỉ backend.' };
  }
}
let backdlgShown = false;
function openBackDlg() { $('#b-url').value = API_BASE || 'http://127.0.0.1:3000'; $('#b-token').value = TOKEN; $('#backdlg').showModal(); }
$('#btn-backend').onclick = openBackDlg;
$('#b-cancel').onclick = () => $('#backdlg').close();
$('#b-ok').onclick = async () => {
  let u = $('#b-url').value.trim().replace(/\/+$/, '');
  if (u && !/^https?:\/\//i.test(u)) u = 'http://' + u;
  API_BASE = u; TOKEN = $('#b-token').value.trim(); store.set('api', API_BASE); store.set('tk', TOKEN);
  const r = await api('/health');
  if (!r.ok) { toast('Vẫn không kết nối được backend.', true); return; }
  $('#backdlg').close(); backdlgShown = false; toast('Đã lưu. Đang tải lại…'); setTimeout(() => location.reload(), 600);
};
let tt;
function toast(msg, err) {
  const t = $('#toast'); t.textContent = msg; t.className = 'show' + (err ? ' err' : '');
  clearTimeout(tt); tt = setTimeout(() => (t.className = ''), 3000);
}
function showTab(name) {
  $$('.tab').forEach(t => t.classList.toggle('on', t.id === 'tab-' + name));
  $$('nav button').forEach(n => n.classList.toggle('on', n.dataset.tab === name));
  if (name === 'tv') scan();
}
$$('[data-tab]').forEach(b => b.addEventListener('click', () => showTab(b.dataset.tab)));

// ---- Trạng thái ----
const LABELS = { disconnected: 'Chưa kết nối', discovering: 'Đang tìm thiết bị', pairing: 'Đang chờ ghép đôi',
  connecting: 'Đang kết nối', connected: 'Đã kết nối', error: 'Thiết bị không tương thích / lỗi' };
function render(s) {
  curState = s.state; connected = s.state === 'connected'; curHost = (s.device && s.device.host) || '';
  let label = LABELS[s.state] || s.state;
  if (s.state === 'disconnected' && s.error && s.error.code === 'lost') label = 'Mất kết nối';
  if (s.state === 'error' && s.error) label = s.error.message;
  $('#status').className = s.state === 'connecting' || s.state === 'pairing' ? 'busy' : s.state;
  const d = s.device || {};
  $('#stxt').textContent = connected ? `Đã kết nối · ${d.name || d.model || d.host}` : label;
  $('#remote').classList.toggle('off', !connected);
  $('#devinfo').textContent = connected ? `${d.name || 'TV'} (${d.host})` : 'Chưa kết nối.';
  if (s.state === 'pairing' && !$('#pairdlg').open) { $('#atvcode').value = ''; $('#pairdlg').showModal(); }
  if (s.state !== 'pairing' && $('#pairdlg').open) $('#pairdlg').close();
  renderDevices(lastList);
}
async function poll() {
  const r = await api('/device/status');
  if (r.ok) render(r); else render({ state: 'error', error: { message: r.message }, device: {} });
}

// ---- Danh sách thiết bị ----
let lastList = [];
function renderDevices(list) {
  lastList = list || [];
  const box = $('#devlist'); box.textContent = '';
  lastList.forEach(d => {
    const b = document.createElement('button'); b.className = 'dev' + (connected && d.host === curHost ? ' on' : '');
    b.innerHTML = '<svg class="ico" viewBox="0 0 24 24"><rect x="2.5" y="4" width="19" height="13" rx="2.5"/><path d="M8 21h8M12 17v4"/></svg><span></span>';
    $('span', b).textContent = d.name; // tên đến từ mạng: chỉ gán textContent
    const sm = document.createElement('small');
    sm.textContent = connected && d.host === curHost ? 'Đã kết nối' : d.host; $('span', b).appendChild(sm);
    b.onclick = () => connectDevice(d.host, d.method);
    box.appendChild(b);
  });
}
async function loadDevices() {
  const r = await api('/devices'); if (!r.ok) { $('#nodev').textContent = r.message; return; }
  renderDevices(r.devices); $('#spin').style.display = r.discovering ? 'inline-block' : 'none';
  $('#nodev').textContent = !r.devices.length && !r.discovering
    ? 'Không tìm thấy thiết bị. Kiểm tra TV đang bật, cùng Wi-Fi, hoặc nhập IP thủ công bên dưới.' : '';
  if (r.discovering) setTimeout(loadDevices, 1500);
}
async function scan() { await api('/discovery/start', {}); loadDevices(); }
$('#btn-rescan').onclick = scan;

async function connectDevice(host, method, port) {
  $('#status').className = 'busy'; $('#stxt').textContent = LABELS.connecting;
  toast('Đang kết nối…');
  const r = await api('/devices/connect', { host, method, port });
  if (!r.ok) toast(r.message, true); else render(r);
  if (r.ok && r.state === 'connected') { toast('Đã kết nối.'); showTab('remote'); }
}
$('#btn-connect').onclick = () => connectDevice($('#host').value, $('#method').value, $('#port').value);
$('#btn-disc').onclick = async () => {
  if (!confirm('Ngắt kết nối với TV?')) return;
  const r = await api('/devices/disconnect', {}); if (r.ok) render(r); else toast(r.message, true);
};
$('#btn-pair').onclick = async () => {
  const r = await api('/devices/pair', { method: 'adb', host: $('#host').value, port: $('#pport').value, code: $('#pcode').value });
  toast(r.ok ? 'Ghép đôi ADB thành công. Chọn ADB và bấm Kết nối.' : r.message, !r.ok);
  if (r.ok) $('#pcode').value = '';
};
// Ghép đôi Android TV Remote: nhập mã hiện trên TV
$('#pair-ok').onclick = async () => {
  const code = $('#atvcode').value.trim();
  $('#pair-ok').disabled = true;
  const r = await api('/devices/pair', { method: 'atv', code });
  $('#pair-ok').disabled = false;
  if (!r.ok) { toast(r.message, true); poll(); return; }
  $('#pairdlg').close(); render(r); toast('Ghép đôi thành công.'); showTab('remote');
};
$('#pair-cancel').onclick = async () => { $('#pairdlg').close(); const r = await api('/devices/disconnect', {}); if (r.ok) render(r); };

// ---- Điều khiển remote ----
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function sendRemoteCommand(command) {
  if (!connected) { toast('Chưa kết nối với TV. Mở tab TV để chọn thiết bị.', true); return false; }
  if (navigator.vibrate) navigator.vibrate(10);
  const r = await api('/remote/command', { command });
  if (!r.ok) { toast(r.message, true); if (r.error === 'not_connected' || r.error === 'send_failed') poll(); return false; }
  return true;
}
$$('[data-cmd]').forEach(btn => {
  let held = false;
  const stop = () => { held = false; btn.classList.remove('hold'); };
  btn.addEventListener('pointerdown', async e => {
    e.preventDefault(); const cmd = btn.dataset.cmd; held = true; btn.classList.add('hold');
    const ok = await sendRemoteCommand(cmd);
    if (!ok || !btn.hasAttribute('data-rep')) return stop();
    await sleep(400);
    while (held && await sendRemoteCommand(cmd)) await sleep(80);
    stop();
  });
  ['pointerup', 'pointerleave', 'pointercancel'].forEach(ev => btn.addEventListener(ev, stop));
  btn.addEventListener('click', e => e.preventDefault());
});

// ---- Văn bản & cài đặt ----
$('#btn-clr').onclick = () => ($('#txt').value = '');
$('#btn-send').onclick = async () => { const r = await api('/remote/text', { text: $('#txt').value }); toast(r.ok ? 'Đã gửi.' : r.message, !r.ok); };
$('#btn-save').onclick = async () => {
  const r = await api('/settings', { name: $('#s-name').value,
    packages: { netflix: $('#s-netflix').value, prime: $('#s-prime').value, disney: $('#s-disney').value } });
  toast(r.ok ? 'Đã lưu cài đặt.' : r.message, !r.ok);
};
(async () => {
  const r = await api('/settings');
  if (r.ok) {
    const s = r.settings; $('#host').value = s.host; $('#port').value = s.port; $('#s-name').value = s.name; $('#method').value = s.method || 'atv';
    $('#s-netflix').value = s.packages.netflix; $('#s-prime').value = s.packages.prime; $('#s-disney').value = s.packages.disney;
  } else toast(r.message, true);
  await poll(); setInterval(poll, 5000);
  if (!connected) showTab('tv'); // chưa kết nối: mở thẳng màn hình chọn thiết bị như Google TV
})();
