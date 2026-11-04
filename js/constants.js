// 図鑑で使う決まった値（種族・ランク・バージョン・入手手段など）
// 自動チェック（validate.js）もここを使うので、値を増やすときはここだけ直す

// 妖怪大辞典の種族の並び順
export const TRIBES = ['イサマシ', 'フシギ', 'ゴーケツ', 'プリチー', 'ポカポカ', 'ウスラカゲ', 'ブキミー', 'ニョロロン', '怪魔'];

// バッジの色（ライト/ダーク共通で読める濃さ）
export const TRIBE_COLORS = {
  イサマシ: '#d9483b',
  フシギ: '#d4a017',
  ゴーケツ: '#8a5a2b',
  プリチー: '#e0679b',
  ポカポカ: '#3fa45b',
  ウスラカゲ: '#4d7fc4',
  ブキミー: '#7b5bb5',
  ニョロロン: '#21a3a8',
  怪魔: '#555b6e',
};

export const RANKS = ['E', 'D', 'C', 'B', 'A', 'S'];

export const VERSIONS = ['ganso', 'honke', 'shinuchi'];
export const VERSION_LABELS = { ganso: '元祖', honke: '本家', shinuchi: '真打' };

export const METHODS = ['wild', 'evolve', 'fusion', 'gasha', 'quest', 'boss', 'legend', 'qr', 'password', 'trade', 'other'];
export const METHOD_LABELS = {
  wild: '野生',
  evolve: '進化',
  fusion: '合成',
  gasha: 'ガシャ',
  quest: 'クエスト報酬',
  boss: 'ボス撃破後',
  legend: 'レジェンド封印解除',
  qr: 'QRコード',
  password: 'パスワード',
  trade: '通信交換',
  other: 'その他',
};

export const ERAS = ['present', 'past'];
export const ERA_LABELS = { present: '現代', past: '過去' };

export const TIMES = ['day', 'night'];
export const TIME_LABELS = { day: '昼', night: '夜' };

export const STAT_KEYS = ['hp', 'str', 'spr', 'def', 'spd'];
export const STAT_LABELS = { hp: 'HP', str: 'ちから', spr: 'ようりょく', def: 'まもり', spd: 'すばやさ' };

// 妖怪大辞典でボスにあてられている番号。ボスは友達にならないので収録せず、欠番とみなさない
// （出典: https://game8.jp/youkai-watch2/491365 と https://wikiwiki.jp/2ganso-honke/ボス で一致）
export const BOSS_DEX = { from: 388, to: 429 };

// 逆引きのエリアの並び（ここにないエリアは後ろに名前順で足す）
export const AREA_GROUPS = [
  ['現代の町', ['さくら住宅街', 'おつかい横丁', 'そよ風ヒルズ', 'さくら中央シティ', 'おおもり山', 'さくらEXツリー', '団々坂', 'ケマモト村', 'ナギサキ']],
  ['過去の町', ['桜町', '桜町地下水道']],
  ['ダンジョンなど', ['えんえんトンネル', 'きまぐれゲート', '連動ダンジョン', '妖魔界', 'アミダ極楽', 'ムゲン地獄']],
];

// 逆引きのコインの並び（名前の頭で決める。ないものは後ろに名前順）
export const COIN_ORDER = ['赤コイン', '黄色コイン', 'オレンジコイン', '桃コイン', '緑コイン', '青コイン', '紫コイン', '水色コイン',
  '5つ星コイン', 'スペシャルコイン', 'きらきらコイン', 'うきうきコイン', 'わくわくコイン', 'どきどきコイン', 'あやかしコイン',
  'エラベールコイン', '超エラベールコイン', 'ゲームコイン', 'ご当地コイン', '鬼ガシャ'];

// 網羅確認で必ず収録されているべき特殊入手の妖怪（仕様書の指定）
export const REQUIRED_SPECIAL = ['ツチノコパンダ', 'ワンダーニャン', 'ロボりゅーくん'];
