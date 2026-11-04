// 進捗画面と設定画面
import { TRIBES, TRIBE_COLORS, RANKS, VERSIONS, VERSION_LABELS } from './constants.js';
import { esc, pctText } from './util.js';
import { store } from './store.js';

// ---------- 進捗 ----------
function progRow(label, list, color) {
  const n = store.caughtCount(list.map(y => y.id));
  const total = list.length;
  const done = total > 0 && n === total;
  const w = total ? n / total * 100 : 0;
  return `<div class="prow${done ? ' done' : ''}"${color ? ` style="--c:${color}"` : ''}>` +
    `<span class="pl">${esc(label)}</span><span class="pv num"><b>${n}</b> / ${total}</span><span class="pp num">${pctText(n, total)}</span>` +
    `<span class="pbar"><i style="width:${w}%"></i></span></div>`;
}

export function progressPageHtml(all) {
  const ids = all.map(y => y.id);
  const n = store.caughtCount(ids);
  const w = all.length ? n / all.length * 100 : 0;
  const only = v => all.filter(y => y.versions.length === 1 && y.versions[0] === v);
  return `<h2 class="page-title">進捗</h2>
    <section class="sec ptotal">
      <div class="ptotal-num num"><b>${n}</b> / ${all.length}<span class="pct">${pctText(n, all.length)}</span></div>
      <div class="progress-bar big"><div class="progress-fill" style="width:${w}%"></div></div>
      <p class="fine">全バージョンの全妖怪（配信系もふくむ）を分母にしています。自分のバージョンを選んでも変わりません。</p>
    </section>
    <section class="sec"><h3>種族別</h3>${TRIBES.map(t => progRow(t, all.filter(y => y.tribe === t), TRIBE_COLORS[t])).join('')}</section>
    <section class="sec"><h3>ランク別</h3>${RANKS.map(r => progRow(r + 'ランク', all.filter(y => y.rank === r))).join('')}</section>
    <section class="sec"><h3>バージョン別<span class="sub">そのバージョンで仲間にできる妖怪</span></h3>
      ${VERSIONS.map(v => progRow(VERSION_LABELS[v], all.filter(y => y.versions.includes(v)))).join('')}</section>
    <section class="sec"><h3>バージョン限定<span class="sub">そのバージョンでしか仲間にできない妖怪</span></h3>
      ${VERSIONS.map(v => progRow(VERSION_LABELS[v] + 'のみ', only(v))).join('')}</section>
    <section class="sec"><h3>そのほか</h3>
      ${progRow('レジェンド', all.filter(y => y.isLegend))}${progRow('配信系', all.filter(y => y.isDistribution))}</section>`;
}

// ---------- 設定 ----------
export function settingsPageHtml(all) {
  const my = store.myVersion();
  const radio = (v, label) => `<label class="opt radio"><input type="radio" name="myVersion" value="${v}"${(my || '') === v ? ' checked' : ''}><span>${esc(label)}</span></label>`;
  const n = store.caughtCount(all.map(y => y.id));
  return `<h2 class="page-title">設定</h2>
    <section class="sec">
      <h3>自分のバージョン</h3>
      <p class="fine">今遊んでいるバージョンを選ぶと、そのバージョンで仲間にできない妖怪を一覧で薄く表示します。進捗の数え方は変わりません。</p>
      <div class="opts" role="radiogroup" aria-label="自分のバージョン">
        ${radio('', '選ばない')}${VERSIONS.map(v => radio(v, VERSION_LABELS[v])).join('')}
      </div>
    </section>
    <section class="sec">
      <h3>チェックの全消去</h3>
      <p class="fine">捕まえた <b class="num">${n}</b> 体のチェックをすべて消します。消す前に確認します。</p>
      <button type="button" class="btn danger" id="clearAll"${n ? '' : ' disabled'}>チェックをすべて消す</button>
    </section>
    <section class="sec">
      <h3>ホーム画面に追加する</h3>
      <p class="fine">ホーム画面に追加すると、アプリのようにアイコンから開けます。一度開いたあとは、電波がなくても使えます。</p>
      <p class="fine"><b>iPhone（Safari）</b>：下の共有ボタン → 「ホーム画面に追加」<br><b>Android（Chrome）</b>：右上の︙ → 「ホーム画面に追加」または「アプリをインストール」</p>
    </section>
    <section class="sec">
      <h3>保存について</h3>
      <p class="fine">チェックと設定は、この端末のこのブラウザの中だけに保存しています。<b>ブラウザの履歴やサイトデータを消すと、チェックも消えます。</b>ほかの端末やブラウザとは共有されません。</p>
    </section>
    <section class="sec">
      <h3>このアプリについて</h3>
      <p class="fine">妖怪ウォッチ2（元祖・本家・真打）の非公式の個人用ファンツールです。収録は仲間になる妖怪 ${all.length} 体。データは攻略サイトの事実情報を照らし合わせて作っていて、妖怪ごとの出典は詳細画面の下にあります。</p>
    </section>`;
}
