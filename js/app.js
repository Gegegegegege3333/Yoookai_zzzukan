import { TRIBES, TRIBE_COLORS, VERSIONS, VERSION_LABELS, METHOD_LABELS, ERA_LABELS, TIME_LABELS, STAT_KEYS, STAT_LABELS } from './constants.js';
import { esc, dexLabel, orUnknown, pctText, UNKNOWN, NONE } from './util.js';
import { store } from './store.js';
import { loadFilter, saveFilter, matches, panelHtml, applyClick, resetFilter, activeCount, isFiltering } from './filter.js';
import { progressPageHtml, settingsPageHtml } from './pages.js';
import { initLookup, renderLookup, refreshLookupCount } from './lookup.js';

const $ = id => document.getElementById(id);
const CHECK_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>';
const PIN_SVG = '<svg viewBox="0 0 24 24" stroke-width="2" stroke-linejoin="round" aria-hidden="true"><path d="M9 3h6l-1 6 4 4v2H6v-2l4-4z"/><path d="M12 15v6" fill="none"/></svg>';
const UNDO_SECONDS = 5;
const STAT_MAX = 400; // ステータスの棒の長さの基準（Lv60のHPの上限あたり）

let all = [];          // sortOrder 順の全妖怪
let byId = new Map();
let recipes = [];      // 全妖怪の合成レシピ（重複を除いたもの）
let recipesByResult = new Map();
const filter = loadFilter();
const filterCtx = { isCaught: store.isCaught, canGet: y => canGetBy(y).length > 0 };

// ---------- 起動 ----------
async function main() {
  try {
    const res = await fetch('data/yokai.json', { cache: 'no-cache' });
    const data = await res.json();
    all = data.slice().sort((a, b) => a.sortOrder - b.sortOrder);
  } catch (e) {
    console.error(e);
    $('loadError').hidden = false;
    return;
  }
  byId = new Map(all.map(y => [y.id, y]));
  recipes = collectRecipes(all);
  for (const r of recipes) {
    if (!recipesByResult.has(r.result)) recipesByResult.set(r.result, []);
    recipesByResult.get(r.result).push(r);
  }

  setupFinder();
  initLookup(all, rowHtml, $('lookupView'));
  renderList();
  renderProgress();
  store.onChange(onCaughtChange);
  window.addEventListener('hashchange', route);
  route();
  // 電波がなくても開けるように（https か、この PC の中で開いたときだけ動く）
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(e => console.warn('Service Worker を登録できませんでした', e));
}

// 合成レシピは結果の妖怪・素材の妖怪の両方に書いてあるので、1つにまとめる
function collectRecipes(list) {
  const seen = new Map();
  for (const y of list) {
    for (const r of y.fusion || []) {
      const key = r.result + '<=' + r.materials.map(m => m.type === 'yokai' ? m.id : 'item:' + m.name).sort().join('+');
      if (!seen.has(key)) seen.set(key, r);
    }
  }
  return [...seen.values()];
}

// ---------- 画面の切り替え ----------
let current = 'list';
let listScrollY = 0;
if ('scrollRestoration' in history) history.scrollRestoration = 'manual'; // スクロール位置は自分で戻す

const VIEWS = { list: 'listView', detail: 'detailView', lookup: 'lookupView', progress: 'progressView', settings: 'settingsView' };
const PAGES = { '#/lookup': 'lookup', '#/progress': 'progress', '#/settings': 'settings' };

