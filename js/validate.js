// 妖怪データの自動チェック（仕様書「データ収集手順と検証」の5）
// Node（scripts/validate.mjs）とブラウザ（validate.html）の両方から使う
import { TRIBES, RANKS, VERSIONS, METHODS, ERAS, TIMES, STAT_KEYS, REQUIRED_SPECIAL, BOSS_DEX } from './constants.js';

// sample: サンプルデータのとき true。欠番と特殊入手妖怪の未収録を「注意」に下げる
export function validate(data, { sample = false } = {}) {
  const errors = [];
  const warnings = [];
  const err = (y, msg) => errors.push(`${label(y)}: ${msg}`);
  const warn = (y, msg) => warnings.push(`${label(y)}: ${msg}`);

  if (!Array.isArray(data)) return { errors: ['データが配列ではありません'], warnings, count: 0 };

  const byId = new Map();
  for (const y of data) {
    if (!y.id || !/^y(\d{3}|L\d{2})$/.test(y.id)) err(y, `id の形式がちがう（y000 か yL00）: ${y.id}`);
    if (byId.has(y.id)) err(y, `id が重複: ${y.id}`);
    byId.set(y.id, y);
  }

  // ---- 1体ごとの項目 ----
  for (const y of data) {
    for (const k of ['name', 'kana', 'tribe', 'rank']) if (!y[k]) err(y, `${k} が空欄`);
    if (y.kana && !/^[ぁ-ゖー・\s]+$/.test(y.kana)) warn(y, `kana がひらがなだけではない: ${y.kana}`);
    if (y.tribe && !TRIBES.includes(y.tribe)) err(y, `決められていない種族: ${y.tribe}`);
    if (y.rank && !RANKS.includes(y.rank)) err(y, `決められていないランク: ${y.rank}`);
    checkVersions(y, y.versions, '仲間にできるバージョン', err);
    if (typeof y.isLegend !== 'boolean') err(y, 'isLegend が true/false ではない');
    if (typeof y.isDistribution !== 'boolean') err(y, 'isDistribution が true/false ではない');
    if (y.dexNo == null && !y.isLegend) err(y, '図鑑番号がない（番号なしはレジェンドだけ）');
    if (y.dexNo != null && !(Number.isInteger(y.dexNo) && y.dexNo > 0)) err(y, `図鑑番号が正の整数ではない: ${y.dexNo}`);
    if (!Number.isFinite(y.sortOrder)) err(y, 'sortOrder がない');

    // ステータス（Lv60）
    const s = y.stats;
    if (!s) err(y, 'stats がない');
    else {
      if (s.level !== 60) err(y, `stats.level が 60 ではない: ${s.level}`);
      for (const k of STAT_KEYS) {
        if (s[k] == null) warn(y, `ステータス ${k} が不明`);
        else if (!(Number.isInteger(s[k]) && s[k] > 0)) err(y, `ステータス ${k} が正の整数ではない: ${s[k]}`);
      }
    }

    // 入手方法
    if (!Array.isArray(y.obtain) || y.obtain.length === 0) err(y, '入手方法（obtain）が空');
    for (const [i, o] of (y.obtain || []).entries()) {
      const where = `入手方法${i + 1}`;
      if (!METHODS.includes(o.method)) err(y, `${where}: 決められていない method: ${o.method}`);
      checkVersions(y, o.versions, where, err);
      for (const v of o.versions || []) if (y.versions && !y.versions.includes(v)) err(y, `${where}: 仲間にできるバージョンにない ${v} が入っている`);
      if (o.method === 'wild') {
        if (!o.area) err(y, `${where}: 野生なのにエリアが空`);
        if (o.era != null && !ERAS.includes(o.era)) err(y, `${where}: 決められていない時代: ${o.era}`);
        if (o.time != null && (!Array.isArray(o.time) || o.time.some(t => !TIMES.includes(t)))) err(y, `${where}: 決められていない時間帯: ${o.time}`);
      }
      if (o.method === 'gasha') {
        if (!Array.isArray(o.coins) || o.coins.length === 0) err(y, `${where}: ガシャなのにコインがない`);
        for (const c of o.coins || []) {
          if (!c.coin) err(y, `${where}: コイン名が空`);
          if (c.rate != null && !c.rateSource) err(y, `${where}: ${c.coin} の確率に出典がない`);
        }
      }
      if (['qr', 'password'].includes(o.method) && !y.isDistribution) warn(y, `${where}: ${o.method} なのに isDistribution が false`);
    }
    for (const v of y.versions || []) {
      if (!(y.obtain || []).some(o => (o.versions || []).includes(v))) err(y, `${v} で仲間にできるのに、${v} の入手方法がない`);
    }

    // レジェンド
    if (y.isLegend && (!Array.isArray(y.legendUnlock) || y.legendUnlock.length === 0)) err(y, 'レジェンドなのに封印解除の妖怪がない');
    if (!y.isLegend && y.legendUnlock && y.legendUnlock.length) err(y, 'レジェンドではないのに legendUnlock がある');
    for (const id of y.legendUnlock || []) if (!byId.has(id)) err(y, `封印解除の妖怪 ${id} が収録されていない`);

    // 技・スキル・魂・好物
    if (!y.skill || !y.skill.name) warn(y, 'スキルが不明');
    const m = y.moves || {};
    for (const k of ['attack', 'technique', 'inspirit', 'soultimate']) if (!m[k] || !m[k].name) warn(y, `技 ${k} が不明`);
    if (!y.soul || !y.soul.name) warn(y, '魂が不明');
    if (!y.favoriteFood) warn(y, '好きな物が不明');

    // 出典・照合
    if (!Array.isArray(y.sources) || y.sources.filter(Boolean).length === 0) err(y, '出典がない');
    if (y.verified && (y.sources || []).filter(Boolean).length < 2) err(y, 'verified なのに出典が2つ未満');
    const open = (y.conflicts || []).filter(c => !c.resolved);
    if (open.length) warn(y, `出典の食い違いが ${open.length} 件ある（ユーザーに確認）`);
  }

  // ---- 進化の向き ----
  for (const y of data) {
    for (const e of y.evolvesTo || []) {
      const t = byId.get(e.id);
      if (!t) { err(y, `進化先 ${e.id} が収録されていない`); continue; }
      const back = (t.evolvesFrom || []).find(f => f.id === y.id);
      if (!back) err(y, `${t.name} に進化するのに、${t.name} の進化元に ${y.name} がない`);
      else if (back.level !== e.level) err(y, `${t.name} への進化レベルが双方でちがう（${e.level} と ${back.level}）`);
    }
    for (const e of y.evolvesFrom || []) {
      const f = byId.get(e.id);
      if (!f) { err(y, `進化元 ${e.id} が収録されていない`); continue; }
      if (!(f.evolvesTo || []).some(t => t.id === y.id)) err(y, `進化元 ${f.name} の進化先に ${y.name} がない`);
    }
    if ((y.evolvesFrom || []).length && !(y.obtain || []).some(o => o.method === 'evolve')) warn(y, '進化元があるのに入手方法に「進化」がない');
  }

  // ---- 合成（結果と素材の妖怪の両方に同じレシピがあるか） ----
  const recipeKey = r => r.result + '<=' + (r.materials || []).map(m => m.type === 'yokai' ? m.id : 'item:' + m.name).sort().join('+');
  for (const y of data) {
    for (const r of y.fusion || []) {
      if (!Array.isArray(r.materials) || r.materials.length < 2) err(y, '合成の素材が2つ未満');
      const involved = [r.result, ...(r.materials || []).filter(m => m.type === 'yokai').map(m => m.id)];
      if (!involved.includes(y.id)) err(y, 'この妖怪が関わらない合成レシピが入っている');
      for (const m of r.materials || []) {
        if (m.type === 'yokai' && !byId.has(m.id)) err(y, `合成素材 ${m.id} が収録されていない`);
        if (m.type === 'item' && !m.name) err(y, '合成素材のアイテム名が空');
        if (!['yokai', 'item'].includes(m.type)) err(y, `合成素材の type がちがう: ${m.type}`);
      }
      if (!byId.has(r.result)) { err(y, `合成結果 ${r.result} が収録されていない`); continue; }
      for (const id of involved) {
        const o = byId.get(id);
        if (o && !(o.fusion || []).some(x => recipeKey(x) === recipeKey(r))) err(y, `同じ合成レシピが ${o.name} 側にない`);
      }
    }
    if ((y.fusion || []).some(r => r.result === y.id) && !(y.obtain || []).some(o => o.method === 'fusion')) warn(y, '合成レシピがあるのに入手方法に「合成」がない');
  }

  // ---- 番号の重複・欠番 ----
  const nums = data.filter(y => y.dexNo != null).map(y => y.dexNo).sort((a, b) => a - b);
  const dup = nums.filter((n, i) => nums[i - 1] === n);
  for (const n of new Set(dup)) errors.push(`図鑑番号 ${n} が重複`);
  if (nums.length) {
    const have = new Set(nums);
    const missing = [];
    for (let n = 1; n <= nums[nums.length - 1]; n++) if (!have.has(n) && !(n >= BOSS_DEX.from && n <= BOSS_DEX.to)) missing.push(n);
    for (const n of nums) if (n >= BOSS_DEX.from && n <= BOSS_DEX.to) errors.push(`図鑑番号 ${n} はボスの番号（友達にならない）`);
    if (missing.length) (sample ? warnings : errors).push(`欠番 ${missing.length} 件: ${rangeText(missing)}`);
  }

  // ---- 並び順（種族ごと・番号順、レジェンドは各種族の末尾） ----
  const sorted = data.slice().sort((a, b) => a.sortOrder - b.sortOrder);
  const orders = sorted.map(y => y.sortOrder);
  if (new Set(orders).size !== orders.length) errors.push('sortOrder が重複');
  const expected = data.slice().sort((a, b) =>
    TRIBES.indexOf(a.tribe) - TRIBES.indexOf(b.tribe) ||
    Number(a.isLegend) - Number(b.isLegend) ||
    (a.dexNo ?? Infinity) - (b.dexNo ?? Infinity));
  const bad = expected.findIndex((y, i) => y !== sorted[i]);
  if (bad >= 0) errors.push(`並び順がちがう: ${bad + 1}番目は ${expected[bad].name} のはずが ${sorted[bad].name}`);

  // ---- 特殊入手の妖怪 ----
  const names = new Set(data.map(y => y.name));
  for (const n of REQUIRED_SPECIAL) if (!names.has(n)) (sample ? warnings : errors).push(`特殊入手の妖怪「${n}」が収録されていない`);

  return { errors, warnings, count: data.length };
}

function checkVersions(y, vs, where, err) {
  if (!Array.isArray(vs) || vs.length === 0) { err(y, `${where}: versions が空`); return; }
  for (const v of vs) if (!VERSIONS.includes(v)) err(y, `${where}: 決められていないバージョン: ${v}`);
  if (new Set(vs).size !== vs.length) err(y, `${where}: versions が重複`);
}

function label(y) {
  return y && y.name ? `${y.name}（${y.id}）` : `(${y && y.id})`;
}

function rangeText(list) {
  const parts = [];
  for (let i = 0; i < list.length; i++) {
    let j = i;
    while (j + 1 < list.length && list[j + 1] === list[j] + 1) j++;
    parts.push(i === j ? `${list[i]}` : `${list[i]}〜${list[j]}`);
    i = j;
  }
  return parts.length > 12 ? parts.slice(0, 12).join(', ') + ' ほか' : parts.join(', ');
}
