/*
 * Smart Exam Seating Management System - frontend session layer.
 *
 * Loaded after index.html's inline script so it can reuse its globals
 * ($, esc, jsonOpts, loaders, students, rooms, exams, batches, ...) and
 * replace individual renderers/actions with extended versions.
 *
 * Sections:
 *   1. CSRF + API wrapper (401 -> login screen)
 *   2. Session, login/logout, role routing
 *   3. Admin enhancements (real dashboard stats, student status/reset,
 *      exam lifecycle + eligibility, room building/floor/status, publish)
 *   4. Student dashboard (overview, my exams, my seat + print, profile)
 *   5. Boot
 */
(function () {
'use strict';

/* ==================== 1. CSRF + API ==================== */

/*
 * CSRF: the token returned by GET /api/auth/csrf is accepted for any number of
 * mutations; the XSRF-TOKEN cookie can lag behind it, so we cache the server's
 * token instead of echoing the cookie, and re-fetch once when a POST is rejected.
 */
let csrfTokenCache = '';
async function refreshCsrf() {
    try {
        const res = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
        const data = await res.json();
        csrfTokenCache = (data && data.token) || '';
    } catch (e) { csrfTokenCache = ''; }
    return csrfTokenCache;
}
async function ensureCsrf() {
    if (!csrfTokenCache) await refreshCsrf();
    return csrfTokenCache;
}

/** Replacement for the inline api(): adds the CSRF header, handles 401 and retries 403 once. */
window.api = async function api(url, opts) {
    opts = opts || {};
    const method = (opts.method || 'GET').toUpperCase();
    const mutating = method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS';

    const send = async (token) => fetch(url, Object.assign({}, opts, {
        headers: Object.assign({}, opts.headers, token ? { 'X-XSRF-TOKEN': token } : {})
    }));

    let res = mutating ? await send(await ensureCsrf()) : await send(null);
    if (mutating && res.status === 403) {
        // Stale token (e.g. session rotated on login): refresh and retry once.
        res = await send(await refreshCsrf());
    }
    if (res.status === 401) {
        showLogin('Your session has expired. Please sign in again.');
        throw new Error('Please sign in to continue.');
    }
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
    if (!res.ok) throw new Error((data && data.message) || text || ('HTTP ' + res.status));
    return data;
};

/* ==================== 2. Session / login / routing ==================== */

let session = null;

async function fetchMe() {
    try {
        const res = await fetch('/api/auth/me', { credentials: 'same-origin' });
        if (!res.ok) return null;
        const data = await res.json();
        return data && data.username ? data : null;
    } catch (e) { return null; }
}

function showLogin(message) {
    session = null;
    $('admin-app').style.display = 'none';
    $('student-app').style.display = 'none';
    $('login-screen').style.display = 'flex';
    const err = $('login-error');
    if (message) { err.textContent = message; err.classList.add('show'); }
    else { err.textContent = ''; err.classList.remove('show'); }
    setTimeout(() => { const u = $('login-username'); if (u && !u.value) u.focus(); }, 30);
}

async function route(me) {
    session = me;
    $('login-screen').style.display = 'none';
    if (me.role === 'ADMIN') {
        $('student-app').style.display = 'none';
        $('admin-app').style.display = 'block';
        await bootAdmin();
    } else {
        $('admin-app').style.display = 'none';
        $('student-app').style.display = 'block';
        await bootStudent(me);
    }
}

async function doLogin(username, password) {
    await refreshCsrf();   // login itself is CSRF-exempt; this warms the token cache
    const res = await fetch('/api/auth/login', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: username, password: password })
    });
    let data = null;
    try { data = await res.json(); } catch (e) { /* non-JSON */ }
    if (!res.ok) throw new Error((data && data.message) || 'Sign in failed. Please try again.');
    return data;
}

async function logout() {
    try {
        await window.api('/api/auth/logout', jsonOpts('POST', {}));
    } catch (e) { /* already signed out */ }
    location.reload();
}

function setBusy(button, busy, label) {
    if (busy) {
        button.dataset.label = button.textContent;
        button.innerHTML = '<span class="spinner" aria-hidden="true"></span>' + label;
        button.disabled = true;
    } else {
        button.textContent = button.dataset.label || 'Sign in';
        button.disabled = false;
    }
}

/* ==================== 3. Admin enhancements ==================== */

let adminPrepared = false;

async function bootAdmin() {
    if (!adminPrepared) { prepareAdmin(); adminPrepared = true; }
    setImportMode('students');
    await loadBatches();
    showSection('dashboard');
}

function prepareAdmin() {
    /* --- inject an End time field into the exam form --- */
    const examForm = $('exam-form');
    if (examForm && !$('e-endtime')) {
        const appField = $('e-appid').closest('.field');
        appField.insertAdjacentHTML('beforebegin',
            '<div class="field"><label for="e-endtime">End time</label>' +
            '<input id="e-endtime" type="time" enterkeyhint="next"></div>');
    }

    /* --- status filter for the student table --- */
    if (!$('student-status-filter')) {
        $('student-search').insertAdjacentHTML('afterend',
            '<select id="student-status-filter" onchange="renderStudents()" aria-label="Filter by status">' +
            '<option value="">All statuses</option><option value="ACTIVE">Active only</option>' +
            '<option value="INACTIVE">Inactive only</option></select>');
    }

    /* --- dashboard from real SQL statistics --- */
    loaders.dashboard = adminDashboard;

    /* --- extended renderers / actions --- */
    window.renderStudents = renderStudentsX;
    window.renderRooms = renderRoomsX;
    window.renderExams = renderExamsX;
    window.addRoom = addRoomX;
    window.addExam = addExamX;

    /* --- extra row actions --- */
    $('student-list').addEventListener('click', onStudentExtraAction);
    $('exam-list').addEventListener('click', onExamExtraAction);
    $('room-list').addEventListener('click', onRoomExtraAction);

    /* --- new sections: calendar, reports, announcements, settings --- */
    loaders.calendar = loadCalendar;
    loaders.reports = loadReports;
    loaders.announcements = loadAnnouncements;
    loaders.settings = function () {};

    /* --- student-table pager (delegated) --- */
    $('student-pager').addEventListener('click', e => {
        const b = e.target.closest('button[data-pg]');
        if (!b) return;
        studentPage += b.dataset.pg === 'next' ? 1 : -1;
        renderStudents();
    });

    /* --- announcement delete (delegated) --- */
    document.addEventListener('click', async e => {
        const btn = e.target.closest('button[data-del-ann]');
        if (!btn) return;
        if (!confirm('Delete this announcement?')) return;
        try {
            await api('/api/admin/announcements/' + btn.dataset.delAnn, jsonOpts('DELETE', {}));
            toast('Announcement deleted.');
            await loadAnnouncements();
        } catch (err) { toast(err.message, true); }
    });

    /* --- availability checkbox on each room row (Select column) --- */
    $('room-list').addEventListener('change', onRoomSelectToggle);

    /* --- mobile navigation drawer (animated hamburger) --- */
    wireNavToggle('admin-nav-toggle', 'admin-tabs');

    /* --- allocation publish status + room picker --- */
    const origLoadAllocation = loaders.allocation;
    loaders.allocation = async function () {
        await origLoadAllocation();
        try { rooms = await api('/api/rooms'); } catch (e) { /* keep the cached list */ }
        renderRoomPicker();
        await refreshAllocStatus();
    };
    $('alloc-room-list').addEventListener('change', updateRoomPickerSummary);
    const origGenerate = window.generateAllocation;
    window.generateAllocation = async function () {
        await origGenerate();
        await refreshAllocStatus();
    };
}

/* ---------- dashboard ---------- */

async function adminDashboard() {
    try {
        const s = await api('/api/admin/dashboard');
        $('stat-students').textContent = s.totalStudents;
        $('stat-rooms').textContent = s.activeRooms + ' / ' + s.totalRooms;
        $('stat-batches').textContent = s.totalBatches;
        $('stat-exams').textContent = s.totalExams;
        $('stat-seats').textContent = s.allocatedStudents;
        if ($('stat-upcoming')) $('stat-upcoming').textContent = s.upcomingExams;
        if ($('stat-unallocated')) $('stat-unallocated').textContent = s.unallocatedStudents;
    } catch (err) { toast(err.message, true); }
}

/* ---------- students ---------- */

let studentPage = 1;
let lastStudentFilterKey = null;
const STUDENT_PAGE_SIZE = 50;

function studentStatusBadge(status) {
    return status === 'INACTIVE'
        ? '<span class="badge" style="background:#EF4444">INACTIVE</span>'
        : '<span class="badge" style="background:#10B981">ACTIVE</span>';
}

/** Semester 1-8 single-select used in the student edit rows. */
function semesterSelectHtml(value) {
    let out = '<select data-f="semester" aria-label="Semester"><option value="">-</option>';
    for (let i = 1; i <= 8; i++) {
        out += '<option value="' + i + '"' + (Number(value) === i ? ' selected' : '') + '>Sem ' + i + '</option>';
    }
    return out + '</select>';
}

/** Semester 1-8 multi-select used in the exam edit rows (none = every semester). */
function semesterMultiHtml(list) {
    const picked = new Set((list || []).map(Number));
    let out = '<select multiple size="3" data-f="semesters" aria-label="Semesters - hold Ctrl (Cmd on Mac) for several">';
    for (let i = 1; i <= 8; i++) {
        out += '<option value="' + i + '"' + (picked.has(i) ? ' selected' : '') + '>Sem ' + i + '</option>';
    }
    return out + '</select>';
}

