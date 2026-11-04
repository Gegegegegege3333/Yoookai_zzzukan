// 表示まわりの小さな道具

// HTML に埋め込む文字を無害にする
export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

// カタカナをひらがなにそろえる（名前検索でどちらでもヒットさせるため）
export function toHiragana(s) {
  return String(s ?? '').replace(/[ァ-ヶ]/g, c => String.fromCharCode(c.charCodeAt(0) - 0x60));
}

// 検索用の文字列（ひらがな・小文字・空白と「・」「.」なし）
// 「ざしきわらし怪」で「ざしきわらし・怪」、「uso」で「U.S.O.」が見つかるようにする
export function searchKey(s) {
  return toHiragana(String(s ?? '').normalize('NFKC').toLowerCase()).replace(/[\s・･.]+/g, '');
}

// 割合の表示（0% と 100% は整数、それ以外は小数1けた）
export function pctText(n, total) {
  const p = total ? n / total * 100 : 0;
  return (p === 0 || p === 100 ? p.toFixed(0) : p.toFixed(1)) + '%';
}

// 番号の表示（レジェンドで番号がないときは「レジェンド」）
export function dexLabel(y) {
  if (y.dexNo == null) return y.isLegend ? 'レジェンド' : '番号不明';
  return 'No.' + String(y.dexNo).padStart(3, '0');
}

// 空欄の代わりに出す文字
export const UNKNOWN = '不明';
export const NONE = 'なし';

export function orUnknown(v) {
  return v == null || v === '' ? UNKNOWN : v;
}
