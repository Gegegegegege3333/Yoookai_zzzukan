// 端末内（localStorage）に保存するもの: 捕まえた妖怪の id の一覧、ピン留めした妖怪の id の一覧、自分のバージョン
// 妖怪データを更新・追加しても、id が同じならチェックは消えない

const CAUGHT_KEY = 'yw2zukan.caught.v1';
const PINS_KEY = 'yw2zukan.pins.v1';
const MY_VERSION_KEY = 'yw2zukan.myVersion.v1';

function readList(key) {
  try {
    const raw = localStorage.getItem(key);
    const list = raw ? JSON.parse(raw) : [];
    return Array.isArray(list) ? list.filter(x => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

function writeList(key, set) {
  try {
    localStorage.setItem(key, JSON.stringify([...set]));
    return true;
  } catch {
    return false; // プライベートブラウズなどで保存できなくても、画面はそのまま動かす
  }
}

function readText(key) {
  try { return localStorage.getItem(key); } catch { return null; }
}

function writeText(key, value) {
  try {
    if (value == null) localStorage.removeItem(key); else localStorage.setItem(key, value);
    return true;
  } catch {
    return false;
  }
}

const caught = new Set(readList(CAUGHT_KEY));
const pins = new Set(readList(PINS_KEY)); // ピン留めした順
let myVersion = readText(MY_VERSION_KEY);
const listeners = new Set();

function changed(info) {
  for (const fn of listeners) fn(info);
}

export const store = {
  isCaught: id => caught.has(id),
  caughtCount: ids => ids.reduce((n, id) => n + (caught.has(id) ? 1 : 0), 0),

  // 捕まえたらピンは自動で外す
  setCaught(id, value) {
    if (value) caught.add(id); else caught.delete(id);
    let saved = writeList(CAUGHT_KEY, caught);
    if (value && pins.delete(id)) saved = writeList(PINS_KEY, pins) && saved;
    changed({ id, value, saved });
  },

  toggle(id) {
    const value = !caught.has(id);
    this.setCaught(id, value);
    return value;
  },

  // チェックをすべて消す。消す前の id の一覧を返す（取り消し用）
  clearAll() {
    const before = [...caught];
    caught.clear();
    changed({ all: true, saved: writeList(CAUGHT_KEY, caught) });
    return before;
  },

  restore(ids) {
    for (const id of ids) { caught.add(id); pins.delete(id); }
    changed({ all: true, saved: writeList(CAUGHT_KEY, caught) && writeList(PINS_KEY, pins) });
  },

  // ピン留め
  isPinned: id => pins.has(id),
  pinned: () => [...pins],
  setPinned(id, value) {
    if (value) pins.add(id); else pins.delete(id);
    changed({ id, pin: true, value, saved: writeList(PINS_KEY, pins) });
  },
  togglePin(id) {
    const value = !pins.has(id);
    this.setPinned(id, value);
    return value;
  },

  onChange(fn) { listeners.add(fn); },

  // 自分のバージョン（'ganso' / 'honke' / 'shinuchi'。選んでいなければ null）
  myVersion: () => myVersion,
  setMyVersion(v) {
    myVersion = v || null;
    return writeText(MY_VERSION_KEY, myVersion);
  },
};