function renderStudentsX() {
    const q = ($('student-search').value || '').toLowerCase();
    const statusFilter = $('student-status-filter') ? $('student-status-filter').value : '';
    const list = students.filter(s =>
        (!q || [s.rollNo, s.name, s.branch, s.batch, String(s.semester ?? '')].some(v => (v || '').toLowerCase().includes(q))) &&
        (!statusFilter || (s.accountStatus || 'ACTIVE') === statusFilter));

    const counter = $('student-count');
    if (counter) counter.textContent = list.length + ' of ' + students.length + ' students';

    const branchList = $('branch-list');
    if (branchList) {
        branchList.innerHTML = [...new Set(students.map(s => (s.branch || '').trim()).filter(Boolean))]
            .map(b => '<option value="' + esc(b) + '">').join('');
    }

    // client-side pagination keeps big lists (200+ students) fast to render
    const filterKey = q + '|' + statusFilter;
    if (filterKey !== lastStudentFilterKey) { lastStudentFilterKey = filterKey; studentPage = 1; }
    const pages = Math.max(1, Math.ceil(list.length / STUDENT_PAGE_SIZE));
    if (studentPage > pages) studentPage = pages;
    const pageRows = list.slice((studentPage - 1) * STUDENT_PAGE_SIZE, studentPage * STUDENT_PAGE_SIZE);
    const pager = $('student-pager');
    if (pager) {
        pager.innerHTML = pages > 1
            ? '<button type="button" class="btn btn-sm btn-ghost" data-pg="prev"' + (studentPage <= 1 ? ' disabled' : '') + '>&lsaquo; Prev</button>' +
              '<span class="hint" style="margin:0">Page ' + studentPage + ' of ' + pages + '</span>' +
              '<button type="button" class="btn btn-sm btn-ghost" data-pg="next"' + (studentPage >= pages ? ' disabled' : '') + '>Next &rsaquo;</button>'
            : '';
    }

    const tbody = $('student-list');
    if (!list.length) {
        tbody.innerHTML = '<tr><td colspan="8" class="empty">No students found. Add one above or import a file.</td></tr>';
        return;
    }
    tbody.innerHTML = pageRows.map(s => {
        if (editingStudentId === s.id) {
            return '<tr data-edit data-id="' + s.id + '">' +
                '<td><input data-f="rollNo" value="' + esc(s.rollNo) + '" placeholder="Roll No"></td>' +
                '<td><input data-f="name" value="' + esc(s.name) + '" placeholder="Name"></td>' +
                '<td><input data-f="branch" value="' + esc(s.branch) + '" placeholder="Branch"></td>' +
                '<td>' + batchSelectHtml('batch', s.batch) + '</td>' +
                '<td>' + semesterSelectHtml(s.semester) + '</td>' +
                '<td><input data-f="email" value="' + esc(s.email) + '" placeholder="Email"></td>' +
                '<td><input data-f="phone" value="' + esc(s.phone) + '" placeholder="Phone" style="min-width:110px"></td>' +
                '<td class="actions no-print">' +
                '<button class="btn btn-sm btn-success" data-act="save" data-id="' + s.id + '">Save</button>' +
                '<button class="btn btn-sm btn-ghost" data-act="cancel" data-id="' + s.id + '">Cancel</button>' +
                '</td></tr>';
        }
        const active = (s.accountStatus || 'ACTIVE') !== 'INACTIVE';
        return '<tr>' +
            '<td><b>' + cellText(s.rollNo) + '</b></td>' +
            '<td>' + cellText(s.name) + '</td>' +
            '<td>' + cellText(s.branch) + '</td>' +
            '<td>' + cellText(s.batch) + '</td>' +
            '<td>' + (s.semester == null ? '<span class="null-chip">null</span>' : 'Sem ' + esc(s.semester)) + '</td>' +
            '<td class="hint" style="margin:0">' + cellText(s.email) + (s.phone ? '<br>' + esc(s.phone) : '') + '</td>' +
            '<td>' + studentStatusBadge(s.accountStatus || 'ACTIVE') + '</td>' +
            '<td class="actions no-print">' +
            '<button class="btn btn-sm btn-ghost" data-act="edit" data-id="' + s.id + '">Edit</button>' +
            '<button class="btn btn-sm btn-amber" data-act="reset" data-id="' + s.id + '">Reset password</button>' +
            '<button class="btn btn-sm ' + (active ? 'btn-danger' : 'btn-success') + '" data-act="status" data-id="' + s.id + '">' +
            (active ? 'Deactivate' : 'Activate') + '</button>' +
            '<button class="btn btn-sm btn-danger" data-act="delete" data-id="' + s.id + '">Delete</button>' +
            '</td></tr>';
    }).join('');
}

async function onStudentExtraAction(e) {
    const btn = e.target.closest('button');
    if (!btn) return;
    const id = Number(btn.dataset.id);
    const student = students.find(s => s.id === id);
    try {
        if (btn.dataset.act === 'reset') {
            const pwd = prompt('Set a new password for ' + (student ? student.rollNo : id) + ' (at least 4 characters):');
            if (pwd === null) return;
            const res = await api('/api/students/' + id + '/reset-password', jsonOpts('POST', { password: pwd }));
            toast(res.message || 'Password reset.');
        } else if (btn.dataset.act === 'status') {
            const current = (student && student.accountStatus) || 'ACTIVE';
            const next = current === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
            if (next === 'INACTIVE' && !confirm('Deactivate this student? They will no longer be able to sign in.')) return;
            await api('/api/students/' + id + '/status', jsonOpts('POST', { status: next }));
            toast('Student ' + (next === 'ACTIVE' ? 'activated' : 'deactivated') + '.');
            await loadStudents();
        }
    } catch (err) { toast(err.message, true); }
}

/* ---------- rooms ---------- */

function renderRoomsX() {
    const tbody = $('room-list');
    const q = ($('room-search') ? $('room-search').value : '').trim().toLowerCase();
    const statusFilter = $('room-status-filter') ? $('room-status-filter').value : '';
    const isAvailable = r => r.active !== false;
    const filtered = rooms.filter(r =>
        (!q || [r.roomNumber, r.name, r.building, r.floor]
            .some(v => String(v == null ? '' : v).toLowerCase().includes(q))) &&
        (!statusFilter || (statusFilter === 'active' ? isAvailable(r) : !isAvailable(r))));
    const counter = $('room-count');
    if (counter) counter.textContent = filtered.length + ' of ' + rooms.length + ' rooms';
    if (!rooms.length) {
        tbody.innerHTML = '<tr><td colspan="12" class="empty">No rooms yet. Add the first exam room above.</td></tr>';
        return;
    }
    if (!filtered.length) {
        tbody.innerHTML = '<tr><td colspan="12" class="empty">No rooms match your search. Searching never changes stored rooms or their availability.</td></tr>';
        return;
    }
    tbody.innerHTML = filtered.map(r => {
        const per = r.studentsPerDesk || 1;
        const desks = r.rowsCount * r.columnsCount;
        const active = r.active !== false;
        if (editingRoomId === r.id) {
            return '<tr data-edit data-id="' + r.id + '">' +
                '<td><input data-f="roomNumber" value="' + esc(r.roomNumber) + '" placeholder="Room No"></td>' +
                '<td><input data-f="name" value="' + esc(r.name || '') + '" placeholder="Room name (optional)"></td>' +
                '<td><input data-f="building" value="' + esc(r.building) + '" placeholder="Building"></td>' +
                '<td><input data-f="floor" type="number" value="' + (r.floor == null ? '' : r.floor) + '" placeholder="Floor"></td>' +
                '<td><input data-f="rowsCount" data-k="rows" type="number" min="1" value="' + r.rowsCount + '"></td>' +
                '<td><input data-f="columnsCount" data-k="cols" type="number" min="1" value="' + r.columnsCount + '"></td>' +
                '<td><input data-f="desks" data-k="desks" type="number" min="1" value="' + desks + '"></td>' +
                '<td><input data-f="studentsPerDesk" data-k="per" type="number" min="1" value="' + per + '"></td>' +
                '<td><input data-f="capacity" data-k="cap" type="number" min="0" value="' + r.capacity + '" data-auto="' + (desks * per) + '"></td>' +
                '<td><select data-f="active"><option value="true"' + (active ? ' selected' : '') + '>Active</option>' +
                '<option value="false"' + (!active ? ' selected' : '') + '>Inactive</option></select></td>' +
                '<td class="hint" style="margin:0">via Status</td>' +
                '<td class="actions no-print">' +
                '<button class="btn btn-sm btn-success" data-act="save" data-id="' + r.id + '">Save</button>' +
                '<button class="btn btn-sm btn-ghost" data-act="cancel" data-id="' + r.id + '">Cancel</button>' +
                '</td></tr>';
        }
        return '<tr' + (active ? '' : ' style="opacity:.55"') + '>' +
            '<td><b>' + esc(r.roomNumber) + '</b></td>' +
            '<td>' + (r.name && String(r.name).trim() ? esc(r.name) : '<span class="muted">&mdash;</span>') + '</td>' +
            '<td>' + cellText(r.building) + '</td>' +
            '<td>' + cellText(r.floor) + '</td>' +
            '<td>' + r.rowsCount + '</td>' +
            '<td>' + r.columnsCount + '</td>' +
            '<td>' + desks + '</td>' +
            '<td>' + per + '</td>' +
            '<td>' + r.capacity + '</td>' +
            '<td>' + (active
                ? '<span class="badge" style="background:#10B981">ACTIVE</span>'
                : '<span class="badge" style="background:#EF4444">INACTIVE</span>') + '</td>' +
            '<td><input type="checkbox" data-select-room="' + r.id + '"' + (active ? ' checked' : '') +
                ' aria-label="Room ' + esc(r.roomNumber) + ' available for seating allocation"></td>' +
            '<td class="actions no-print">' +
            '<button class="btn btn-sm btn-ghost" data-act="edit" data-id="' + r.id + '">Edit</button>' +
            '<button class="btn btn-sm ' + (active ? 'btn-danger' : 'btn-success') + '" data-act="status" data-id="' + r.id + '">' +
            (active ? 'Deactivate' : 'Activate') + '</button>' +
            '<button class="btn btn-sm btn-danger" data-act="delete" data-id="' + r.id + '">Delete</button>' +
            '</td></tr>';
    }).join('');
}

async function onRoomExtraAction(e) {
    const btn = e.target.closest('button');
    if (!btn || btn.dataset.act !== 'status') return;
    const id = Number(btn.dataset.id);
    const room = rooms.find(r => r.id === id);
    try {
        const next = !(room && room.active !== false);
        await api('/api/rooms/' + id + '/status', jsonOpts('POST', { active: next }));
        toast('Room ' + (next ? 'activated' : 'deactivated') + '.');
        await loadRooms();
    } catch (err) { toast(err.message, true); }
}

async function onRoomSelectToggle(e) {
    const cb = e.target.closest('input[data-select-room]');
    if (!cb) return;
    const id = Number(cb.dataset.selectRoom);
    try {
        await api('/api/rooms/' + id + '/status', jsonOpts('POST', { active: cb.checked }));
        toast(cb.checked
            ? 'Room marked available for seating allocations.'
            : 'Room excluded from new seating allocations. Existing published seats are kept.');
        await loadRooms();
    } catch (err) {
        toast(err.message, true);
        renderRoomsX();   // revert the checkbox to the stored state
    }
}

/**
 * Room picker on the Seating Chart tab: choose which available rooms this
 * allocation may use, with a live total-capacity check against the number of
 * eligible students (make edit / to add spec §4).
 */
let selectedRoomIds = null;   // null = default: every available room

