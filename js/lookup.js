// 逆引き画面（場所から・ガシャのコインから）
// 選んだ条件は sessionStorage に残す（一覧の絞り込みと同じ）
import { AREA_GROUPS, COIN_ORDER, VERSIONS, VERSION_LABELS, ERA_LABELS, TIME_LABELS } from './constants.js';
import { esc, UNKNOWN } from './util.js';
import { store } from './store.js';

const KEY = 'yw2zukan.lookup.v1';
const DEFAULT = { mode: 'place', area: null, era: 'all', time: 'all', uncaughtOnly: true, coin: null };

let all = [];
let rowHtml = null;   // 一覧と同じ行を作る関数（app.js から受け取る）
let root = null;
let areas = [];       // [グループ名, [エリア名]]
let coins = [];
const state = load();

function load() {
  try {
    const s = JSON.parse(sessionStorage.getItem(KEY));
    if (s && typeof s === 'object') return { ...DEFAULT, ...s };
  } catch { /* 読めなければ初期値 */ }
  return { ...DEFAULT };
}

function save() {
  try { sessionStorage.setItem(KEY, JSON.stringify(state)); } catch { /* 保存できなくても動く */ }
}

export function initLookup(list, makeRow, container) {
  all = list;
  rowHtml = makeRow;
  root = container;

  // データに出てくるエリア（野生）とコインだけを選べるようにする
  const seenAreas = new Set(), seenCoins = new Set();
  for (const y of all) for (const o of y.obtain || []) {
    if (o.method === 'wild' && o.area) seenAreas.add(o.area);
    if (o.method === 'gasha') for (const c of o.coins || []) if (c.coin) seenCoins.add(c.coin);
  }
  const listed = new Set(AREA_GROUPS.flatMap(([, as]) => as));
  areas = AREA_GROUPS.map(([g, as]) => [g, as.filter(a => seenAreas.has(a))]).filter(([, as]) => as.length);
  const rest = [...seenAreas].filter(a => !listed.has(a)).sort((a, b) => a.localeCompare(b, 'ja'));
  if (rest.length) areas.push(['そのほか', rest]);
  const rank = c => { const i = COIN_ORDER.findIndex(p => c.startsWith(p)); return i < 0 ? COIN_ORDER.length : i; };
  coins = [...seenCoins].sort((a, b) => rank(a) - rank(b) || a.localeCompare(b, 'ja'));
  if (!seenAreas.has(state.area)) state.area = areas[0]?.[1][0] ?? null;
  if (!seenCoins.has(state.coin)) state.coin = coins[0] ?? null;

  root.addEventListener('change', e => {
    if (e.target.id === 'lkArea') state.area = e.target.value;
    else if (e.target.id === 'lkCoin') state.coin = e.target.value;
    else return;
    save();
    renderLookup();
  });
  root.addEventListener('click', e => {
    const b = e.target.closest('[data-lk]');
    if (!b) return;
    const [k, v] = [b.dataset.lk, b.dataset.value];
    state[k] = k === 'uncaughtOnly' ? v === 'true' : v;
    save();
    renderLookup();
  });
}

function versionsNote(vs) {
  if (!vs || VERSIONS.every(v => vs.includes(v))) return null;
  return VERSIONS.filter(v => vs.includes(v)).map(v => VERSION_LABELS[v]).join('・') + 'のみ';
}

// 自分のバージョンで、この入手方法が使えない
function otherVersion(entries) {
  const my = store.myVersion();
  return my != null && !entries.some(o => (o.versions || []).includes(my));
}

function opts(key, options) {
  return options.map(([v, text]) =>
    `<button type="button" class="opt" data-lk="${key}" data-value="${v}" aria-pressed="${String(state[key]) === v}">${esc(text)}</button>`).join('');
}

// ---------- 場所から ----------
function placeHits() {
  const hits = [];
  for (const y of all) {
    const es = (y.obtain || []).filter(o => o.method === 'wild' && o.area === state.area &&
      (state.era === 'all' || o.era == null || o.era === state.era) &&
      (state.time === 'all' || !o.time || o.time.length === 0 || o.time.includes(state.time)));
    if (es.length) hits.push({ y, es });
  }
  return hits;
}

function placeLine(o) {
  const parts = [o.spot || 'エリア内'];
  if (o.how) parts.push(o.how);
  if (o.era) parts.push(ERA_LABELS[o.era]);
  parts.push(o.time && o.time.length ? o.time.map(t => TIME_LABELS[t]).join('・') + 'だけ' : '昼も夜も');
  if (o.weather) parts.push(o.weather + 'のとき');
  const v = versionsNote(o.versions);
  if (v) parts.push(v);
  if (o.note) parts.push(o.note);
  return parts.join('／');
}

