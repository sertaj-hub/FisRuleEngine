'use strict';
// Rule configuration UI. Talks only to the REST API; every value from the server is inserted as text, never as HTML.
let auth = null, me = null, templates = {}, txnTypes = [];
const main = document.getElementById('main');

function h(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === 'class') e.className = v; else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (v === true) e.setAttribute(k, ''); else if (v !== false && v != null) e.setAttribute(k, v);
  }
  for (const c of kids.flat()) if (c != null && c !== false) e.append(c.nodeType ? c : document.createTextNode(String(c)));
  return e;
}
const can = role => me && me.roles.includes(role);

async function api(method, path, body) {
  const res = await fetch('/api' + path, { method, headers: { 'Authorization': auth, ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) { const err = new Error(res.status === 401 ? 'Please sign in again' : res.status === 403 ? 'Your role does not allow this' : (data && data.error) || res.statusText); err.status = res.status; throw err; }
  return data;
}
function show(node) { main.replaceChildren(node); }
function msg(kind, text) { return h('div', { class: 'msg ' + kind }, text); }
function guard(fn) { return async (...a) => { try { await fn(...a); } catch (e) { if (e.status === 401) return login(); main.prepend(msg('err', e.message)); window.scrollTo(0, 0); } }; }
const when = t => t ? t.replace('T', ' ').slice(0, 16) : '';

// ---- login ----
function login(message) {
  document.getElementById('nav').hidden = true; document.getElementById('who').textContent = ''; auth = null; me = null;
  const u = h('input', { id: 'u', autocomplete: 'username' }), p = h('input', { id: 'p', type: 'password', autocomplete: 'current-password' });
  const go = guard(async () => {
    auth = 'Basic ' + btoa(unescape(encodeURIComponent(u.value + ':' + p.value)));
    try { me = await api('GET', '/me'); } catch (e) { auth = null; throw new Error(e.status === 401 ? 'Wrong user name or password' : e.message); }
    [templates, txnTypes] = await Promise.all([api('GET', '/templates'), api('GET', '/txn-types')]);
    document.getElementById('nav').hidden = false;
    document.getElementById('who').textContent = me.username + ' (' + me.roles.join(', ') + ')';
    view('rules');
  });
  show(h('div', { class: 'card narrow' }, h('h2', {}, 'Sign in'), message && msg('err', message),
    h('label', {}, 'User name'), u, h('label', {}, 'Password'), p, h('div', { class: 'actions' }, h('button', { class: 'primary', onclick: go }, 'Sign in'))));
  p.addEventListener('keydown', e => e.key === 'Enter' && go());
  u.focus();
}

document.getElementById('nav').addEventListener('click', e => { const v = e.target.closest('button'); if (v) view(v.dataset.view); });
const view = guard(async (name, arg) => {
  document.querySelectorAll('#nav button').forEach(b => b.classList.toggle('on', b.dataset.view === name));
  if (name === 'rules') return rulesView();
  if (name === 'pending') return pendingView();
  if (name === 'new') return editor(null);
  if (name === 'rule') return ruleView(arg);
});

// ---- rule list and approvals ----
async function rulesView() {
  const rules = await api('GET', '/rules');
  refreshPending();
  show(h('div', { class: 'card' }, h('h2', {}, 'Rules'), h('table', {},
    h('thead', {}, h('tr', {}, ['Rule', 'Name', 'Template', 'Active version', 'Latest version'].map(t => h('th', {}, t)))),
    h('tbody', {}, rules.map(r => h('tr', { class: 'click', onclick: () => view('rule', r.code) },
      h('td', {}, r.code), h('td', {}, r.name), h('td', {}, r.template), h('td', {}, r.activeVersion == null ? '-' : 'v' + r.activeVersion),
      h('td', {}, 'v' + r.latestVersion + ' ', h('span', { class: 'tag ' + r.latestStatus }, r.latestStatus))))))));
}
async function refreshPending() { try { const p = await api('GET', '/pending'); document.getElementById('pendingCount').textContent = p.length ? '(' + p.length + ')' : ''; return p; } catch (e) { return []; } }
async function pendingView() {
  const p = await refreshPending();
  show(h('div', { class: 'card' }, h('h2', {}, 'Waiting for approval'), p.length === 0 ? h('p', { class: 'muted' }, 'Nothing is waiting.') : h('table', {},
    h('thead', {}, h('tr', {}, ['Rule', 'Version', 'Submitted by', 'When', 'Reason'].map(t => h('th', {}, t)))),
    h('tbody', {}, p.map(v => h('tr', { class: 'click', onclick: () => view('rule', v.ruleCode) }, h('td', {}, v.ruleCode), h('td', {}, 'v' + v.version),
      h('td', {}, v.submittedBy), h('td', {}, when(v.submittedTs)), h('td', {}, v.changeReason)))))));
}

// ---- one rule ----
function diffView(oldObj, newObj) {
  const a = JSON.stringify(sortKeys(oldObj || {}), null, 2).split('\n'), b = JSON.stringify(sortKeys(newObj), null, 2).split('\n');
  const inA = new Set(a), inB = new Set(b), pre = h('pre', { class: 'diff' });
  for (const l of b) pre.append(h('div', { class: oldObj && !inA.has(l) ? 'add' : '' }, (oldObj ? (inA.has(l) ? '  ' : '+ ') : '') + l));
  if (oldObj) for (const l of a) if (!inB.has(l)) pre.append(h('div', { class: 'del' }, '- ' + l));
  return pre;
}
function sortKeys(o) { return Array.isArray(o) ? o.map(sortKeys) : o && typeof o === 'object' ? Object.fromEntries(Object.keys(o).sort().map(k => [k, sortKeys(o[k])])) : o; }

async function ruleView(code, selected) {
  const [versions, audit] = await Promise.all([api('GET', '/rules/' + code + '/versions'), api('GET', '/rules/' + code + '/audit')]);
  const active = versions.find(v => v.status === 'ACTIVE');
  const sel = versions.find(v => v.version === selected) || versions[0];
  const holder = h('div', {});
  const run = fn => guard(async () => { await fn(); await ruleView(code, sel.version); });
  const note = text => { const n = prompt(text); return n === null ? null : n; };
  const actions = h('div', { class: 'actions' });
  if (can('AUTHOR') && sel.status === 'DRAFT' && sel.authoredBy === me.username) {
    actions.append(h('button', { onclick: () => editor(sel, 'edit') }, 'Edit draft'),
      h('button', { class: 'primary', onclick: run(async () => { await api('POST', `/rules/${code}/versions/${sel.version}/submit`, { note: note('Note for the approver (optional)') || '' }); }) }, 'Submit for approval'));
  }
  if (can('AUTHOR')) actions.append(h('button', { onclick: guard(async () => { const d = await api('POST', `/rules/${code}/versions/${sel.version}/dry-run?days=7`); holder.replaceChildren(dryRunView(d)); }) }, 'Dry-run (7 days)'));
  if (can('AUTHOR') && !versions.some(v => v.status === 'DRAFT' || v.status === 'PENDING_APPROVAL')) actions.append(h('button', { onclick: () => editor(sel, 'new-version') }, 'New version from v' + sel.version));
  if (can('APPROVER') && sel.status === 'PENDING_APPROVAL') {
    actions.append(h('button', { class: 'primary', onclick: run(async () => { await api('POST', `/rules/${code}/versions/${sel.version}/approve`, { note: note('Approval note (optional)') || '' }); }) }, 'Approve'),
      h('button', { class: 'danger', onclick: run(async () => { const n = note('Reason for rejecting (required)'); if (n) await api('POST', `/rules/${code}/versions/${sel.version}/reject`, { note: n }); }) }, 'Reject'));
  }
  if (can('APPROVER') && sel.status === 'ACTIVE') actions.append(h('button', { class: 'danger', onclick: run(async () => { const n = note('Reason for retiring (required)'); if (n) await api('POST', `/rules/${code}/versions/${sel.version}/retire`, { note: n }); }) }, 'Retire'));
  show(h('div', {},
    h('p', {}, h('a', { href: '#', onclick: e => { e.preventDefault(); view('rules'); } }, '< Rules')),
    h('div', { class: 'card' }, h('h2', {}, sel.ruleCode + ' - ' + sel.name), h('p', { class: 'muted' }, sel.description || ''),
      h('table', {}, h('thead', {}, h('tr', {}, ['Version', 'Status', 'Source', 'Author', 'Submitted', 'Decided', 'Reason / note'].map(t => h('th', {}, t)))),
        h('tbody', {}, versions.map(v => h('tr', { class: 'click', onclick: () => ruleView(code, v.version) },
          h('td', {}, (v.version === sel.version ? '> ' : '') + 'v' + v.version), h('td', {}, h('span', { class: 'tag ' + v.status }, v.status)), h('td', {}, v.source), h('td', {}, v.authoredBy || ''),
          h('td', {}, (v.submittedBy || '') + ' ' + when(v.submittedTs)), h('td', {}, (v.decidedBy || '') + ' ' + when(v.decidedTs)), h('td', {}, v.decisionNote || v.changeReason || '')))))),
    h('div', { class: 'card' }, h('h2', {}, 'Version ' + sel.version + ' (' + sel.template + ', suppress for ' + sel.suppressDays + ' days)'), actions, holder,
      h('h3', {}, active && active.version !== sel.version ? 'Configuration (changes against active v' + active.version + ' highlighted)' : 'Configuration'),
      diffView(active && active.version !== sel.version ? active.config : null, sel.config)),
    h('div', { class: 'card' }, h('h2', {}, 'Audit trail'), h('table', {}, h('tbody', {}, audit.map(a => h('tr', {}, h('td', { class: 'nowrap' }, when(a.auditTs)), h('td', {}, a.actor), h('td', {}, a.action + (a.version ? ' v' + a.version : '')),
      h('td', {}, h('code', {}, JSON.stringify(a.detail).slice(0, 160)))))))))); 
}

function dryRunView(d) {
  const n = x => x == null ? '-' : x;
  return h('div', {}, h('h3', {}, 'Dry-run: ' + d.days + ' posting days' + (d.activeVersion ? ', compared with active v' + d.activeVersion : ', no active version to compare')),
    h('p', {}, 'Proposed alerts in total: ', h('b', {}, d.totalProposed), d.activeVersion ? [' | active version: ', h('b', {}, d.totalActive)] : ''),
    h('table', {}, h('thead', {}, h('tr', {}, ['Posting day', 'Proposed', 'Active', 'Added', 'Removed', ''].map(t => h('th', {}, t)))),
      h('tbody', {}, d.perDay.map(r => h('tr', {}, h('td', {}, r.postingDay), h('td', {}, r.proposed), h('td', {}, n(r.active)), h('td', {}, n(r.added)), h('td', {}, n(r.removed)), h('td', { class: 'muted' }, r.skipped || ''))))),
    d.sample.length ? h('details', {}, h('summary', {}, 'Sample of ' + d.sample.length + ' hit(s)'), h('pre', {}, JSON.stringify(d.sample, null, 2))) : '', h('p', { class: 'muted' }, d.note));
}

// ---- editor generated from template fields ----
function editor(version, mode) {
  const isNew = !version, creating = isNew || mode === 'new-version';
  const tplCodes = Object.keys(templates), cfg = version ? version.config : {};
  const code = h('input', { value: version ? version.ruleCode : '', disabled: !isNew, placeholder: 'LARGE_CASH_DAILY' });
  const name = h('input', { value: version ? version.name : '' }), desc = h('textarea', { rows: 2 }, version ? version.description || '' : '');
  const tpl = h('select', { disabled: !isNew }, tplCodes.map(c => h('option', { value: c, selected: version && version.template === c }, c)));
  const suppress = h('input', { type: 'number', min: 0, max: 365, value: version ? version.suppressDays : 0 });
  const reason = h('input', { placeholder: 'Why is this change needed? (kept in the audit trail)' });
  const fieldsBox = h('div', {}), result = h('div', {});
  let readers = {};
  function drawFields() {
    readers = {}; fieldsBox.replaceChildren();
    for (const f of templates[tpl.value]) {
      const cur = cfg[f.key], box = h('div', {}, h('label', {}, f.label + (f.required ? ' *' : ''), ' ', h('span', { class: 'help' }, f.help)));
      if (f.kind === 'INT' || f.kind === 'DECIMAL') { const i = h('input', { type: 'number', step: f.kind === 'INT' ? 1 : 'any', value: cur ?? '' }); box.append(i); readers[f.key] = () => i.value === '' ? undefined : Number(i.value); }
      else if (f.kind === 'TEXT') { const i = h('input', { value: cur ?? '' }); box.append(i); readers[f.key] = () => i.value.trim() || undefined; }
      else if (f.kind === 'ENUM') { const s = h('select', {}, h('option', { value: '' }, '-'), f.options.map(o => h('option', { value: o, selected: cur === o }, o))); box.append(s); readers[f.key] = () => s.value || undefined; }
      else if (f.kind === 'PRODUCTS') { const cs = ['CARD', 'LOAN', 'DEPOSIT'].map(p => h('input', { type: 'checkbox', value: p, checked: (cur || []).includes(p) })); box.append(h('div', { class: 'checks' }, cs.map(c => h('label', {}, c, ' ', c.value)))); readers[f.key] = () => { const v = cs.filter(c => c.checked).map(c => c.value); return v.length ? v : undefined; }; }
      else if (f.kind === 'FILTER') { const fb = filterBox(cur || {}); box.append(fb.node); readers[f.key] = fb.read; }
      fieldsBox.append(box);
    }
  }
  function filterBox(cur) {
    const types = txnTypes.map(t => h('input', { type: 'checkbox', value: t.txn_type, checked: (cur.txn_types || []).includes(t.txn_type) }));
    const cash = h('select', {}, [['', 'cash or not'], ['true', 'cash only'], ['false', 'not cash']].map(([v, l]) => h('option', { value: v, selected: String(cur.cash ?? '') === v }, l)));
    const dir = h('select', {}, ['ANY', 'CREDIT', 'DEBIT'].map(v => h('option', { value: v, selected: (cur.direction || 'ANY') === v }, v)));
    const channels = h('input', { value: (cur.channels || []).join(', '), placeholder: 'channels, comma separated' });
    const min = h('input', { type: 'number', step: 'any', value: cur.min_amount ?? '', placeholder: 'each transaction at least' }), max = h('input', { type: 'number', step: 'any', value: cur.max_amount ?? '', placeholder: 'each transaction below' });
    const node = h('div', { class: 'sub' }, h('div', { class: 'checks' }, types.map(t => h('label', {}, t, ' ', t.value))), h('div', { class: 'row' }, cash, dir), channels, h('div', { class: 'row' }, min, max));
    return { node, read: () => { const o = {}; const t = types.filter(c => c.checked).map(c => c.value); if (t.length) o.txn_types = t; if (cash.value) o.cash = cash.value === 'true'; if (dir.value !== 'ANY') o.direction = dir.value;
      const ch = channels.value.split(',').map(s => s.trim()).filter(Boolean); if (ch.length) o.channels = ch; if (min.value !== '') o.min_amount = Number(min.value); if (max.value !== '') o.max_amount = Number(max.value); return Object.keys(o).length ? o : undefined; } };
  }
  const build = () => { const config = {}; for (const [k, r] of Object.entries(readers)) { const v = r(); if (v !== undefined) config[k] = v; } return config; };
  tpl.addEventListener('change', () => { for (const k of Object.keys(cfg)) delete cfg[k]; drawFields(); });
  drawFields();
  const body = () => ({ name: name.value, description: desc.value || null, template: tpl.value, suppressDays: Number(suppress.value || 0), config: build(), reason: reason.value });
  const save = guard(async () => {
    const saved = isNew || creating ? await api('POST', `/rules/${code.value || version.ruleCode}/versions`, body()) : await api('PUT', `/rules/${version.ruleCode}/versions/${version.version}`, body());
    view('rule', saved.ruleCode);
  });
  const dry = guard(async () => { result.replaceChildren(h('p', { class: 'muted' }, 'Running...')); result.replaceChildren(dryRunView(await api('POST', '/dry-run', { code: code.value || version.ruleCode, template: tpl.value, config: build(), days: 7 }))); });
  show(h('div', { class: 'card' }, h('h2', {}, isNew ? 'New rule' : creating ? 'New version of ' + version.ruleCode : 'Edit draft v' + version.version),
    h('div', { class: 'row' }, h('div', {}, h('label', {}, 'Rule code'), code), h('div', {}, h('label', {}, 'Template'), tpl)),
    h('label', {}, 'Name'), name, h('label', {}, 'Description'), desc, h('label', {}, 'Suppress repeat alerts for (days)'), suppress,
    h('h3', {}, 'Configuration'), fieldsBox, h('label', {}, 'Reason for the change *'), reason,
    h('div', { class: 'actions' }, h('button', { class: 'primary', onclick: save }, 'Save draft'), h('button', { onclick: dry }, 'Dry-run (7 days)'), h('button', { onclick: () => isNew ? view('rules') : view('rule', version.ruleCode) }, 'Cancel')), result));
}

login();