function renderRoomPicker() {
    const list = $('alloc-room-list');
    if (!list) return;
    const available = rooms.filter(r => r.active !== false);
    if (!available.length) {
        list.innerHTML = '<p class="hint" style="margin:0">No available rooms. Add rooms and tick their Select box in the Rooms tab first.</p>';
        updateRoomPickerSummary();
        return;
    }
    if (selectedRoomIds === null) selectedRoomIds = new Set(available.map(r => r.id));
    list.innerHTML = available.map(r =>
        '<label class="room-check">' +
        '<input type="checkbox" data-room-id="' + r.id + '"' + (selectedRoomIds.has(r.id) ? ' checked' : '') + '>' +
        '<span><b>' + esc(r.roomNumber) + '</b>' + (r.name && String(r.name).trim() ? ' &middot; ' + esc(r.name) : '') +
        '<div class="rc-meta">Capacity ' + r.capacity +
        (r.building ? ' &middot; ' + esc(r.building) : '') +
        (r.floor != null ? ' &middot; Floor ' + r.floor : '') + '</div></span>' +
        '</label>').join('');
    updateRoomPickerSummary();
}

function updateRoomPickerSummary(e) {
    if (e && e.target && e.target.matches('input[data-room-id]')) {
        const id = Number(e.target.dataset.roomId);
        if (!selectedRoomIds) selectedRoomIds = new Set();
        if (e.target.checked) selectedRoomIds.add(id); else selectedRoomIds.delete(id);
    }
    const warn = $('alloc-room-warn');
    if (!warn) return;
    const ids = new Set([...document.querySelectorAll('#alloc-room-list input[data-room-id]:checked')]
        .map(c => Number(c.dataset.roomId)));
    const roomList = (typeof rooms !== 'undefined' ? rooms : []).filter(r => ids.has(r.id));
    const total = roomList.reduce((n, r) => n + (r.capacity || 0), 0);
    const exam = (typeof exams !== 'undefined' ? exams : []).find(x => x.id === selectedExamId) || null;
    const eligible = (typeof students !== 'undefined' ? students : []).filter(s => matchesExam(s, exam));
    warn.style.display = 'block';
    if (!ids.size) {
        warn.className = 'message-box err';
        warn.textContent = 'No room selected — tick at least one available room before generating the chart.';
    } else if (total < eligible.length) {
        warn.className = 'message-box err';
        warn.innerHTML = '<b>Capacity warning:</b> the selected rooms seat ' + total +
            ' student(s), but ' + eligible.length + ' student(s) are eligible for this exam. Add more rooms or a bigger room.';
    } else {
        warn.className = 'message-box ok';
        warn.textContent = 'Selected ' + roomList.length + ' room(s), ' + total +
            ' seats — enough for ' + eligible.length + ' eligible student(s).';
    }
}

async function addRoomX() {
    const form = $('room-form');
    const val = k => Number(form.querySelector('[data-k="' + k + '"]').value) || 0;
    let rows = val('rows'), cols = val('cols'), desks = val('desks');
    const per = val('per') || 3;

    if ((!rows || !cols) && desks > 0) {
        const g = predictGrid(desks);
        if (g) { rows = g[0]; cols = g[1]; }
    }
    if (rows > 0 && cols > 0) desks = rows * cols;
    if (!rows || !cols) { rows = rows || 1; cols = cols || 1; desks = rows * cols; }

    const floorRaw = $('r-floor') ? $('r-floor').value : '';
    const payload = {
        roomNumber: $('r-no').value.trim() || null,
        name: $('r-name') ? ($('r-name').value.trim() || null) : null,
        building: $('r-building') ? ($('r-building').value.trim() || null) : null,
        floor: floorRaw === '' ? null : Number(floorRaw),
        rowsCount: rows,
        columnsCount: cols,
        studentsPerDesk: per,
        capacity: Number($('r-cap').value) || desks * per,
        active: true
    };
    if (!payload.roomNumber) { toast('Enter a room number.', true); $('r-no').focus(); return; }
    try {
        await api('/api/rooms', jsonOpts('POST', payload));
        ['r-no', 'r-rows', 'r-cols', 'r-desks', 'r-cap'].forEach(id => $(id).value = '');
        if ($('r-name')) $('r-name').value = '';
        if ($('r-building')) $('r-building').value = '';
        if ($('r-floor')) $('r-floor').value = '';
        $('r-per').value = '3';
        delete $('r-cap').dataset.auto;
        $('r-no').focus();
        toast('Room added: ' + rows + ' x ' + cols + ' desks, capacity ' + payload.capacity + '.');
        await loadRooms();
    } catch (e) { toast(e.message, true); }
}

/* ---------- exams ---------- */

const EXAM_STATUS = {
    DRAFT:      { color: '#6B7280', label: 'DRAFT' },
    PUBLISHED:  { color: '#10B981', label: 'PUBLISHED' },
    COMPLETED:  { color: '#0EA5E9', label: 'COMPLETED' },
    CANCELLED:  { color: '#EF4444', label: 'CANCELLED' }
};

function examStatusBadge(status) {
    const s = EXAM_STATUS[status] || EXAM_STATUS.DRAFT;
    return '<span class="badge" style="background:' + s.color + '">' + s.label + '</span>';
}

function examActions(x) {
    const id = x.id, st = x.status || 'DRAFT';
    const b = [];
    if (st === 'DRAFT') {
        b.push('<button class="btn btn-sm btn-success" data-act="status" data-status="PUBLISHED" data-id="' + id + '">Publish</button>');
    }
    if (st === 'PUBLISHED') {
        b.push('<button class="btn btn-sm btn-success" data-act="status" data-status="COMPLETED" data-id="' + id + '">Complete</button>');
    }
    if (st !== 'COMPLETED') {
        b.push('<button class="btn btn-sm btn-danger" data-act="status" data-status="CANCELLED" data-id="' + id + '">Cancel</button>');
    }
    if (st === 'COMPLETED' || st === 'CANCELLED') {
        b.push('<button class="btn btn-sm btn-ghost" data-act="status" data-status="DRAFT" data-id="' + id + '">Reopen</button>');
    }
    b.push('<button class="btn btn-sm btn-ghost" data-act="eligibility" data-id="' + id + '">Audience</button>');
    if (st === 'DRAFT' || st === 'CANCELLED') {
        b.push('<button class="btn btn-sm btn-ghost" data-act="edit" data-id="' + id + '">Edit</button>');
        b.push('<button class="btn btn-sm btn-danger" data-act="delete" data-id="' + id + '">Delete</button>');
    }
    return b.join('');
}

function renderExamsX() {
    const tbody = $('exam-list');
    if (!exams.length) {
        tbody.innerHTML = '<tr><td colspan="7" class="empty">No exams yet. Create one above or upload exam data (PDF/Excel) in the Import Data tab.</td></tr>';
        return;
    }
    tbody.innerHTML = exams.map(x => {
        if (editingExamId === x.id) {
            return '<tr data-edit data-id="' + x.id + '">' +
                '<td><input data-f="subjectName" value="' + esc(x.subjectName) + '" placeholder="Exam / Subject"></td>' +
                '<td><input data-f="branch" value="' + esc(x.branch) + '" placeholder="Branch"></td>' +
                '<td>' + batchSelectHtml('course', x.course, 'All') + '</td>' +
                '<td>' + semesterMultiHtml(x.semesters) + '</td>' +
                '<td><input data-f="examDate" type="date" value="' + esc(x.examDate) + '"></td>' +
                '<td><input data-f="examTime" type="time" value="' + ((x.examTime || x.startTime) ? String(x.examTime || x.startTime).slice(0, 5) : '') + '"></td>' +
                '<td><input data-f="endTime" type="time" value="' + (x.endTime ? String(x.endTime).slice(0, 5) : '') + '" placeholder="End time"></td>' +
                '<td class="actions no-print">' +
                '<button class="btn btn-sm btn-success" data-act="save" data-id="' + x.id + '">Save</button>' +
                '<button class="btn btn-sm btn-ghost" data-act="cancel" data-id="' + x.id + '">Cancel</button>' +
                '</td></tr>';
        }
        const branch = x.branch ? esc(x.branch) : '<span class="muted">All</span>';
        const batch = x.course ? '<span class="badge" style="background:#4F46E5">' + esc(x.course) + '</span>' : '<span class="muted">All</span>';
        const semesters = (x.semesters && x.semesters.length)
            ? '<span class="badge" style="background:#0EA5E9">Sem ' + x.semesters.map(esc).join(', Sem ') + '</span>'
            : '<span class="muted">All</span>';
        const eligibilityRow = '<tr id="elig-' + x.id + '" style="display:none"><td colspan="8" class="hint" id="elig-body-' + x.id + '"></td></tr>';
        return '<tr>' +
            '<td><b>' + esc(x.subjectName) + '</b>' +
                (x.subjectCode ? ' <span class="muted">(' + esc(x.subjectCode) + ')</span>' : '') +
                '<div class="hint" style="margin:0">' + esc(x.applicationId || '') +
                (x.durationMinutes ? ' · ' + x.durationMinutes + ' min' : '') + '</div></td>' +
            '<td>' + branch + '</td>' +
            '<td>' + batch + '</td>' +
            '<td>' + semesters + '</td>' +
            '<td>' + fmtDate(x.examDate) + '</td>' +
            '<td>' + fmtTime(x.examTime || x.startTime) + (x.endTime ? '–' + fmtTime(x.endTime) : '') + '</td>' +
            '<td>' + examStatusBadge(x.status) + '</td>' +
            '<td class="actions no-print">' + examActions(x) + '</td>' +
            '</tr>' + eligibilityRow;
    }).join('');
}

async function onExamExtraAction(e) {
    const btn = e.target.closest('button');
    if (!btn) return;
    const id = Number(btn.dataset.id);
    try {
        if (btn.dataset.act === 'status') {
            const next = btn.dataset.status;
            const labels = { PUBLISHED: 'publish', COMPLETED: 'mark completed', CANCELLED: 'cancel', DRAFT: 'reopen as draft' };
            const ask = next === 'CANCELLED'
                ? 'Cancel this exam? Published seating will be hidden from students.'
                : 'Do you want to ' + (labels[next] || 'update') + ' this exam?';
            if (!confirm(ask)) return;
            await api('/api/exams/' + id + '/status', jsonOpts('POST', { status: next }));
            toast('Exam ' + (labels[next] || 'updated') + '.');
            await loadExams();
        } else if (btn.dataset.act === 'eligibility') {
            await toggleEligibility(id);
        }
    } catch (err) { toast(err.message, true); }
}

