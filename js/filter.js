// 一覧の検索・絞り込み
// 条件は sessionStorage に残す（詳細から戻ったときや、スマホでタブが読み直されたときも消えない）
import { TRIBES, RANKS, VERSIONS, VERSION_LABELS, METHODS, METHOD_LABELS } from './constants.js';
import { esc, searchKey } from './util.js';

const KEY = 'yw2zukan.filter.v1';
const LISTS = ['tribes', 'ranks', 'versions', 'methods'];
export const EMPTY = { q: '', caught: 'all', tribes: [], ranks: [], versions: [], methods: [], dist: 'all', open: false };

export function loadFilter() {
  try {
    const f = JSON.parse(sessionStorage.getItem(KEY));
    if (f && typeof f === 'object') {
      const out = { ...EMPTY, ...f };
      for (const k of LISTS) if (!Array.isArray(out[k])) out[k] = [];
      return out;
    }
  } catch { /* 読めなければ条件なしから */ }
  return { ...EMPTY, tribes: [], ranks: [], versions: [], methods: [] };
}

export function saveFilter(f) {
  try { sessionStorage.setItem(KEY, JSON.stringify(f)); } catch { /* 保存できなくても絞り込みは動く */ }
}

// 絞り込みパネルで選んでいる条件の数（名前検索は数えない）
export function activeCount(f) {
  return LISTS.filter(k => f[k].length).length + (f.caught !== 'all') + (f.dist !== 'all');
}

export function isFiltering(f) {
  return activeCount(f) > 0 || searchKey(f.q) !== '';
}

const keys = new Map(); // id → 名前と読みの検索用文字列
function keysOf(y) {
  if (!keys.has(y.id)) keys.set(y.id, [searchKey(y.name), searchKey(y.kana)]);
  return keys.get(y.id);
}

// 種族・ランクなどは同じ項目の中では「どれか」、項目どうしは「すべて」を満たすもの
// isCaught(id): 捕まえたか、canGet(y): 進化・合成で入手できるか
export function matches(y, f, { isCaught, canGet }) {
  const q = searchKey(f.q);
  if (q) {
    const byName = keysOf(y).some(k => k.includes(q));
    const byNo = /^\d+$/.test(q) && y.dexNo === Number(q);
    if (!byName && !byNo) return false;
  }
  if (f.caught === 'yes' && !isCaught(y.id)) return false;
  if (f.caught === 'no' && isCaught(y.id)) return false;
  if (f.caught === 'can' && !canGet(y)) return false;
  if (f.tribes.length && !f.tribes.includes(y.tribe)) return false;
  if (f.ranks.length && !f.ranks.includes(y.rank)) return false;
  if (f.versions.length && !y.versions.some(v => f.versions.includes(v))) return false;
  if (f.methods.length && !(y.obtain || []).some(o => f.methods.includes(o.method))) return false;
  if (f.dist === 'only' && !y.isDistribution) return false;
  if (f.dist === 'exclude' && y.isDistribution) return false;
  return true;
}

// 絞り込みパネルの中身。methods はデータに出てくる入手手段だけ
export function panelHtml(f, methods) {
  const multi = (group, values, label = v => v) => values.map(v =>
    `<button type="button" class="opt" data-group="${group}" data-value="${esc(v)}" aria-pressed="${f[group].includes(v)}">${esc(label(v))}</button>`).join('');
  const single = (group, options) => options.map(([v, text]) =>
    `<button type="button" class="opt" data-single="${group}" data-value="${v}" aria-pressed="${f[group] === v}">${esc(text)}</button>`).join('');
  const block = (title, body, note = '') => `<fieldset class="fgroup"><legend>${title}${note ? `<span class="fnote">${note}</span>` : ''}</legend><div class="opts">${body}</div></fieldset>`;
  return block('チェック', single('caught', [['all', 'すべて'], ['no', '未入手'], ['yes', '捕まえた'], ['can', '進化・合成で入手可']])) +
    block('種族', multi('tribes', TRIBES)) +
    block('ランク', multi('ranks', RANKS)) +
    block('バージョン', multi('versions', VERSIONS, v => VERSION_LABELS[v]), 'そのバージョンで仲間にできる') +
    block('入手手段', multi('methods', METHODS.filter(m => methods.has(m)), m => METHOD_LABELS[m])) +
    block('配信系', single('dist', [['all', 'すべて'], ['only', '配信系だけ'], ['exclude', '配信系を除く']])) +
    `<button type="button" class="btn reset" data-filter-reset>条件をすべて解除</button>`;
}

// パネルのボタンが押されたときに条件を書き換える。書き換えたら true
export function applyClick(f, btn) {
  if (btn.dataset.group) {
    const list = f[btn.dataset.group], v = btn.dataset.value;
    const i = list.indexOf(v);
    if (i >= 0) list.splice(i, 1); else list.push(v);
    return true;
  }
  if (btn.dataset.single) {
    f[btn.dataset.single] = btn.dataset.value;
    return true;
  }
  return false;
}

export function resetFilter(f) {
  Object.assign(f, { ...EMPTY, tribes: [], ranks: [], versions: [], methods: [], open: f.open });
}