function placeHtml() {
  const groups = areas.map(([g, as]) => `<optgroup label="${esc(g)}">${as.map(a => `<option${a === state.area ? ' selected' : ''}>${esc(a)}</option>`).join('')}</optgroup>`).join('');
  const hits = placeHits();
  const left = hits.filter(h => !store.isCaught(h.y.id));
  const shown = state.uncaughtOnly ? left : hits;
  const rows = shown.map(({ y, es }) => rowHtml(y, { sub: es.map(placeLine), dim: otherVersion(es) })).join('');
  return `<section class="sec lk-form">
      <label class="field"><span>エリア</span><select id="lkArea">${groups}</select></label>
      <fieldset class="fgroup"><legend>時代</legend><div class="opts">${opts('era', [['all', 'すべて'], ['present', '現代'], ['past', '過去']])}</div></fieldset>
      <fieldset class="fgroup"><legend>時間帯</legend><div class="opts">${opts('time', [['all', 'すべて'], ['day', '昼'], ['night', '夜']])}</div></fieldset>
      <fieldset class="fgroup"><legend>表示</legend><div class="opts">${opts('uncaughtOnly', [['true', '未入手だけ'], ['false', '全部']])}</div></fieldset>
    </section>
    <p class="result-line" id="lkCount">${countText(hits.map(h => h.y.id), '体が未入手（このエリアで出るのは')}</p>
    ${rows ? `<ul class="rows">${rows}</ul>` : `<p class="empty">${hits.length ? 'このエリアの妖怪はすべて捕まえました' : '条件に合う妖怪はいません'}</p>`}`;
}

// ---------- ガシャのコインから ----------
function coinHits() {
  const hits = [];
  for (const y of all) {
    const es = (y.obtain || []).filter(o => o.method === 'gasha' && (o.coins || []).some(c => c.coin === state.coin));
    if (es.length) hits.push({ y, es });
  }
  return hits;
}

function coinLine(o) {
  const c = o.coins.find(x => x.coin === state.coin);
  const parts = [c.rate == null ? `確率 ${UNKNOWN}` : `確率 ${c.rate}%`];
  if (o.era) parts.push(ERA_LABELS[o.era] + 'のガシャ');
  const v = versionsNote(o.versions);
  if (v) parts.push(v);
  return parts.join('／');
}

function coinHtml() {
  const hits = coinHits();
  const rows = hits.map(({ y, es }) => rowHtml(y, { sub: [es.map(coinLine).join('　')], dim: otherVersion(es), want: true })).join('');
  const noRate = hits.every(h => h.es.every(o => o.coins.every(c => c.coin !== state.coin || c.rate == null)));
  return `<section class="sec lk-form">
      <label class="field"><span>コイン</span><select id="lkCoin">${coins.map(c => `<option${c === state.coin ? ' selected' : ''}>${esc(c)}</option>`).join('')}</select></label>
      ${noRate ? '<p class="fine">このコインの出現確率は、公式にも攻略サイトにも載っていないため「不明」としています。</p>' : ''}
    </section>
    <p class="result-line" id="lkCount">${countText(hits.map(h => h.y.id), '体が未入手（このコインで出るのは')}</p>
    ${rows ? `<ul class="rows">${rows}</ul>` : '<p class="empty">このコインで出る妖怪はいません</p>'}`;
}

function countText(ids, middle) {
  const left = ids.length - store.caughtCount(ids);
  return `<span class="num"><b>${left}</b> ${middle} ${ids.length} 体）</span>`;
}

export function renderLookup() {
  root.innerHTML = `<h2 class="page-title">逆引き</h2>
    <div class="opts seg">${opts('mode', [['place', '場所から'], ['coin', 'ガシャのコインから']])}</div>
    ${state.mode === 'coin' ? coinHtml() : placeHtml()}`;
}

// チェックを切り替えたときは行を消さず、件数だけ数え直す
export function refreshLookupCount() {
  const el = root && root.querySelector('#lkCount');
  if (!el) return;
  const hits = state.mode === 'coin' ? coinHits() : placeHits();
  el.innerHTML = countText(hits.map(h => h.y.id), state.mode === 'coin' ? '体が未入手（このコインで出るのは' : '体が未入手（このエリアで出るのは');
}