async function toggleEligibility(examId) {
    const row = $('elig-' + examId);
    if (!row) return;
    if (row.style.display !== 'none') { row.style.display = 'none'; return; }
    await loadBatches();
    const [current, currentSemesters] = await Promise.all([
        api('/api/exams/' + examId + '/eligibility'),
        api('/api/exams/' + examId + '/semesters')
    ]);
    const body = $('elig-body-' + examId);
    const semBoxes = Array.from({ length: 8 }, function (_, i) {
        const n = i + 1;
        return '<label style="font-weight:600;text-transform:none"><input type="checkbox" data-semester="' + n + '"' +
            (currentSemesters.includes(n) ? ' checked' : '') + '> Sem ' + n + '</label>';
    }).join('');
    body.innerHTML = '<div style="display:flex;gap:.6rem;flex-wrap:wrap;align-items:center">' +
        '<b>Publish to batches:</b>' +
        batches.map(b => '<label style="font-weight:600;text-transform:none"><input type="checkbox" data-batch="' + esc(b.name) + '"' +
            (current.includes(b.name) ? ' checked' : '') + '> ' + esc(b.name) + '</label>').join('') +
        '</div>' +
        '<div style="display:flex;gap:.6rem;flex-wrap:wrap;align-items:center;margin-top:.35rem">' +
        '<b>Semesters:</b>' + semBoxes +
        '<button class="btn btn-sm btn-success" data-act="save-elig" data-id="' + examId + '">Save</button>' +
        '<span class="muted">No selection = every batch / semester sees this exam.</span></div>';
    row.style.display = '';
    body.querySelector('[data-act="save-elig"]').addEventListener('click', async () => {
        const picked = [...body.querySelectorAll('input[data-batch]:checked')].map(c => c.dataset.batch);
        const semesters = [...body.querySelectorAll('input[data-semester]:checked')].map(c => Number(c.dataset.semester));
        try {
            await api('/api/exams/' + examId + '/eligibility', jsonOpts('PUT', { batches: picked }));
            await api('/api/exams/' + examId + '/semesters', jsonOpts('PUT', { semesters: semesters }));
            toast((picked.length ? 'Batches: ' + picked.join(', ') : 'All batches') +
                ' · ' + (semesters.length ? 'Semesters: ' + semesters.join(', ') : 'All semesters') + '.');
            row.style.display = 'none';
            await loadExams();
        } catch (err) { toast(err.message, true); }
    });
}

async function addExamX() {
    const semesters = $('e-semesters')
        ? [...$('e-semesters').selectedOptions].map(o => Number(o.value)).filter(n => n >= 1 && n <= 8)
        : [];
    const payload = {
        subjectName: $('e-subject').value.trim() || null,
        branch: $('e-branch').value.trim() || null,
        course: $('e-course').value.trim() || null,
        semesters: semesters,
        examDate: $('e-date').value || null,
        examTime: $('e-time').value || null,
        endTime: $('e-endtime') ? ($('e-endtime').value || null) : null,
        applicationId: $('e-appid').value.trim() || null
    };
    if (!payload.subjectName) { toast('Enter the exam / subject name.', true); $('e-subject').focus(); return; }
    if (!payload.examDate) { toast('Pick the exam date.', true); $('e-date').focus(); return; }
    if (semesters.some(n => !Number.isInteger(n))) { toast('Semesters must be whole numbers between 1 and 8.', true); return; }
    if (!payload.applicationId) {
        const tag = ((payload.course || payload.branch || 'EX').replace(/[^A-Za-z0-9]/g, '') || 'EX').toUpperCase();
        payload.applicationId = 'APP-' + tag + '-' + payload.examDate.replace(/-/g, '') + '-' + String(exams.length + 1).padStart(3, '0');
    }
    try {
        await api('/api/exams', jsonOpts('POST', payload));
        ['e-subject', 'e-branch', 'e-date', 'e-time', 'e-appid'].forEach(id => $(id).value = '');
        if ($('e-endtime')) $('e-endtime').value = '';
        if ($('e-semesters')) [...$('e-semesters').options].forEach(o => { o.selected = false; });
        $('e-subject').focus();
        toast('Exam created (draft): ' + payload.applicationId +
            (semesters.length ? ' · Semester ' + semesters.join(', ') : '') + ' - publish it when it is final.');
        await loadExams();
    } catch (e) { toast(e.message, true); }
}

/* ---------- allocation publishing ---------- */

async function refreshAllocStatus() {
    if (!$('alloc-status')) return;
    if (!selectedExamId) { $('alloc-status').textContent = ''; return; }
    try {
        const rows = await api('/api/allocations?examId=' + selectedExamId);
        const published = rows.filter(r => r.status === 'PUBLISHED').length;
        $('alloc-status').textContent = rows.length
            ? rows.length + ' seat allocation(s): ' + published + ' published, ' + (rows.length - published) + ' draft. ' +
              'Students only see published seats.'
            : 'No seating generated yet for this exam.';
        $('btn-publish').disabled = !rows.length || published === rows.length;
        $('btn-unpublish').disabled = !published;
    } catch (e) { /* non-critical */ }
}

async function publishAllocations() {
    if (!selectedExamId) return;
    try {
        const res = await api('/api/allocations/' + selectedExamId + '/publish', jsonOpts('POST', {}));
        toast(res.message || 'Seating published.');
        await refreshAllocStatus();
    } catch (e) { toast(e.message, true); }
}

async function unpublishAllocations() {
    if (!selectedExamId) return;
    try {
        const res = await api('/api/allocations/' + selectedExamId + '/unpublish', jsonOpts('POST', {}));
        toast(res.unpublished + ' allocation(s) moved back to draft.');
        await refreshAllocStatus();
    } catch (e) { toast(e.message, true); }
}

window.publishAllocations = publishAllocations;
window.unpublishAllocations = unpublishAllocations;
window.logout = logout;
window.renderMyExams = function () { renderMyExams(); };

/* ==================== 4. Student dashboard ==================== */

let studentExams = [];
const todayISO = () => new Date().toISOString().slice(0, 10);

const stuLoaders = {
    overview: loadStudentOverview,
    exams: loadMyExams,
    seat: loadSeatSection,
    profile: loadProfile
};

function showStudentSection(name) {
    document.querySelectorAll('#student-app .stu-view').forEach(el => el.classList.remove('active-view'));
    const section = $(name === 'overview' ? 'student-overview-section' : 'student-' + name + '-section');
    if (section) section.classList.add('active-view');
    document.querySelectorAll('#student-app .tab[data-stu]').forEach(b => {
        const active = b.dataset.stu === name;
        b.classList.toggle('active', active);
        b.setAttribute('aria-current', active ? 'page' : 'false');
    });
    try { stuLoaders[name](); } catch (e) { /* noop */ }
}
window.showStudentSection = showStudentSection;

async function bootStudent(me) {
    wireNavToggle('student-nav-toggle', 'student-tabs');
    $('student-nav-sub').textContent = (me.student && me.student.name ? me.student.name : me.username) +
        ' · ' + (me.student ? me.student.rollNo : 'Student');
    $('pw-banner').style.display = me.mustChangePassword ? 'block' : 'none';
    try {
        studentExams = await api('/api/student/exams');
    } catch (e) { studentExams = []; }
    // Push notification clicks land on /index.html#exams|#seat|... - honour the hash.
    const wanted = (location.hash || '').replace('#', '');
    showStudentSection(['overview', 'exams', 'seat', 'profile'].indexOf(wanted) >= 0 ? wanted : 'overview');
    await refreshNotifications();
    requestPushPermissionOnStudentEntry();
    startNotificationStream();
}

function statusBadge(status) {
    const s = EXAM_STATUS[status] || EXAM_STATUS.DRAFT;
    return '<span class="badge" style="background:' + s.color + '">' + s.label + '</span>';
}

/** Human-readable semester audience of an exam JSON entry ("Sem 1, 3" or ''). */
function semLabel(x) {
    if (x.semesters && x.semesters.length) return 'Sem ' + x.semesters.join(', ');
    return x.semester ? 'Sem ' + x.semester : '';
}

function fmtDateLong(d) {
    if (!d) return '—';
    try {
        return new Date(d + 'T00:00:00').toLocaleDateString(undefined,
            { weekday: 'short', day: 'numeric', month: 'long', year: 'numeric' });
    } catch (e) { return d; }
}

function reportTime(startTime) {
    if (!startTime) return '—';
    try {
        const [h, m] = String(startTime).slice(0, 5).split(':').map(Number);
        const d = new Date(2000, 0, 1, h, m - 15);
        return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
    } catch (e) { return String(startTime).slice(0, 5); }
}

async function loadStudentOverview() {
    try {
        const s = await api('/api/student/dashboard');
        $('stu-greeting').textContent = 'Welcome, ' + (s.name || s.rollNo) + ' (' + s.rollNo + ')' +
            (s.semester ? ' · Semester ' + s.semester : '');
        $('stu-upcoming').textContent = s.upcomingExams;
        $('stu-completed').textContent = s.completedExams;
        $('stu-batch').textContent = s.batch || '—';

        const next = s.nextExam;
        $('stu-next-exam').innerHTML = next
            ? '<div class="exam-card"><h4>' + esc(next.subjectName || next.examName) +
                  (next.subjectCode ? ' <span class="muted">(' + esc(next.subjectCode) + ')</span>' : '') + '</h4>' +
                  '<div class="meta">' + fmtDateLong(next.examDate) + ' · ' + fmtTime(next.startTime) +
                  (next.endTime ? '–' + fmtTime(next.endTime) : '') + '</div>' +
                  (s.latestSeat
                      ? '<div class="meta">Seat: <b>' + esc(s.latestSeat.roomName) + '</b> · <b>' + esc(s.latestSeat.seatCode) + '</b></div>'
                      : '<div class="meta muted">Your seat has not been allocated yet. Please check again later.</div>') +
                  '</div>'
            : '<p class="hint">No examinations are currently scheduled for your batch or semester.</p>';

        const anns = s.announcements || [];
        $('stu-announcements').innerHTML = anns.length
            ? anns.map(a => '<div class="ann-item"><b>' + esc(a.title) + '</b>' +
                  '<div class="hint" style="margin:0">' + esc(a.body || '') + '</div></div>').join('')
            : '<p class="hint">No announcements.</p>';
    } catch (e) { toast(e.message, true); }
}

