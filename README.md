# 妖怪ウォッチ2 図鑑チェッカー

妖怪ウォッチ2（元祖・本家・真打）で仲間になる妖怪を調べて、捕まえた妖怪をチェックするスマホ向けWebアプリ。
非公式の個人用ファンツールで、株式会社レベルファイブとは関係ありません。公式のイラスト・アイコン・ロゴは使っていません。
データは下の「出典と使いみち」の攻略サイトから事実情報（名前・数値など）だけを集め、照らし合わせて作っています。

公開しているページ: https://mottoknt-bit.github.io/Yoookai_zzzukan/ （スマホで開いて「ホーム画面に追加」すると、電波がなくても使える）

## 動かし方（手元で直すとき）

ビルドは不要（素の HTML / CSS / JavaScript）。`data/yokai.json` を読み込むため、ファイルを直接開くのではなく、ローカルサーバーで開く。

```bash
java -m jdk.httpserver -d . -p 8765 -b 127.0.0.1
```

ブラウザで http://127.0.0.1:8765/ を開く。
スマホから同じ Wi-Fi で見るときは `-b 0.0.0.0` にして、PC の IP アドレス（例 http://192.168.x.x:8765/）を開く。

## データの自動チェック

- ブラウザ: http://127.0.0.1:8765/validate.html
- Node.js がある場合: `npm run validate`（サンプルのうちは `npm run validate -- --sample`）

## データの作り直し

`data/yokai.json` は手で書かず、攻略サイトから集めたページを突き合わせて作る（Java 22 以上が必要）。

```bash
java scripts/fetch/Fetch.java ../yw2-cache
```

```bash
java scripts/build/Build.java ../yw2-cache
```

- 1つめで攻略サイトのページをキャッシュのフォルダ（この例では `../yw2-cache`）に保存する。保存済みのページは取り直さない。初回は 30 分ほどかかる（サイトに負担をかけないよう間隔を空けるため）
- 2つめで `data/yokai.json` と `docs/data-report.md`（件数・食い違い・1つの出典にしかない項目。公開はしない）を書き出す
- 自動で読み取れないこと（限定バージョン・配信系・レジェンドの解除条件・特殊な入手方法・表記ゆれ）は `scripts/data/overrides.json` に手で書く

### 出典と使いみち

| 出典 | 使った項目 |
|---|---|
| [gamepedia（攻略大百科）](https://gamepedia.jp/youkai-yw2/monsters) | 骨格（番号・名前・読み・種族・ランク）、Lv60 ステータス、技・スキル、好物、出現場所、進化・合成、ガシャのコイン |
| [enjoi 妖怪ウォッチ2攻略](https://enjoi7.sakura.ne.jp/youkai-wach2/youkai_list.html) | 名前・ランク・好物・出現場所（バージョン限定の印）、進化・合成、ガシャの現代/過去、限定妖怪、怪魔、レジェンド |
| [元祖・本家 Wiki*](https://wikiwiki.jp/2ganso-honke/) | 名前・ランク・種族、レジェンドの解除条件 |
| [Game8](https://game8.jp/youkai-watch2/491365) | 名前・ランク・好物、魂の効果、合成レシピ |
| [note「妖怪ウォッチ2のガチャシステムとテーブル」](https://note.com/isakaakasi/n/n10bcbf233156) | ガシャのコインごとの出現確率（`scripts/data/gasha-rates.json`）。gamepedia にないコインの組み合わせと、色コインの現代/過去もこちらに合わせた。鬼ガシャ・エラベール・ゲームコインは載っていないので「不明」 |

名前・ランク・好物は出典の多数決で決め、割れたものは `conflicts` に残す（`resolved: false` がユーザーに確認したいもの）。

## ファイル

| パス | 中身 |
|---|---|
| `data/yokai.json` | 妖怪データ（仕様書「データ構造」の形式） |
| `js/constants.js` | 種族・ランク・バージョン・入手手段などの決まった値 |
| `js/store.js` | チェック・ピン留め・自分のバージョンの保存（localStorage） |
| `js/filter.js` | 一覧の検索・絞り込み（条件は sessionStorage に残す） |
| `js/lookup.js` | 逆引き画面（場所から・ガシャのコインから） |
| `js/pages.js` | 進捗画面・設定画面 |
| `js/app.js` | 画面の切り替え・一覧・ピン留め・詳細・チェック・取り消し・進化/合成で入手可の印 |
| `js/validate.js` | データの自動チェック（`scripts/validate.mjs` と `validate.html` から使う） |
| `css/app.css` | 見た目（ライト / ダーク） |
| `scripts/fetch/Fetch.java` | 攻略サイトのページを集める |
| `scripts/build/Build.java`, `Json.java` | 集めたページから `data/yokai.json` とレポートを作る |
| `scripts/data/overrides.json` | 手で書いた補足（限定バージョン・配信系・レジェンド・特殊な入手方法・表記ゆれ） |
| `manifest.webmanifest`, `sw.js`, `icons/` | ホーム画面への追加と、電波がなくても開けるしくみ（PWA） |
| `scripts/icons/MakeIcons.java` | アイコンの画像を作る（文字と色だけ） |

仕様書（`docs/spec.md`）とデータ収集レポート（`docs/data-report.md`）は手元だけに置き、公開しない（`.gitignore`）。

## データ形式の補足（仕様書からの追加）

- `moves.attack` と `moves.soultimate` に `hits`（何回当たるか。例: 威力 20 × 3 なら `power: 20, hits: 3`）を足した
- 合成レシピ（`fusion`）は、結果の妖怪と、素材の妖怪の全員に同じものを書く（詳細の「この妖怪を素材に使う合成」に使う）
- 分からない値は `null`。画面では「不明」と出る
- `id` は `y` ＋ 図鑑番号3けた（番号のないレジェンドは `yL01` から）
- `conflicts` の各要素は `field`（項目）・`values`（出典ごとの値）・`chosen`（採用した値）・`resolved`（多数決で決まったか）・`note`
- 入手方法（`obtain`）の `area`・`spot`・`era`・`time` は、野生以外（ボス・QRコードなど）でも戦う場所が決まっていれば入れる
- 野生の `area` は逆引きでまとめられるよう表記をそろえる（「アミダの極楽」→「アミダ極楽」、「ムゲン地獄1〜3階層」→ エリア「ムゲン地獄」・場所「1〜3階層」など。`Build.java` の `AREA_ALIAS`）。時代は `era` で持ち、エリア名には入れない
- 妖怪大辞典の No.388〜429 はボス（友達にならない）なので収録しない。自動チェックでも欠番にしない（`js/constants.js` の `BOSS_DEX`）

## 進み具合

1. ✅ データ形式の確定と、サンプルデータ（13体）
2. ✅ 一覧・詳細・チェック機能（取り消しボタンを含む）
3. ✅ 全妖怪のデータ収集（398体）と自動チェック。食い違いは仮の値のまま（ユーザー了承済み）
4. ✅ 絞り込み・進捗画面・設定画面
5. ✅ 便利機能（場所とコインの逆引き、進化・合成で入手可能の表示、ピン留め）
6. ✅ PWA化と公開（GitHub Pages。`main` に push すると1〜2分で反映される）