function route() {
  const h = location.hash;
  const m = h.match(/^#\/yokai\/([\w-]+)/);
  const next = m ? 'detail' : PAGES[h] || 'list';
  if (current === 'list' && next !== 'list' && !$('listView').hidden) listScrollY = window.scrollY;
  current = next;
  if (next === 'detail') renderDetail(m[1]);
  if (next === 'lookup') renderLookup();
  if (next === 'progress') $('progressView').innerHTML = progressPageHtml(all);
  if (next === 'settings') $('settingsView').innerHTML = settingsPageHtml(all);
  for (const [k, id] of Object.entries(VIEWS)) $(id).hidden = k !== next;
  for (const t of document.querySelectorAll('.tab')) {
    const on = t.dataset.tab === (next === 'detail' ? 'list' : next);
    if (on) t.setAttribute('aria-current', 'page'); else t.removeAttribute('aria-current');
  }
  window.scrollTo(0, next === 'list' ? listScrollY : 0); // 一覧に戻ったら元の位置へ
}

// 一覧を開いているときに「一覧」タブを押したら先頭へ
document.querySelector('.tab[data-tab="list"]').addEventListener('click', e => {
  if (current !== 'list') return;
  e.preventDefault();
  window.scrollTo({ top: 0, behavior: 'smooth' });
});

// ---------- 共通の部品 ----------
function versionsText(vs) {
  if (!vs || vs.length === 0) return UNKNOWN;
  if (VERSIONS.every(v => vs.includes(v))) return '全バージョン';
  return VERSIONS.filter(v => vs.includes(v)).map(v => VERSION_LABELS[v]).join('・') + 'のみ';
}

function badges(y) {
  const c = TRIBE_COLORS[y.tribe] || '#888';
  let h = `<span class="badge tribe" style="--c:${c}">${esc(y.tribe)}</span>`;
  h += `<span class="badge rank rank-${esc(y.rank)}">${esc(y.rank)}ランク</span>`;
  if (y.isLegend) h += '<span class="badge legend">レジェンド</span>';
  if (y.isDistribution) h += '<span class="badge dist">配信系</span>';
  if (!VERSIONS.every(v => y.versions.includes(v))) h += `<span class="badge ver">${esc(versionsText(y.versions))}</span>`;
  for (const how of canGetBy(y)) h += `<span class="badge can">${how === 'evolve' ? '進化' : '合成'}で入手可</span>`;
  return h;
}

// 未入手のうち、進化元を捕まえている → 'evolve'、合成素材の妖怪をすべて捕まえている → 'fusion'
// （アイテムの所持は管理しないので、アイテムの素材は数えない）
function canGetBy(y) {
  if (store.isCaught(y.id)) return [];
  const out = [];
  if ((y.evolvesFrom || []).some(e => store.isCaught(e.id))) out.push('evolve');
  const rs = recipesByResult.get(y.id) || [];
  if (rs.some(r => { const ms = r.materials.filter(m => m.type === 'yokai'); return ms.length > 0 && ms.every(m => store.isCaught(m.id)); })) out.push('fusion');
  return out;
}

function checkButton(y, withLabel) {
  const on = store.isCaught(y.id);
  return `<button class="check" type="button" data-check="${esc(y.id)}" aria-pressed="${on}" aria-label="${esc(y.name)}を捕まえた">` +
    `<span class="box">${CHECK_SVG}</span>${withLabel ? `<span class="check-label">${on ? '捕まえた' : '未入手'}</span>` : ''}</button>`;
}

function pinButton(y, withLabel) {
  const on = store.isPinned(y.id);
  return `<button class="pin" type="button" data-pin="${esc(y.id)}" aria-pressed="${on}" aria-label="${esc(y.name)}をピン留め">` +
    `${PIN_SVG}${withLabel ? `<span class="pin-label">${on ? 'ピン留め中' : 'ピン留め'}</span>` : ''}</button>`;
}

// 一覧・ピン留め・逆引きで共通の行。sub は名前の下に出す説明の行、want は未入手を強調する
function rowHtml(y, { sub = [], dim = isOtherVersion(y), want = false } = {}) {
  const cls = ['row', store.isCaught(y.id) && 'caught', dim && 'dim', want && 'want'].filter(Boolean).join(' ');
  return `<li class="${cls}" data-id="${esc(y.id)}">` +
    `<a class="row-main" href="#/yokai/${esc(y.id)}">` +
    `<span class="row-no${y.dexNo == null ? ' legend' : ''}">${esc(dexLabel(y))}</span>` +
    `<span class="row-name">${esc(y.name)}</span>` +
    `<span class="row-badges">${badges(y)}</span>` +
    sub.map(s => `<span class="row-sub">${esc(s)}</span>`).join('') + '</a>' +
    pinButton(y, false) + checkButton(y, false) + '</li>';
}

function yokaiLink(id, extra = '') {
  const y = byId.get(id);
  if (!y) return `<li><span class="ylink missing"><span class="mark"></span><span class="nm">未収録の妖怪</span><span class="extra">${esc(id)}</span></span></li>`;
  const on = store.isCaught(id);
  return `<li><a class="ylink${on ? ' caught' : ''}" href="#/yokai/${esc(id)}"><span class="mark">${CHECK_SVG}</span><span class="nm">${esc(y.name)}</span><span class="extra">${extra}</span></a></li>`;
}

// ---------- 一覧 ----------
// 自分のバージョンで仲間にできない妖怪（一覧で薄く表示する）
function isOtherVersion(y) {
  const my = store.myVersion();
  return my != null && !y.versions.includes(my);
}

function renderList() {
  let html = '';
  for (const tribe of TRIBES) {
    const members = all.filter(y => y.tribe === tribe);
    if (members.length === 0) continue;
    const c = TRIBE_COLORS[tribe];
    html += `<section class="tribe-sec" data-tribe="${esc(tribe)}"><h2 class="tribe-head" style="--c:${c}">${esc(tribe)}<span class="count num"></span></h2><ul class="rows">`;
    html += members.map(y => rowHtml(y)).join('');
    html += '</ul></section>';
  }
  $('list').innerHTML = html;
  renderTribeCounts();
  renderPinned();
  applyFilter();
}

// 一覧の上のピン留め（ピン留めした順）
function renderPinned() {
  const ys = store.pinned().map(id => byId.get(id)).filter(Boolean);
  $('pinned').hidden = ys.length === 0;
  $('pinned').innerHTML = ys.length
    ? `<h2 class="pinned-head">${PIN_SVG}ピン留め<span class="count num">${ys.length} 体</span></h2><ul class="rows">${ys.map(y => rowHtml(y)).join('')}</ul>`
    : '';
}

// 「入手可」の印は他の妖怪のチェックで変わるので、行のバッジだけ描き直す
function refreshBadges() {
  for (const el of document.querySelectorAll('.row .row-badges')) {
    const y = byId.get(el.closest('.row').dataset.id);
    if (y) el.innerHTML = badges(y);
  }
}

function renderTribeCounts() {
  for (const s of document.querySelectorAll('.tribe-sec')) {
    const ids = all.filter(y => y.tribe === s.dataset.tribe).map(y => y.id);
    s.querySelector('.count').textContent = `${store.caughtCount(ids)} / ${ids.length}`;
  }
}

function renderProgress() {
  const total = all.length;
  const n = store.caughtCount(all.map(y => y.id));
  $('progCount').textContent = n;
  $('progTotal').textContent = total;
  $('progPct').textContent = pctText(n, total);
  $('progFill').style.width = (total ? n / total * 100 : 0) + '%';
}

// ---------- 検索・絞り込み ----------
function setupFinder() {
  const methods = new Set(all.flatMap(y => (y.obtain || []).map(o => o.method)));
  $('search').value = filter.q;
  $('filterPanel').innerHTML = panelHtml(filter, methods);
  $('filterPanel').hidden = !filter.open;
  $('filterToggle').setAttribute('aria-expanded', filter.open);

  $('search').addEventListener('input', () => { filter.q = $('search').value; filterChanged(); });
  $('filterToggle').addEventListener('click', () => {
    filter.open = !filter.open;
    $('filterPanel').hidden = !filter.open;
    $('filterToggle').setAttribute('aria-expanded', filter.open);
    saveFilter(filter);
  });
  $('filterPanel').addEventListener('click', e => {
    const btn = e.target.closest('.opt');
    if (!btn || !applyClick(filter, btn)) return;
    syncPanel();
    filterChanged();
  });
  document.addEventListener('click', e => {
    if (!e.target.closest('[data-filter-reset]')) return;
    resetFilter(filter);
    $('search').value = '';
    syncPanel();
    filterChanged();
  });
}

// パネルのボタンの押された状態を、条件に合わせる
function syncPanel() {
  for (const b of $('filterPanel').querySelectorAll('.opt')) {
    const on = b.dataset.group ? filter[b.dataset.group].includes(b.dataset.value) : filter[b.dataset.single] === b.dataset.value;
    b.setAttribute('aria-pressed', on);
  }
}

function filterChanged() {
  saveFilter(filter);
  applyFilter();
}

// 行は作り直さず、表示・非表示だけを切り替える（398体でも一瞬で終わる）
// チェックを付け外ししても、その場では行を消さない（「未入手」で絞っていても、付けた直後に消えないように）
function applyFilter() {
  let shown = 0;
  for (const s of document.querySelectorAll('.tribe-sec')) {
    let inTribe = 0;
    for (const row of s.querySelectorAll('.row')) {
      const ok = matches(byId.get(row.dataset.id), filter, filterCtx);
      row.hidden = !ok;
      if (ok) inTribe++;
    }
    s.hidden = inTribe === 0;
    shown += inTribe;
  }
  const n = activeCount(filter);
  $('filterCount').hidden = n === 0;
  $('filterCount').textContent = n;
  const on = isFiltering(filter);
  $('resultLine').hidden = !on;
  $('resultLine').innerHTML = on ? `<span class="num"><b>${shown}</b> / ${all.length} 体</span><button type="button" class="link-btn" data-filter-reset>解除</button>` : '';
  $('emptyList').hidden = shown > 0;
}

// ---------- 詳細 ----------
function renderDetail(id) {
  const y = byId.get(id);
  const view = $('detailView');
  if (!y) {
    view.innerHTML = `<div class="detail-nav"><a class="btn back" href="#/">← 一覧へ</a></div><p class="error">この妖怪は見つかりませんでした。</p>`;
    return;
  }
  const i = all.indexOf(y);
  const prev = all[i - 1], next = all[i + 1];
  const on = store.isCaught(y.id);

  view.innerHTML = `
    <div class="detail-nav">
      <a class="btn back" href="#/">← 一覧へ</a>
      <a class="btn${prev ? '' : ' disabled'}" href="${prev ? '#/yokai/' + esc(prev.id) : '#/'}" aria-label="前の妖怪">‹ 前</a>
      <a class="btn${next ? '' : ' disabled'}" href="${next ? '#/yokai/' + esc(next.id) : '#/'}" aria-label="次の妖怪">次 ›</a>
    </div>
    <div class="hero${on ? ' caught' : ''}" data-id="${esc(y.id)}">
      <div class="hero-main">
        <div class="hero-no${y.dexNo == null ? ' legend' : ''}">${esc(dexLabel(y))}</div>
        <div class="hero-name">${esc(y.name)}</div>
        <div class="hero-kana">${esc(orUnknown(y.kana))}</div>
        <div class="row-badges">${badges(y)}</div>
        ${isOtherVersion(y) ? `<div class="hero-warn">${esc(VERSION_LABELS[store.myVersion()])}では仲間にできない</div>` : ''}
      </div>
      <div class="hero-actions">${checkButton(y, true)}${pinButton(y, true)}</div>
    </div>
    ${secBasic(y)}
    ${secStats(y)}
    ${secObtain(y)}
    ${y.isLegend ? secLegend(y) : ''}
    ${secSimple('好きな物', y.favoriteFood)}
    ${secSkill(y)}
    ${secMoves(y)}
    ${secSoul(y)}
    ${secEvolution(y)}
    ${secFusion(y)}
    ${secSources(y)}
  `;
}

function secBasic(y) {
  return `<section class="sec"><h3>基本情報</h3><dl class="kv">
    <dt>名前</dt><dd>${esc(y.name)}</dd>
    <dt>読み</dt><dd>${esc(orUnknown(y.kana))}</dd>
    <dt>種族</dt><dd>${esc(y.tribe)}</dd>
    <dt>ランク</dt><dd>${esc(y.rank)}</dd>
    <dt>レジェンド</dt><dd>${y.isLegend ? 'レジェンド妖怪' : 'ちがう'}</dd>
    <dt>バージョン</dt><dd>${esc(versionsText(y.versions))}で仲間にできる</dd>
  </dl></section>`;
}

function secStats(y) {
  const s = y.stats || {};
  const rows = STAT_KEYS.map(k => {
    const v = s[k];
    const w = v == null ? 0 : Math.min(100, v / STAT_MAX * 100);
    return `<div class="stat"><span class="label">${STAT_LABELS[k]}</span><span class="val">${v == null ? `<span class="unknown">${UNKNOWN}</span>` : v}</span><span class="bar"><i style="width:${w}%"></i></span></div>`;
  }).join('');
  return `<section class="sec"><h3>ステータス<span class="sub">Lv${esc(s.level ?? 60)}時点の値（個体差・性格で少し変わる）</span></h3><div class="stats">${rows}</div></section>`;
}

function obtainItem(o, y) {
  const ver = `<span class="badge ver">${esc(versionsText(o.versions))}</span>`;
  const dist = y.isDistribution || ['qr', 'password'].includes(o.method) ? '<span class="badge dist">配信系</span>' : '';
  let body = '';
  if (o.method === 'wild') {
    const time = o.time && o.time.length ? o.time.map(t => TIME_LABELS[t] || t).join('・') : null;
    body = `<dl class="kv">
      <dt>エリア</dt><dd>${esc(orUnknown(o.area))}</dd>
      <dt>場所</dt><dd>${esc(o.spot || 'エリア内')}</dd>
      <dt>出現のしかた</dt><dd>${o.how ? esc(o.how) : `<span class="unknown">${UNKNOWN}</span>`}</dd>
      <dt>時代</dt><dd>${o.era ? esc(ERA_LABELS[o.era] || o.era) : `<span class="unknown">${UNKNOWN}</span>`}</dd>
      <dt>時間帯</dt><dd>${time ? esc(time) : `<span class="unknown">${UNKNOWN}</span>`}</dd>
      <dt>天気など</dt><dd>${o.weather ? esc(o.weather) : `<span class="unknown">${UNKNOWN}</span>`}</dd>
    </dl>`;
  } else if (o.method === 'gasha') {
    const coins = (o.coins || []).map(c => {
      const rate = c.rate == null ? `出現確率は${UNKNOWN}` : `出現確率 ${c.rate}%`;
      const src = c.rateSource ? ` <a href="${esc(c.rateSource)}" target="_blank" rel="noopener">出典</a>` : '';
      return `<dt>${esc(c.coin)}</dt><dd>${esc(rate)}${src}</dd>`;
    }).join('');
    const era = o.era ? `<dt>時代</dt><dd>${esc(ERA_LABELS[o.era] || o.era)}</dd>` : '';
    body = `<dl class="kv">${coins || `<dt>コイン</dt><dd>${UNKNOWN}</dd>`}${era}</dl>`;
  } else if (o.area) {
    // ボス・QRコードなどで、戦う場所が決まっているもの（分かっている項目だけ出す）
    const time = o.time && o.time.length ? o.time.map(t => TIME_LABELS[t] || t).join('・') : null;
    body = `<dl class="kv">
      <dt>エリア</dt><dd>${esc(o.area)}</dd>
      ${o.spot ? `<dt>場所</dt><dd>${esc(o.spot)}</dd>` : ''}
      ${o.era ? `<dt>時代</dt><dd>${esc(ERA_LABELS[o.era] || o.era)}</dd>` : ''}
      ${time ? `<dt>時間帯</dt><dd>${esc(time)}</dd>` : ''}
    </dl>`;
  }
  const note = o.note ? `<div>${esc(o.note)}</div>` : '';
  return `<li><div class="head">${esc(METHOD_LABELS[o.method] || o.method)}${ver}${dist}</div>${body}${note}</li>`;
}

function secObtain(y) {
  const list = y.obtain || [];
  let lead = '';
  if (!list.some(o => o.method === 'wild')) {
    const how = [...new Set(list.map(o => METHOD_LABELS[o.method] || o.method))].join('・') || UNKNOWN;
    lead = `<p class="lead">野生では出現しない（${esc(how)}で入手）</p>`;
  }
  const items = list.length ? `<ul class="obtain">${list.map(o => obtainItem(o, y)).join('')}</ul>` : `<p class="unknown">${UNKNOWN}</p>`;
  return `<section class="sec"><h3>出現場所・入手方法</h3>${lead}${items}</section>`;
}

function secLegend(y) {
  const ids = y.legendUnlock || [];
  const n = store.caughtCount(ids);
  const body = ids.length ? `<ul class="links">${ids.map(id => yokaiLink(id)).join('')}</ul>` : `<p class="unknown">${UNKNOWN}</p>`;
  return `<section class="sec" data-legend><h3>レジェンド解除条件<span class="legend-count num">${n} / ${ids.length} 捕まえた</span></h3>${body}</section>`;
}

function secSimple(title, value) {
  return `<section class="sec"><h3>${esc(title)}</h3><div class="${value ? '' : 'unknown'}">${esc(orUnknown(value))}</div></section>`;
}

function secSkill(y) {
  const s = y.skill;
  if (!s || !s.name) return secSimple('スキル', null);
  return `<section class="sec"><h3>スキル</h3><div><b>${esc(s.name)}</b></div><div>${esc(orUnknown(s.effect))}</div></section>`;
}

function power(p, hits) {
  if (p == null) return null;
  return hits && hits > 1 ? `${p} × ${hits}` : String(p);
}

function secMoves(y) {
  const m = y.moves || {};
  const cell = (name, meta) => name
    ? `<td><span class="mv">${esc(name)}</span>${meta ? `<div class="meta">${meta}</div>` : ''}</td>`
    : `<td class="unknown">${UNKNOWN}</td>`;
  const a = m.attack || {}, t = m.technique || {}, ins = m.inspirit || {}, s = m.soultimate || {};
  const pw = (p, h) => `威力 ${esc(power(p, h) ?? UNKNOWN)}`;
  const sMeta = [s.power != null ? pw(s.power, s.hits) : null, s.effect ? esc(s.effect) : null].filter(Boolean).join('／') || `威力・効果 ${UNKNOWN}`;
  return `<section class="sec"><h3>技</h3><table class="moves">
    <tr><th>こうげき</th>${cell(a.name, pw(a.power, a.hits))}</tr>
    <tr><th>ようじゅつ</th>${cell(t.name, `${esc(t.element ? t.element + '属性' : '属性' + UNKNOWN)}／${pw(t.power)}`)}</tr>
    <tr><th>とりつく</th>${cell(ins.name, esc(orUnknown(ins.effect)))}</tr>
    <tr><th>ひっさつわざ</th>${cell(s.name, sMeta)}</tr>
  </table></section>`;
}

function secSoul(y) {
  const s = y.soul;
  if (!s || !s.name) return secSimple('魂の効果', null);
  return `<section class="sec"><h3>魂の効果</h3><div><b>${esc(s.name)}</b></div><div>${esc(orUnknown(s.effect))}</div></section>`;
}

function secEvolution(y) {
  const from = y.evolvesFrom || [], to = y.evolvesTo || [];
  const lv = e => e.level != null ? `Lv${e.level}で進化` : '必要レベル不明';
  const part = (label, list) => `<div class="sub-h">${label}</div>` +
    (list.length ? `<ul class="links">${list.map(e => yokaiLink(e.id, lv(e))).join('')}</ul>` : `<div>${NONE}</div>`);
  return `<section class="sec"><h3>進化</h3>${part('進化元', from)}${part('進化先', to)}</section>`;
}

function recipeHtml(r) {
  const chip = m => {
    if (m.type === 'item') return `<span class="chip item">${esc(m.name)}</span>`;
    const y = byId.get(m.id);
    if (!y) return `<span class="chip">未収録（${esc(m.id)}）</span>`;
    return `<a class="chip${store.isCaught(y.id) ? ' caught' : ''}" href="#/yokai/${esc(y.id)}">${esc(y.name)}</a>`;
  };
  return `<div class="recipe">${r.materials.map(chip).join('<span class="op">＋</span>')}<span class="op">→</span>${chip({ type: 'yokai', id: r.result })}</div>`;
}

function secFusion(y) {
  const into = recipes.filter(r => r.result === y.id);
  const uses = recipes.filter(r => r.materials.some(m => m.type === 'yokai' && m.id === y.id));
  const part = (label, list) => `<div class="sub-h">${label}</div>` + (list.length ? list.map(recipeHtml).join('') : `<div>${NONE}</div>`);
  return `<section class="sec"><h3>合成</h3>${part('この妖怪になる合成', into)}${part('この妖怪を素材に使う合成', uses)}</section>`;
}

function secSources(y) {
  const src = (y.sources || []).filter(Boolean);
  const list = src.length ? `<ul>${src.map(u => `<li><a href="${esc(u)}" target="_blank" rel="noopener">${esc(decodeURIComponentSafe(u))}</a></li>`).join('')}</ul>` : `<p class="unknown">${UNKNOWN}</p>`;
  const verify = y.verified ? '2つ以上の出典で確認ずみ' : 'まだ2つ以上の出典で照らし合わせていないデータです';
  return `<section class="sec"><details class="sources"><summary>出典（${src.length}件）</summary>${list}<p class="verify">${verify}</p></details></section>`;
}

function decodeURIComponentSafe(u) {
  try { return decodeURI(u); } catch { return u; }
}

// ---------- チェック ----------
document.addEventListener('click', e => {
  const btn = e.target.closest('[data-check]');
  if (!btn) return;
  e.preventDefault();
  const id = btn.dataset.check;
  const wasPinned = store.isPinned(id);
  const value = store.toggle(id);
  showUndo(id, value, wasPinned);
});

document.addEventListener('click', e => {
  const btn = e.target.closest('[data-pin]');
  if (!btn) return;
  e.preventDefault();
  store.togglePin(btn.dataset.pin);
});

function onCaughtChange({ id, value, pin, all: everything, saved }) {
  if (everything) {
    renderList(); // 全消去・その取り消しは一覧を作り直す
  } else {
    // 一覧・ピン留め・逆引きの同じ妖怪の行（行は消さずに印だけ変える）
    for (const row of document.querySelectorAll(`.row[data-id="${CSS.escape(id)}"]`)) {
      row.classList.toggle('caught', store.isCaught(id));
      row.querySelector('.check').setAttribute('aria-pressed', store.isCaught(id));
      row.querySelector('.pin').setAttribute('aria-pressed', store.isPinned(id));
    }
    if (!pin) { renderTribeCounts(); refreshBadges(); }
    renderPinned();
  }
  renderProgress();
  if (current === 'settings') $('settingsView').innerHTML = settingsPageHtml(all);
  if (current === 'lookup') refreshLookupCount();
  // 詳細を開いているなら描き直す（レジェンドの数・進化先の印も変わる）
  if (current === 'detail') {
    const m = location.hash.match(/^#\/yokai\/([\w-]+)/);
    if (m) renderDetail(m[1]);
  }
  if (!saved) toastMessage('端末に保存できませんでした（プライベートブラウズでは保存されません）');
}

// ---------- 取り消し（画面下に5秒） ----------
let toastTimer = null;
let undoAction = null; // 「元に戻す」で呼ぶ処理

// 捕まえたときに自動で外れたピンも、取り消しで元に戻す
function showUndo(id, value, wasPinned) {
  const y = byId.get(id);
  const text = `${y ? y.name : id} を${value ? '「捕まえた」にしました' : '「未入手」に戻しました'}${value && wasPinned ? '（ピンを外しました）' : ''}`;
  showUndoMessage(text, () => {
    store.setCaught(id, !value);
    if (value && wasPinned) store.setPinned(id, true);
  });
}

function showUndoMessage(text, action) {
  undoAction = action;
  $('toastText').textContent = text;
  $('toastUndo').hidden = false;
  openToast();
}

function toastMessage(text) {
  undoAction = null;
  $('toastText').textContent = text;
  $('toastUndo').hidden = true;
  openToast();
}

function openToast() {
  const t = $('toast');
  t.hidden = false;
  t.style.animation = 'none'; void t.offsetWidth; t.style.animation = '';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(closeToast, UNDO_SECONDS * 1000);
}

function closeToast() {
  $('toast').hidden = true;
  undoAction = null;
}

$('toastUndo').addEventListener('click', () => {
  if (!undoAction) return;
  const action = undoAction;
  closeToast();
  clearTimeout(toastTimer);
  action();
});

// ---------- 設定 ----------
$('settingsView').addEventListener('change', e => {
  if (e.target.name !== 'myVersion') return;
  const saved = store.setMyVersion(e.target.value);
  renderList(); // 薄く表示する妖怪が変わる
  if (!saved) toastMessage('端末に保存できませんでした（プライベートブラウズでは保存されません）');
});

$('settingsView').addEventListener('click', e => {
  if (!e.target.closest('#clearAll')) return;
  const n = store.caughtCount(all.map(y => y.id));
  $('confirmText').textContent = `捕まえた ${n} 体のチェックがすべて未入手に戻ります。`;
  $('confirmClear').returnValue = '';
  $('confirmClear').showModal();
});

$('confirmClear').addEventListener('close', () => {
  if ($('confirmClear').returnValue !== 'ok') return;
  const before = store.clearAll();
  showUndoMessage(`${before.length} 体のチェックを消しました`, () => store.restore(before));
});

main();