function renderMyExams() {
    const box = $('stu-exam-list');
    const filter = $('stu-exam-filter') ? $('stu-exam-filter').value : 'upcoming';
    const today = todayISO();
    const list = studentExams.filter(x => {
        const status = x.status || 'DRAFT';
        if (filter === 'cancelled') return status === 'CANCELLED';
        if (filter === 'completed') return status !== 'CANCELLED' && x.examDate && x.examDate < today;
        if (filter === 'upcoming') return status !== 'CANCELLED' && x.examDate && x.examDate >= today;
        return true;   // 'all' includes completed and cancelled exams too
    });
    if (!list.length) {
        box.innerHTML = '<p class="hint">No examinations are currently scheduled for your batch or semester.</p>';
        return;
    }
    box.innerHTML = list.map(x =>
        '<div class="exam-card"><h4>' + esc(x.subjectName || x.examName) +
            (x.subjectCode ? ' <span class="muted">(' + esc(x.subjectCode) + ')</span>' : '') + ' ' + statusBadge(x.status) + '</h4>' +
            '<div class="meta">' + fmtDateLong(x.examDate) + ' · ' + fmtTime(x.startTime) +
            (x.endTime ? '–' + fmtTime(x.endTime) : '') +
            (x.durationMinutes ? ' · ' + x.durationMinutes + ' min' : '') + '</div>' +
            '<div class="meta">' + esc([x.branch, x.course, semLabel(x)].filter(Boolean).join(' · ') || 'All batches & semesters') + '</div>' +
            (x.seat
                ? '<div class="meta">Room <b>' + esc(x.seat.roomName) + '</b> · Seat <b>' + esc(x.seat.seatCode) + '</b></div>'
                : (x.status === 'PUBLISHED'
                    ? '<div class="meta muted">Your seat has not been allocated yet. Please check again later.</div>'
                    : '')) +
            (x.instructions ? '<div class="hint" style="margin:.3rem 0 0">Instructions: ' + esc(x.instructions) + '</div>' : '') +
            '</div>').join('');
}

async function loadMyExams() {
    try {
        studentExams = await api('/api/student/exams');
    } catch (e) { toast(e.message, true); }
    renderMyExams();
}

async function loadSeatSection() {
    const select = $('stu-seat-exam');
    const previous = select.value;
    select.innerHTML = studentExams.length
        ? '<option value="">Latest published exam</option>' +
          studentExams.map(x => '<option value="' + x.id + '">' + esc(x.subjectName || x.examName) +
              (x.examDate ? ' — ' + x.examDate : '') + '</option>').join('')
        : '<option value="">No examinations scheduled</option>';
    if (previous) select.value = previous;
    await loadMySeat();
}

async function loadMySeat() {
    const box = $('stu-seat-card');
    const examId = $('stu-seat-exam').value;
    if (!studentExams.length && !examId) {
        box.innerHTML = '<p class="hint">No examinations are currently scheduled for your batch or semester.</p>';
        return;
    }
    box.innerHTML = '<p class="hint">Loading…</p>';
    try {
        const data = await api('/api/student/seat' + (examId ? '?examId=' + examId : ''));
        if (!data.seat) {
            box.innerHTML = '<p class="hint">' + esc(data.message || 'Your seat has not been allocated yet. Please check again later.') + '</p>' +
                (data.exam ? '<div class="exam-card"><h4>' + esc(data.exam.subjectName) + ' ' + statusBadge(data.exam.status) + '</h4>' +
                    '<div class="meta">' + fmtDateLong(data.exam.examDate) + ' · ' + fmtTime(data.exam.startTime) + '</div></div>' : '');
            return;
        }
        const e = data.exam, s = data.seat;
        box.innerHTML = '<div class="slip">' +
            '<h3>' + esc(e.subjectName || 'Examination') + '</h3>' +
            '<div class="seat-big">' + esc(s.seatCode) + '</div>' +
            '<dl>' +
            '<dt>Candidate</dt><dd>' + esc((session.student && session.student.name) || '') + ' — ' + esc(s.rollNo || (session.student && session.student.rollNo) || '') + '</dd>' +
            (s.subjectCode || e.subjectCode ? '<dt>Subject code</dt><dd>' + esc(s.subjectCode || e.subjectCode) + '</dd>' : '') +
            '<dt>Date</dt><dd>' + fmtDateLong(e.examDate) + '</dd>' +
            '<dt>Reporting time</dt><dd>' + reportTime(e.startTime) + '</dd>' +
            '<dt>Exam time</dt><dd>' + fmtTime(e.startTime) + (e.endTime ? ' – ' + fmtTime(e.endTime) : '') + '</dd>' +
            '<dt>Venue</dt><dd>' + esc(s.roomName || s.roomNumber || '—') + '</dd>' +
            '<dt>Building / Floor</dt><dd>' + esc([s.building, s.floor != null ? 'Floor ' + s.floor : null].filter(Boolean).join(', ') || '—') + '</dd>' +
            '<dt>Seat</dt><dd>' + esc(s.seatCode) + ' (row ' + esc(s.row) + ', column ' + esc(s.column) + ')</dd>' +
            '</dl>' +
            (e.instructions ? '<p class="hint" style="margin-top:.8rem"><b>Instructions:</b> ' + esc(e.instructions) + '</p>' : '') +
            '</div>';
    } catch (err) {
        box.innerHTML = '<p class="hint">' + esc(err.message) + '</p>';
    }
}
window.loadMySeat = loadMySeat;

async function loadProfile() {
    try {
        const me = await api('/api/student/me');
        $('stu-profile-details').innerHTML = '<dl class="slip" style="background:#fff;border-style:solid;max-width:100%">' +
            '<dt>Roll number</dt><dd>' + esc(me.rollNo) + '</dd>' +
            '<dt>Name</dt><dd>' + esc(me.name) + '</dd>' +
            '<dt>Department</dt><dd>' + esc(me.department || me.branch || '—') + '</dd>' +
            '<dt>Branch</dt><dd>' + esc(me.branch || '—') + '</dd>' +
            '<dt>Batch</dt><dd>' + esc(me.batch || '—') + '</dd>' +
            '<dt>Semester</dt><dd>' + (me.semester == null ? '—' : 'Semester ' + esc(me.semester)) + '</dd>' +
            '<dt>Account status</dt><dd>' + esc(me.accountStatus || 'ACTIVE') + '</dd>' +
            '</dl>';
        $('p-email').value = me.email || '';
        $('p-phone').value = me.phone || '';
    } catch (e) { toast(e.message, true); }
}

async function saveProfile() {
    try {
        const res = await api('/api/student/profile', jsonOpts('PUT', {
            email: $('p-email').value.trim(),
            phone: $('p-phone').value.trim()
        }));
        toast(res.message || 'Profile updated.');
    } catch (e) { toast(e.message, true); }
}
window.saveProfile = saveProfile;

async function changeStudentPassword() {
    const current = $('p-current').value, next = $('p-new').value, confirmPwd = $('p-confirm').value;
    if (!current || !next) { setMessage('p-msg', 'Please fill in all three password fields.', true); return; }
    if (next !== confirmPwd) { setMessage('p-msg', 'The new passwords do not match.', true); return; }
    if (next.length < 6) { setMessage('p-msg', 'The new password must be at least 6 characters long.', true); return; }
    try {
        const res = await api('/api/student/password', jsonOpts('POST', { currentPassword: current, newPassword: next }));
        setMessage('p-msg', res.message || 'Your password has been updated.', false);
        $('p-current').value = ''; $('p-new').value = ''; $('p-confirm').value = '';
        session = await fetchMe();
        $('pw-banner').style.display = (session && session.mustChangePassword) ? 'block' : 'none';
        toast('Password changed.');
    } catch (e) { setMessage('p-msg', e.message, true); }
}
window.changeStudentPassword = changeStudentPassword;

/* ==================== 5. Calendar / Reports / Announcements / Settings ==================== */

/* --- mobile navigation drawer: the hamburger morphs to an X and toggles the tab strip --- */
function wireNavToggle(buttonId, tabsId) {
    const btn = $(buttonId), tabs = $(tabsId);
    if (!btn || !tabs) return;
    const mobile = () => window.matchMedia('(max-width: 760px)').matches;
    const setOpen = open => {
        btn.setAttribute('aria-pressed', String(open));
        btn.setAttribute('aria-expanded', String(open));
        tabs.classList.toggle('is-collapsed', !open);
        tabs.classList.toggle('is-open', open);
    };
    if (mobile()) setOpen(false);
    btn.addEventListener('click', () => setOpen(btn.getAttribute('aria-pressed') !== 'true'));
    tabs.addEventListener('click', e => { if (e.target.closest('.tab') && mobile()) setOpen(false); });
}

/* --- shared calendar rendering (admin Calendar tab + student Calendar view) --- */

function calIso(d) {
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

function buildMonthCells(list, cursor) {
    const DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    let html = DOW.map(d => '<div class="cal-dow">' + d + '</div>').join('');
    const first = new Date(cursor.getFullYear(), cursor.getMonth(), 1);
    const lead = (first.getDay() + 6) % 7;
    const days = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 0).getDate();
    const today = calIso(new Date());
    for (let i = 0; i < lead; i++) html += '<div class="cal-day other"></div>';
    for (let d = 1; d <= days; d++) {
        const iso = calIso(new Date(cursor.getFullYear(), cursor.getMonth(), d));
        const todays = list.filter(x => x.examDate === iso && (x.status || 'DRAFT') !== 'CANCELLED');
        html += '<div class="cal-day' + (iso === today ? ' today' : '') + '">' +
            '<div class="cal-daynum">' + d + '</div>' +
            todays.slice(0, 3).map(x =>
                '<button type="button" class="cal-chip" data-exam="' + x.id + '" title="' + esc(x.subjectName || x.examName || '') + '">' +
                esc(String(x.examTime || x.startTime || '').slice(0, 5)) + ' ' + esc(x.subjectName || x.examName || '') +
                '</button>').join('') +
            (todays.length > 3 ? '<div class="hint" style="margin:0">+' + (todays.length - 3) + ' more</div>' : '') +
            '</div>';
    }
    return html;
}

/* --- admin Calendar & Timetable tab --- */

const cal = { cursor: new Date(), exams: [] };

function calMove(step) {
    const view = $('cal-view') ? $('cal-view').value : 'month';
    if (view === 'week') cal.cursor = new Date(cal.cursor.getTime() + step * 7 * 864e5);
    else if (view !== 'list') cal.cursor = new Date(cal.cursor.getFullYear(), cal.cursor.getMonth() + step, 1);
    calRender();
}
function calToday() { cal.cursor = new Date(); calRender(); }

async function loadCalendar() {
    try { cal.exams = await api('/api/exams'); } catch (e) { cal.exams = []; }
    calRender();
}

function calRender() {
    const grid = $('cal-grid'), agenda = $('cal-agenda'), title = $('cal-title');
    if (!grid) return;
    const view = $('cal-view') ? $('cal-view').value : 'month';
    const cur = cal.cursor;
    const today = calIso(new Date());
    if (view === 'list') {
        grid.style.display = 'none';
        if (title) title.textContent = 'All scheduled examinations';
        renderCalAgenda(agenda, cal.exams.slice().sort((a, b) => (a.examDate || '').localeCompare(b.examDate || '')));
        return;
    }
    grid.style.display = 'grid';
    if (view === 'week') {
        const dow = (cur.getDay() + 6) % 7;
        const start = new Date(cur.getFullYear(), cur.getMonth(), cur.getDate() - dow);
        if (title) title.textContent = 'Week of ' + start.toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' });
        const DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
        let html = DOW.map(d => '<div class="cal-dow">' + d + '</div>').join('');
        for (let i = 0; i < 7; i++) {
            const date = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i);
            const iso = calIso(date);
            const todays = cal.exams.filter(x => x.examDate === iso && (x.status || 'DRAFT') !== 'CANCELLED');
            html += '<div class="cal-day' + (iso === today ? ' today' : '') + '">' +
                '<div class="cal-daynum">' + date.getDate() + '</div>' +
                todays.slice(0, 4).map(x =>
                    '<button type="button" class="cal-chip" data-exam="' + x.id + '" title="' + esc(x.subjectName || '') + '">' +
                    esc(String(x.examTime || x.startTime || '').slice(0, 5)) + ' ' + esc(x.subjectName || '') + '</button>').join('') +
                '</div>';
        }
        grid.innerHTML = html;
    } else {
        if (title) title.textContent = cur.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
        grid.innerHTML = buildMonthCells(cal.exams, cur);
    }
    renderCalAgenda(agenda, cal.exams.filter(x => x.examDate && x.examDate >= today)
        .sort((a, b) => (a.examDate || '').localeCompare(b.examDate || '')));
}

function renderCalAgenda(box, list) {
    if (!box) return;
    box.innerHTML = list.length
        ? list.map(x =>
            '<div class="cal-agenda-item">' +
            '<span class="cal-when">' + esc(fmtDate(x.examDate)) + ' ' + esc(fmtTime(x.examTime || x.startTime)) + '</span>' +
            '<span><b>' + esc(x.subjectName || x.examName) + '</b> ' + statusBadge(x.status) +
            ' · ' + esc([x.branch, x.course, semLabel(x)].filter(Boolean).join(' · ') || 'All batches & semesters') + '</span>' +
            '<button type="button" class="btn btn-sm btn-ghost" data-exam="' + x.id + '" style="margin-left:auto">Details</button>' +
            '</div>').join('')
        : '<p class="hint">No examinations scheduled.</p>';
}

async function calPick(examId) {
    const box = $('cal-detail');
    const x = cal.exams.find(e => e.id === examId);
    if (!box || !x) return;
    box.style.display = 'block';
    box.className = 'message-box';
    box.innerHTML = calDetailHtml(x, null);
    try {
        const rows = await api('/api/allocations?examId=' + x.id);
        box.innerHTML = calDetailHtml(x, rows);
    } catch (e) { /* keep the short form */ }
}

function calDetailHtml(x, rows) {
    const head = '<b>' + esc(x.subjectName || x.examName) + '</b> — ' + fmtDateLong(x.examDate) +
        ' · ' + fmtTime(x.examTime || x.startTime) + (x.endTime ? ' – ' + fmtTime(x.endTime) : '') +
        ' · ' + statusBadge(x.status) + '<div class="hint" style="margin:.3rem 0 0">Audience: ' +
        esc([x.branch, x.course, semLabel(x)].filter(Boolean).join(' · ') || 'All batches & semesters') +
        (x.applicationId ? ' · ' + esc(x.applicationId) : '') + '<br>';
    if (!rows) return head + 'Checking room allocation…</div>';
    if (!rows.length) return head + 'No seating chart has been generated for this exam yet.</div>';
    const published = rows.filter(r => r.status === 'PUBLISHED').length;
    const roomNos = [...new Set(rows.map(r => r.roomNumber))];
    return head + 'Seating chart: ' + rows.length + ' seat(s) in room(s) ' + esc(roomNos.join(', ')) + ' — ' +
        (published === rows.length
            ? 'published, visible to students.'
            : published + ' published, ' + (rows.length - published) + ' draft (hidden from students).') + '</div>';
}

window.calMove = calMove;
window.calToday = calToday;
window.calRender = calRender;

/* --- student calendar view (My Exams tab) --- */

let stuCalOpen = false;

function toggleStudentCalendar() {
    stuCalOpen = !stuCalOpen;
    $('stu-calendar').style.display = stuCalOpen ? 'block' : 'none';
    const b = $('stu-cal-toggle');
    b.setAttribute('aria-pressed', String(stuCalOpen));
    b.textContent = stuCalOpen ? 'Hide calendar' : 'Calendar view';
    if (stuCalOpen) renderStudentCalendar();
}

function renderStudentCalendar() {
    const grid = $('stu-cal-grid');
    if (!grid) return;
    grid.innerHTML = buildMonthCells(studentExams, new Date());
    const today = todayISO();
    const upcoming = studentExams
        .filter(x => x.examDate && x.examDate >= today && (x.status || 'DRAFT') !== 'CANCELLED')
        .sort((a, b) => (a.examDate || '').localeCompare(b.examDate || ''));
    $('stu-cal-agenda').innerHTML = upcoming.length
        ? upcoming.map(x =>
            '<div class="cal-agenda-item">' +
            '<span class="cal-when">' + esc(fmtDate(x.examDate)) + ' ' + esc(fmtTime(x.startTime)) + '</span>' +
            '<span><b>' + esc(x.subjectName || x.examName) + '</b> ' + statusBadge(x.status) +
            (x.seat ? ' · Seat ' + esc(x.seat.seatCode) : '') + '</span>' +
            '<button type="button" class="btn btn-sm btn-ghost" data-exam="' + x.id + '" style="margin-left:auto">Details</button>' +
            '</div>').join('')
        : '<p class="hint">No upcoming examinations.</p>';
}

async function stuCalPick(id) {
    const box = $('stu-cal-detail');
    const x = studentExams.find(e => e.id === id);
    if (!box || !x) return;
    box.style.display = 'block';
    box.className = 'message-box';
    box.innerHTML = '<b>' + esc(x.subjectName || x.examName) + '</b> — ' + fmtDateLong(x.examDate) +
        ' · ' + fmtTime(x.startTime) + (x.endTime ? ' – ' + fmtTime(x.endTime) : '') + ' · ' + statusBadge(x.status) +
        (x.seat
            ? '<div class="hint" style="margin:.3rem 0 0">Venue ' + esc(x.seat.roomName) + ' · Seat <b>' + esc(x.seat.seatCode) + '</b></div>'
            : '<div class="hint" style="margin:.3rem 0 0">Your seat has not been allocated for this exam yet. Please check again later.</div>');
}

window.toggleStudentCalendar = toggleStudentCalendar;

/* --- calendar event clicks (admin chips/agenda + student calendar) --- */
document.addEventListener('click', e => {
    const el = e.target.closest('button[data-exam]');
    if (!el) return;
    const id = Number(el.dataset.exam);
    if (!Number.isFinite(id)) return;
    if (el.closest('#stu-calendar')) stuCalPick(id); else calPick(id);
});

/* --- Reports tab --- */

async function loadReports() {
    const tbody = $('report-list');
    if (!tbody) return;
    try {
        const [exs, sts] = await Promise.all([api('/api/exams'), api('/api/students')]);
        exams = exs; students = sts;
        const rows = await Promise.all(exs.map(x =>
            api('/api/allocations?examId=' + x.id).then(a => ({ x, a })).catch(() => ({ x, a: [] }))));
        let seatedTotal = 0, unseatedTotal = 0, publishedExams = 0;
        tbody.innerHTML = rows.length ? rows.map(({ x, a }) => {
            const eligible = students.filter(s => matchesExam(s, x)).length;
            const seated = a.length;
            const published = seated > 0 && a.every(r => r.status === 'PUBLISHED');
            const unseated = Math.max(0, eligible - seated);
            seatedTotal += seated; unseatedTotal += unseated;
            if (published) publishedExams++;
            const roomNos = [...new Set(a.map(r => r.roomNumber))];
            return '<tr>' +
                '<td><b>' + esc(x.subjectName) + '</b><div class="hint" style="margin:0">' + esc(x.applicationId || '') + '</div></td>' +
                '<td>' + fmtDate(x.examDate) + '</td>' +
                '<td>' + fmtTime(x.examTime || x.startTime) + (x.endTime ? '–' + fmtTime(x.endTime) : '') + '</td>' +
                '<td>' + esc([x.branch, x.course, semLabel(x)].filter(Boolean).join(' · ') || 'All batches & semesters') + '</td>' +
                '<td>' + examStatusBadge(x.status) + '</td>' +
                '<td>' + eligible + '</td>' +
                '<td>' + seated + '</td>' +
                '<td>' + (unseated ? '<b style="color:#B91C1C">' + unseated + '</b>' : '0') + '</td>' +
                '<td>' + (seated
                    ? (published ? '<span class="badge" style="background:#10B981">PUBLISHED</span>'
                                 : '<span class="badge" style="background:#6B7280">DRAFT</span>') +
                      (roomNos.length ? ' <span class="hint" style="margin:0">' + esc(roomNos.join(', ')) + '</span>' : '')
                    : '<span class="muted">no chart</span>') + '</td>' +
                '</tr>';
        }).join('') : '<tr><td colspan="9" class="empty">No exams yet. Create one in the Exams tab.</td></tr>';
        const totals = $('report-totals');
        totals.style.display = 'flex';
        totals.innerHTML = '<div class="count"><b>' + exs.length + '</b> exams</div>' +
            '<div class="count"><b>' + students.length + '</b> students</div>' +
            '<div class="count"><b>' + seatedTotal + '</b> seated allocations</div>' +
            '<div class="count"><b>' + unseatedTotal + '</b> unseated (per-exam)</div>' +
            '<div class="count"><b>' + publishedExams + '</b> published exams</div>';
    } catch (e) { toast(e.message, true); }
}
window.loadReports = loadReports;

/* --- Announcements tab --- */

async function loadAnnouncements() {
    const box = $('ann-list');
    if (!box) return;
    try {
        const list = await api('/api/admin/announcements');
        const sel = $('a-batch');
        if (sel) {
            const prev = sel.value;
            sel.innerHTML = '<option value="">All students</option>' +
                batches.map(b => '<option value="' + esc(b.name) + '">' + esc(b.name) + '</option>').join('');
            if (prev) sel.value = prev;
        }
        box.innerHTML = list.length
            ? list.map(a =>
                '<div class="ann-admin-item">' +
                '<div><b>' + esc(a.title) + '</b>' +
                (a.batchName ? ' <span class="badge" style="background:#4F46E5">' + esc(a.batchName) + '</span>' : '') +
                (a.semester ? ' <span class="badge" style="background:#0EA5E9">Sem ' + esc(a.semester) + '</span>' : '') +
                (a.rollNos ? ' <span class="badge" style="background:#F59E0B">' + esc(shortRolls(a.rollNos)) + '</span>' : '') +
                (!a.batchName && !a.semester && !a.rollNos ? ' <span class="muted">all students</span>' : '') +
                '<div class="hint" style="margin:0">' + esc(a.body || '') + '</div>' +
                '<div class="hint" style="margin:0">' + esc(String(a.createdAt || '').replace('T', ' ').slice(0, 16)) + '</div></div>' +
                '<button type="button" class="btn btn-sm btn-danger" data-del-ann="' + a.id + '">Delete</button>' +
                '</div>').join('')
            : '<p class="hint">No announcements yet.</p>';
    } catch (e) { toast(e.message, true); }
}

/** "3 roll numbers" style label for a comma-separated list. */
function shortRolls(raw) {
    const parts = String(raw).split(',').map(s => s.trim()).filter(Boolean);
    return parts.length > 2 ? parts.length + ' students' : parts.join(', ');
}

async function addAnnouncement() {
    const title = $('a-title').value.trim();
    const body = $('a-body').value.trim();
    const batchName = $('a-batch').value.trim();
    const semesterRaw = $('a-semester') ? $('a-semester').value.trim() : '';
    const rollNos = $('a-rolls') ? $('a-rolls').value.trim() : '';
    if (!title) { toast('Enter an announcement title.', true); $('a-title').focus(); return; }
    if (semesterRaw && !/^[1-8]$/.test(semesterRaw)) { toast('Semester must be 1-8.', true); return; }
    if (rollNos && !/^[\w\-]+(\s*,\s*[\w\-]+)*$/.test(rollNos)) {
        toast('Roll numbers must be a comma-separated list (e.g. 101, 102).', true); return;
    }
    try {
        const res = await api('/api/admin/announcements', jsonOpts('POST', {
            title,
            body: body || null,
            batchName: batchName || null,
            semester: semesterRaw ? Number(semesterRaw) : null,
            rollNos: rollNos || null
        }));
        $('a-title').value = ''; $('a-body').value = '';
        if ($('a-rolls')) $('a-rolls').value = '';
        const n = (res && res.notifications) || {};
        const audience = [batchName && 'batch ' + batchName,
                          semesterRaw && 'semester ' + semesterRaw,
                          rollNos && 'roll nos'].filter(Boolean).join(' + ') || 'all students';
        toast('Announcement posted to ' + audience + ' — '
            + ((res && res.recipients) || 0) + ' recipient(s), '
            + (n.created || 0) + ' notification(s)'
            + (n.duplicatesSkipped ? ', ' + n.duplicatesSkipped + ' duplicate(s) skipped' : '')
            + (n.pushEnabled ? ', ' + (n.pushed || 0) + ' pushed' : '') + '.');
        await loadAnnouncements();
    } catch (e) { toast(e.message, true); }
}
window.addAnnouncement = addAnnouncement;

/* --- Settings tab: administrator password change --- */

async function changeAdminPassword() {
    const cur = $('set-current').value, next = $('set-new').value, confirmPwd = $('set-confirm').value;
    if (!cur || !next) { setMessage('set-msg', 'Please fill in all three password fields.', true); return; }
    if (next !== confirmPwd) { setMessage('set-msg', 'The new passwords do not match.', true); return; }
    if (next.length < 6) { setMessage('set-msg', 'The new password must be at least 6 characters long.', true); return; }
    try {
        const res = await api('/api/auth/change-password', jsonOpts('POST', { currentPassword: cur, newPassword: next }));
        setMessage('set-msg', res.message || 'Your password has been updated.', false);
        $('set-current').value = ''; $('set-new').value = ''; $('set-confirm').value = '';
        toast('Administrator password changed.');
    } catch (e) { setMessage('set-msg', e.message, true); }
}
window.changeAdminPassword = changeAdminPassword;

/* ==================== 6. Notifications (bell, inbox, web push) ==================== */

let pushStatusCache = { pushEnabled: false, vapidKey: '', devices: [] };

let pushPromptedOnStudentEntry = false;
let notificationUnreadCount = 0;
const liveNotificationIds = new Set();
let notificationStream = null;

/** Opens/closes the notification dropdown and refreshes it when opening. */
function toggleNotifPanel() {
    const panel = $('stu-notif-panel'), btn = $('stu-notif-btn');
    const opening = panel.hidden;
    panel.hidden = !opening;
    btn.setAttribute('aria-expanded', String(opening));
    if (opening) refreshNotifications();
}
window.toggleNotifPanel = toggleNotifPanel;

/* Close the panel when clicking elsewhere (delegated, one listener). */
document.addEventListener('click', e => {
    const panel = $('stu-notif-panel');
    if (!panel || panel.hidden) return;
    if (e.target.closest('.bell-wrap')) return;
    panel.hidden = true;
    const btn = $('stu-notif-btn');
    if (btn) btn.setAttribute('aria-expanded', 'false');
});

/** Loads the inbox + unread badge. Silently keeps the old state on errors. */
async function refreshNotifications() {
    try {
        const data = await api('/api/student/notifications');
        const notifications = data.notifications || [];
        renderNotifList(notifications);
        renderBadge(data.unreadCount || 0);
    } catch (e) { /* student page can be open while logged out - ignore */ }
    try {
        pushStatusCache = await api('/api/student/push/status');
        renderPushStatus();
    } catch (e) { /* non-fatal */ }
}
window.refreshNotifications = refreshNotifications;

function showLiveNotification(notification) {
    const id = Number(notification.id) || 0;
    if (id && liveNotificationIds.has(id)) return;
    if (id) {
        liveNotificationIds.add(id);
        if (liveNotificationIds.size > 200) {
            liveNotificationIds.delete(liveNotificationIds.values().next().value);
        }
    }
    const title = notification.title || 'New notification';
    toast(title + (notification.body ? ' — ' + notification.body : ''));
    if (notification.read !== true) renderBadge(notificationUnreadCount + 1);
    const bell = $('stu-notif-btn');
    if (bell) {
        bell.classList.remove('has-new-notification');
        void bell.offsetWidth;
        bell.classList.add('has-new-notification');
        window.setTimeout(() => bell.classList.remove('has-new-notification'), 900);
    }
    if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
        showDeviceNotification(notification, title, id);
    }
    refreshNotifications();
}

function showDeviceNotification(notification, title, id) {
    const options = {
        body: notification.body || '',
        tag: id ? 'exam-notification-' + id : undefined,
        data: { id: id || null, link: notification.link || 'overview' }
    };
    const showInPage = () => {
        try {
            const browserNotification = new Notification(title, options);
            browserNotification.onclick = () => {
                window.focus();
                openNotification(id, !!notification.read, notification.link);
                browserNotification.close();
            };
        } catch (error) {
            console.warn('Could not display the browser notification.', error);
        }
    };

    if (!('serviceWorker' in navigator)) {
        showInPage();
        return;
    }
    navigator.serviceWorker.getRegistration('/').then(registration => {
        if (registration && registration.showNotification) {
            return registration.showNotification(title, options);
        }
        showInPage();
    }).catch(error => {
        console.warn('Could not use the notification service worker.', error);
        showInPage();
    });
}

function startNotificationStream() {
    if (typeof EventSource === 'undefined' || !session || session.role !== 'STUDENT') return;
    if (notificationStream) notificationStream.close();
    notificationStream = new EventSource('/api/student/notifications/stream');
    notificationStream.onopen = () => refreshNotifications();
    notificationStream.addEventListener('notification', event => {
        try {
            showLiveNotification(JSON.parse(event.data));
        } catch (e) {
            console.error('Could not read live notification event.', e);
        }
    });
}

async function requestPushPermissionOnStudentEntry() {
    if (pushPromptedOnStudentEntry || typeof Notification === 'undefined'
            || Notification.permission === 'denied'
            || (pushStatusCache.devices || []).length > 0) {
        return;
    }
    pushPromptedOnStudentEntry = true;
    await enablePushNotifications();
}

function renderBadge(count) {
    const badge = $('stu-notif-badge');
    if (!badge) return;
    notificationUnreadCount = Math.max(0, Number(count) || 0);
    badge.hidden = !notificationUnreadCount;
    badge.textContent = notificationUnreadCount > 99 ? '99+' : String(notificationUnreadCount);
    const button = $('stu-notif-btn');
    if (button) {
        button.setAttribute('aria-label', notificationUnreadCount
            ? 'Notifications, ' + notificationUnreadCount + ' unread'
            : 'Notifications');
    }
}

function renderNotifList(items) {
    const box = $('stu-notif-list');
    if (!box) return;
    if (!items.length) {
        box.innerHTML = '<p class="hint" style="padding:.6rem">No notifications yet. '
            + 'Exam publications, seat changes and announcements appear here.</p>';
        return;
    }
    box.innerHTML = items.map(n =>
        '<button type="button" class="notif-item' + (n.read ? '' : ' unread') + '"'
        + ' data-notif-id="' + Number(n.id) + '"'
        + ' data-notif-read="' + (n.read ? '1' : '0') + '"'
        + ' data-notif-link="' + esc(String(n.link || 'overview').replace(/[^a-z]/gi, '')) + '">'
        + '<span class="t">' + (n.read ? '' : '<span class="notif-dot" aria-label="Unread"></span>')
        + esc(n.title || 'Notification') + '</span>'
        + (n.body ? '<span class="b">' + esc(n.body) + '</span>' : '')
        + '<span class="w">' + fmtWhen(n.createdAt) + ' · ' + esc((n.type || '').replace(/_/g, ' ').toLowerCase()) + '</span>'
        + '</button>').join('');
}

/* Delegated: one listener for all inbox items (handles dynamic re-renders). */
document.addEventListener('click', e => {
    const item = e.target.closest('.notif-item[data-notif-id]');
    if (!item) return;
    openNotification(Number(item.dataset.notifId), item.dataset.notifRead === '1', item.dataset.notifLink);
});

/** Marks one notification read and jumps to the related section. */
async function openNotification(id, alreadyRead, link, togglePanel = true) {
    try {
        if (!alreadyRead) await api('/api/student/notifications/' + id + '/read', jsonOpts('POST', {}));
    } catch (e) { /* still navigate */ }
    const section = ['exams', 'seat', 'overview', 'profile'].indexOf(link) >= 0 ? link : 'overview';
    if (togglePanel) toggleNotifPanel();
    showStudentSection(section);
    refreshNotifications();
}
window.openNotification = openNotification;

if ('serviceWorker' in navigator) {
    navigator.serviceWorker.addEventListener('message', event => {
        const data = event.data || {};
        if (data.type !== 'notification-click' || !session || session.role !== 'STUDENT') return;
        openNotification(Number(data.id) || 0, false, data.link, false);
    });
}

async function markAllRead() {
    try {
        await api('/api/student/notifications/read-all', jsonOpts('POST', {}));
        refreshNotifications();
    } catch (e) { toast(e.message, true); }
}
window.markAllRead = markAllRead;

function renderPushStatus() {
    const status = $('stu-push-status'), btn = $('stu-push-btn'), off = $('stu-push-off');
    if (!status) return;
    const devices = pushStatusCache.devices || [];
    const registered = devices.length > 0;
    const deviceNames = devices.map(device => device.deviceInfo).filter(Boolean);
    const sending = !!pushStatusCache.pushEnabled;   // server holds its VAPID keys
    if (window.isSecureContext === false) {
        status.textContent = 'Phone push requires HTTPS. Open the app using its HTTPS address; plain local-IP HTTP cannot register push devices.';
        if (btn) btn.hidden = true;
        return;
    }
    if (typeof Notification !== 'undefined' && Notification.permission === 'denied') {
        status.textContent = 'Notifications are blocked in your browser settings.';
        if (btn) btn.hidden = true;
        if (off) off.hidden = !registered;
        return;
    }
    if (!('PushManager' in window) || !('serviceWorker' in navigator)) {
        status.textContent = 'This browser does not support push notifications.';
        if (btn) btn.hidden = true;
        return;
    }
    if (registered) {
        const devicesLabel = deviceNames.length ? deviceNames.join(', ') : devices.length + ' device(s)';
        status.textContent = 'Push enabled on ' + devicesLabel + '.';
        if (btn) btn.hidden = true;
        if (off) off.hidden = false;
        return;
    }
    status.textContent = sending
        ? 'Get exam alerts even when this tab is closed.'
        : 'Enable notifications to receive exam alerts in the background.';
    if (btn) btn.hidden = false;
    if (off) off.hidden = true;
}

/** base64url VAPID key -> Uint8Array for pushManager.subscribe(). */
function urlBase64ToUint8Array(base64String) {
    const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
    const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
    const raw = window.atob(base64);
    const output = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i += 1) output[i] = raw.charCodeAt(i);
    return output;
}

/* ---------- Native Web Push (VAPID), no third-party service ---------- */

function identifyBrowserDevice() {
    const userAgent = navigator.userAgent || '';
    let browser = 'Browser';
    if (/Edg\//i.test(userAgent)) browser = 'Microsoft Edge';
    else if (/OPR\//i.test(userAgent)) browser = 'Opera';
    else if (/Firefox\//i.test(userAgent)) browser = 'Firefox';
    else if (/Chrome\//i.test(userAgent)) browser = 'Chrome';
    else if (/Safari\//i.test(userAgent)) browser = 'Safari';

    const platform = navigator.userAgentData && navigator.userAgentData.platform
        ? navigator.userAgentData.platform
        : navigator.platform || 'unknown device';
    return browser + ' on ' + platform + (navigator.language ? ' · ' + navigator.language : '');
}

/**
 * Explicit user action -> service worker -> permission -> VAPID subscription ->
 * server registration. The permission prompt only ever appears from this click
 * (or the once-per-session entry prompt), never on its own. Every failure path
 * leaves the app fully usable.
 */
async function enablePushNotifications() {
    const btn = $('stu-push-btn'), status = $('stu-push-status');
    if (btn) { btn.disabled = true; }
    try {
        if (window.isSecureContext === false) {
            throw new Error('Phone push requires HTTPS. Open the app using its HTTPS address, then enable notifications again.');
        }
        if (!('Notification' in window)) {
            throw new Error('This browser does not support push notifications.');
        }
        if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
            throw new Error('This browser does not support background push notifications.');
        }
        // 1. Register the service worker (no permission prompt here).
        const registration = await navigator.serviceWorker.register('/sw.js');
        await navigator.serviceWorker.ready;
        // 2. Ask for permission - only from this explicit action.
        let permission = Notification.permission;
        if (permission !== 'granted') {
            permission = await Notification.requestPermission();
        }
        if (permission !== 'granted') {
            throw new Error(permission === 'denied'
                ? 'Notifications are blocked. Allow them in your browser settings, then try again.'
                : 'Permission not granted - in-app notifications still work.');
        }
        // 3. Subscribe with the server's public VAPID key, then register it.
        if (!pushStatusCache.vapidKey) {
            throw new Error('Push is not configured on the server yet. In-app notifications still work.');
        }
        const subscription = await registration.pushManager.subscribe({
            userVisibleOnly: true,
            applicationServerKey: urlBase64ToUint8Array(pushStatusCache.vapidKey)
        });
        await api('/api/student/push/register', jsonOpts('POST', {
            subscription: subscription.toJSON(),
            deviceInfo: identifyBrowserDevice()
        }));
        if (status) status.textContent = 'Push enabled on this device.';
        toast('Notifications enabled on this device.');
        await refreshNotifications();
    } catch (e) {
        // Permission denial or any failure must never break the portal.
        if (status) status.textContent = e.message;
        toast(e.message, true);
    } finally {
        if (btn) btn.disabled = false;
    }
}
window.enablePushNotifications = enablePushNotifications;

async function disablePushNotifications() {
    try {
        const devices = (pushStatusCache.devices || []).slice();
        for (const device of devices) {
            await api('/api/student/push/unregister?id=' + device.id, jsonOpts('DELETE', {}));
        }
        toast('Push notifications turned off on this device.');
        await refreshNotifications();
    } catch (e) { toast(e.message, true); }
}
window.disablePushNotifications = disablePushNotifications;

/** "3 minutes ago" style label; falls back to the raw timestamp. */
function fmtWhen(iso) {
    if (!iso) return '';
    const then = new Date(iso);
    if (isNaN(then)) return String(iso).slice(0, 16).replace('T', ' ');
    const seconds = Math.max(0, (Date.now() - then.getTime()) / 1000);
    if (seconds < 60) return 'just now';
    if (seconds < 3600) return Math.floor(seconds / 60) + ' min ago';
    if (seconds < 86400) return Math.floor(seconds / 3600) + ' h ago';
    return then.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

/* ==================== 7. Admin: push notification templates ==================== */

let notificationTemplates = [];

/** Loads every template (stored or default) into the Settings screen. */
async function loadNotificationTemplates() {
    const box = $('tpl-list');
    try {
        notificationTemplates = await api('/api/admin/notification-templates');
        renderNotificationTemplates();
    } catch (e) {
        if (box) box.innerHTML = '<p class="hint">Could not load templates: ' + esc(e.message) + '</p>';
    }
}
window.loadNotificationTemplates = loadNotificationTemplates;

function renderNotificationTemplates() {
    const box = $('tpl-list');
    if (!box) return;
    if (!notificationTemplates.length) {
        box.innerHTML = '<p class="hint">No notification types found.</p>';
        return;
    }
    box.innerHTML = notificationTemplates.map(t => {
        const chips = (t.placeholders || []).map(p =>
            '<button type="button" class="tpl-token" data-tpl-insert="1" data-token="'
            + esc(p) + '" title="Insert {' + esc(p) + '} into the title">{' + esc(p) + '}</button>')
            .join(' ');
        return '<div class="tpl-card" data-tpl-key="' + esc(t.key) + '">'
            + '<div class="tpl-head"><b>' + esc(t.label) + '</b>'
            + (t.isDefault
                ? '<span class="badge" style="background:#64748B">Default</span>'
                : '<span class="badge" style="background:#F59E0B">Custom</span>')
            + '</div>'
            + '<label>Title<input type="text" class="tpl-title" maxlength="200" value="'
            + esc(t.title) + '"></label>'
            + '<label>Message<textarea class="tpl-body" rows="2" maxlength="1000">'
            + esc(t.body) + '</textarea></label>'
            + '<div class="tpl-actions">'
            + '<button type="button" class="btn btn-sm btn-success" data-tpl-act="save">Save</button>'
            + '<button type="button" class="btn btn-sm btn-ghost" data-tpl-act="reset"'
            + (t.isDefault ? ' disabled' : '') + '>Reset to default</button>'
            + '<span class="hint" style="margin:0">Insert: ' + chips + '</span>'
            + '</div></div>';
    }).join('');
}

/* One delegated listener for token chips, Save and Reset (dynamic re-renders). */
document.addEventListener('click', async e => {
    const insert = e.target.closest('[data-tpl-insert]');
    if (insert) {
        const card = insert.closest('[data-tpl-key]');
        const input = card && card.querySelector('.tpl-title');
        if (input) {
            input.value += '{' + (insert.dataset.token || '') + '}';
            input.focus();
        }
        return;
    }
    const btn = e.target.closest('[data-tpl-act]');
    if (!btn) return;
    const card = btn.closest('[data-tpl-key]');
    if (!card) return;
    const key = card.dataset.tplKey;
    try {
        if (btn.dataset.tplAct === 'save') {
            const res = await api('/api/admin/notification-templates/' + encodeURIComponent(key),
                jsonOpts('PUT', {
                    title: (card.querySelector('.tpl-title') || {}).value || '',
                    body: (card.querySelector('.tpl-body') || {}).value || ''
                }));
            notificationTemplates = res.templates || [];
            renderNotificationTemplates();
            toast('Template saved - it applies from the next notification on.');
        } else {
            if (!confirm('Reset "' + key + '" to its default wording?')) return;
            const res = await api('/api/admin/notification-templates/' + encodeURIComponent(key)
                + '/reset', jsonOpts('POST', {}));
            notificationTemplates = res.templates || [];
            renderNotificationTemplates();
            toast('Template reset to the built-in default.');
        }
    } catch (err) { toast(err.message, true); }
});

/* ==================== 8. Boot ==================== */

function wireLogin() {
    const form = $('login-form');
    $('pw-toggle').addEventListener('click', () => {
        const input = $('login-password'), btn = $('pw-toggle');
        const showing = input.type === 'text';
        input.type = showing ? 'password' : 'text';
        btn.textContent = showing ? 'Show' : 'Hide';
        btn.setAttribute('aria-pressed', String(!showing));
        btn.setAttribute('aria-label', showing ? 'Show password' : 'Hide password');
    });
    form.addEventListener('submit', async e => {
        e.preventDefault();
        const username = $('login-username').value.trim();
        const password = $('login-password').value;
        const err = $('login-error');
        err.classList.remove('show');
        if (!username || !password) {
            err.textContent = 'Please enter both username and password.';
            err.classList.add('show');
            return;
        }
        const btn = $('login-btn');
        setBusy(btn, true, 'Signing in…');
        try {
            const me = await doLogin(username, password);
            $('login-password').value = '';
            await route(me);
        } catch (ex) {
            err.textContent = ex.message;
            err.classList.add('show');
        } finally {
            setBusy(btn, false);
        }
    });
}

async function boot() {
    wireLogin();
    const me = await fetchMe();
    if (me) await route(me); else showLogin();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
} else {
    boot();
}

})();
