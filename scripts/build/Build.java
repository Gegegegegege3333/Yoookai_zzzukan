import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

// 攻略サイトから保存したデータ（キャッシュ）を突き合わせて data/yokai.json と docs/data-report.md を作る。
// 使い方: java scripts/build/Build.java <キャッシュのフォルダ>
//   キャッシュの中身は scripts/fetch のツールで集める（README を参照）。
public class Build {
  static final List<String> TRIBES = List.of("イサマシ", "フシギ", "ゴーケツ", "プリチー", "ポカポカ", "ウスラカゲ", "ブキミー", "ニョロロン", "怪魔");
  static final List<String> ALL_VERSIONS = List.of("ganso", "honke", "shinuchi");
  static final Map<String, String> VERSION_JA = Map.of("ganso", "元祖", "honke", "本家", "shinuchi", "真打");

  static final String URL_GP_MONSTER = "https://gamepedia.jp/youkai-yw2/monsters/";
  static final String URL_ENJOI_LIST = "https://enjoi7.sakura.ne.jp/youkai-wach2/youkai_list.html";
  static final String URL_ENJOI_SHINKA = "https://enjoi.sakura.ne.jp/youkai-watch2/youkai_shinka.html";
  static final String URL_ENJOI_GOUSEI = "https://enjoi.sakura.ne.jp/youkai-watch2/youkai_shinka_gousei.html";
  static final String URL_ENJOI_GASHA = "https://enjoi.sakura.ne.jp/youkai-watch2/gasya_2.html";
  static final String URL_ENJOI_KAIMA = "https://enjoi7.sakura.ne.jp/youkai-wach2/youkai_kaima.html";
  static final String URL_ENJOI_LEGEND = "https://enjoi7.sakura.ne.jp/youkai-wach2/youkai_legend.html";
  static final String URL_WIKI = "https://wikiwiki.jp/2ganso-honke/";
  static final String URL_GAME8_SOUL = "https://game8.jp/youkai-watch2/504902";
  static final String URL_GAME8_LIST = "https://game8.jp/youkai-watch2/491365";
  static final String URL_GAME8_EVO = "https://game8.jp/youkai-watch2/504828";

  static Path RAW, ROOT;

  // ---- 1体分の作業用データ ----
  static class Y {
    int no;
    String id, name, kana, tribe, rank;
    long wpId;
    Map<String, Object> gp;          // gamepedia の meta
    String gpTribe, gpRank;
    List<String> gpFeatures = new ArrayList<>();
    String[] wiki, enjoi, g8;        // 番号, 名前, ランク, ...（Game8 は 番号, ランク, 名前, 好物）
    String kaimaRank;
    PageInfo page;
    List<String> versions = new ArrayList<>(ALL_VERSIONS);
    boolean legend, distribution, versionUnsure;
    List<Map<String, Object>> obtain = new ArrayList<>();
    List<String> legendUnlock = new ArrayList<>();
    List<Map<String, Object>> evolvesTo = new ArrayList<>(), evolvesFrom = new ArrayList<>();
    List<Map<String, Object>> fusion = new ArrayList<>();
    List<Map<String, Object>> conflicts = new ArrayList<>();
    LinkedHashSet<String> sources = new LinkedHashSet<>();
    List<String> singleSource = new ArrayList<>();
    Map<String, Object> soul;
    String favorite;
    boolean verified, movesChecked;
    String enjoiFood() { return enjoi != null && enjoi.length > 4 ? enjoi[4] : ""; }
  }

  // gamepedia の妖怪ページから読んだ値
  static class PageInfo {
    String favorite, skillEffect, attackLine, techLine, inspiritEffect, soultimateLine, soultimateEffect;
  }

  static final Map<Integer, Y> byNo = new TreeMap<>();
  static final Map<String, Y> byName = new HashMap<>();   // 正規化した名前 → 妖怪
  static final Map<String, List<Y>> byFold = new HashMap<>(); // かなをそろえた名前 → 妖怪（ゆれの救済用）
  static Map<String, Object> ovr;
  static final List<String> unresolved = new ArrayList<>();
  static final List<String> notes = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    RAW = Path.of(args[0]);
    ROOT = Path.of(args.length > 1 ? args[1] : ".");
    ovr = map(Json.parse(Files.readString(ROOT.resolve("scripts/data/overrides.json"))));

    loadSkeleton();
    resolveIdentity();
    indexNames();
    applyVersionsAndFlags();
    loadPages();
    buildMoves();
    buildSoulAndFood();
    buildEvolution();
    buildFusion();
    buildObtain();
    applyGashaRates();
    finish();

    List<Object> out = new ArrayList<>();
    List<Y> sorted = new ArrayList<>(byNo.values());
    sorted.sort(Comparator.comparingInt((Y y) -> TRIBES.indexOf(y.tribe)).thenComparing(y -> y.legend).thenComparingInt(y -> y.no));
    int order = 1;
    for (Y y : sorted) out.add(toJson(y, order++));
    Files.writeString(ROOT.resolve("data/yokai.json"), Json.write(out));
    Files.writeString(ROOT.resolve("docs/data-report.md"), report(sorted));
    System.out.println("妖怪 " + sorted.size() + " 体を書き出しました");
    if (!unresolved.isEmpty()) System.out.println("名前を特定できなかったもの: " + unresolved.size() + " 件（docs/data-report.md）");
  }

  // ================= 読み込み =================

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }
  @SuppressWarnings("unchecked")
  static List<Object> list(Object o) { return o == null ? List.of() : (List<Object>) o; }
  static String str(Object o) { return o == null ? "" : o.toString().trim(); }

  static List<Map<String, Object>> gpAll(String type) throws Exception {
    int pages = Integer.parseInt(Files.readString(RAW.resolve("gp/" + type + ".pages")).trim());
    List<Map<String, Object>> all = new ArrayList<>();
    for (int p = 1; p <= pages; p++) for (Object o : list(Json.parse(Files.readString(RAW.resolve("gp/" + type + "-" + p + ".json"))))) all.add(map(o));
    return all;
  }

  static List<String[]> tsv(String file) throws Exception {
    return Files.readAllLines(RAW.resolve(file)).stream().filter(l -> !l.isBlank()).map(l -> l.split("\t", -1)).toList();
  }

  static void loadSkeleton() throws Exception {
    for (Map<String, Object> m : gpAll("monsters")) {
      Map<String, Object> meta = map(m.get("meta"));
      Y y = new Y();
      y.no = Integer.parseInt(str(meta.get("number")));
      y.wpId = ((Number) m.get("id")).longValue();
      y.gp = meta;
      y.gpTribe = str(m.get("monster_species"));
      y.gpRank = str(m.get("monster_rank"));
      for (String f : str(m.get("monster_feature")).split(",")) if (!f.isBlank()) y.gpFeatures.add(f.trim());
      y.id = String.format("y%03d", y.no);
      y.sources.add(URL_GP_MONSTER + y.wpId);
      byNo.put(y.no, y);
    }
    for (String[] w : tsv("wiki.tsv")) { Y y = byNo.get(Integer.parseInt(w[0])); if (y != null) y.wiki = w; else notes.add("Wiki にだけある番号: " + w[0] + " " + w[1]); }
    for (String[] e : tsv("enjoi.tsv")) { Y y = byNo.get(Integer.parseInt(e[0])); if (y != null) y.enjoi = e; else notes.add("enjoi にだけある番号: " + e[0] + " " + e[1]); }
    // Game8 の全妖怪一覧（番号・ランク・名前・好物）
    for (String line : htmlLines(read("game8/list.html"))) {
      String[] c = cells(line);
      if (c.length >= 4 && c[0].matches("\\d{3}")) { Y y = byNo.get(Integer.parseInt(c[0])); if (y != null && y.g8 == null) y.g8 = c; }
    }
    // enjoi の怪魔のページ（番号は大辞典と違うので名前で合わせる）
    for (String line : htmlLines(read("enjoi/youkai_kaima.html"))) {
      String[] c = cells(line);
      if (c.length >= 3 && c[0].matches("\\d+")) for (Y y : byNo.values()) if (nfkc(str(y.gp.get("name"))).equals(nfkc(c[1]))) y.kaimaRank = nfkc(c[2]);
    }
  }

  // 名前・ランク・種族を出典の多数決で決める
  static void resolveIdentity() {
    for (Y y : byNo.values()) {
      Map<String, String> names = new LinkedHashMap<>();
      names.put("gamepedia", str(y.gp.get("name")));
      if (y.wiki != null) names.put("Wiki", y.wiki[1]);
      if (y.enjoi != null) names.put("enjoi", y.enjoi[1]);
      if (y.g8 != null) names.put("Game8", y.g8[2]);
      y.name = vote(y, "名前", names, true);

      Map<String, String> ranks = new LinkedHashMap<>();
      ranks.put("gamepedia", y.gpRank);
      if (y.wiki != null) ranks.put("Wiki", nfkc(y.wiki[2]));
      if (y.enjoi != null) ranks.put("enjoi", nfkc(y.enjoi[2]));
      if (y.kaimaRank != null) ranks.put("enjoi（怪魔のページ）", y.kaimaRank);
      if (y.g8 != null) ranks.put("Game8", nfkc(y.g8[1]));
      y.rank = vote(y, "ランク", ranks, false);
      if (y.g8 != null) y.sources.add(URL_GAME8_LIST);

      y.tribe = y.gpTribe;
      if (y.wiki != null && !y.wiki[3].equals(y.gpTribe)) conflict(y, "種族", Map.of("gamepedia", y.gpTribe, "Wiki", y.wiki[3]), y.gpTribe, "gamepedia を採用（要確認）", false);
      if (y.wiki == null) y.singleSource.add("種族");
      if (!TRIBES.contains(y.tribe)) throw new IllegalStateException("種族が不明: " + y.no + " " + y.tribe);
      y.kana = str(y.gp.get("kana"));
      if (y.wiki != null) y.sources.add(URL_WIKI + y.tribe + (y.tribe.equals("怪魔") ? "" : "族"));
      if (y.enjoi != null) y.sources.add(URL_ENJOI_LIST);
      if (y.kaimaRank != null) y.sources.add(URL_ENJOI_KAIMA);
      y.legend = y.no >= 322 && y.no <= 329;
    }
  }

  // 多数決。同数なら gamepedia。食い違いは conflicts に残す
  static String vote(Y y, String field, Map<String, String> vals, boolean isName) {
    Map<String, List<String>> groups = new LinkedHashMap<>();
    Map<String, String> shown = new HashMap<>();
    for (var e : vals.entrySet()) {
      if (e.getValue() == null || e.getValue().isBlank()) continue;
      String k = nfkc(e.getValue());
      groups.computeIfAbsent(k, x -> new ArrayList<>()).add(e.getKey());
      shown.putIfAbsent(k, e.getKey().equals("gamepedia") || !shown.containsKey(k) ? e.getValue() : shown.get(k));
      if (e.getKey().equals("gamepedia")) shown.put(k, e.getValue());
    }
    String best = null;
    for (var e : groups.entrySet()) {
      if (best == null || e.getValue().size() > groups.get(best).size()
          || (e.getValue().size() == groups.get(best).size() && e.getValue().contains("gamepedia"))) best = e.getKey();
    }
    if (groups.size() > 1) {
      int n = groups.get(best).size();
      // 2つ以上が一致し、同じ数の別の値がないときだけ「多数決で決まった」とする
      boolean majority = n >= 2 && groups.values().stream().filter(g -> g.size() == n).count() == 1;
      String how = majority ? String.join("・", groups.get(best)) + " の" + n + "つが一致したものを採用" : "出典が割れたため gamepedia を採用（要確認）";
      conflict(y, field, vals, shown.get(best), how, majority);
    } else if (groups.get(best).size() < 2) y.singleSource.add(field);
    return isName ? shown.get(best) : best;
  }

  // resolved: 多数決で決まったもの（false はユーザーに確認したいもの）
  static void conflict(Y y, String field, Map<String, String> vals, String chosen, String how, boolean resolved) {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("field", field);
    c.put("values", new LinkedHashMap<>(vals));
    c.put("chosen", chosen);
    c.put("resolved", resolved);
    c.put("note", how);
    y.conflicts.add(c);
  }

  // ================= 名前の引き当て =================

  static void indexNames() {
    for (Y y : byNo.values()) {
      for (String n : new String[]{y.name, str(y.gp.get("name")), y.wiki != null ? y.wiki[1] : null, y.enjoi != null ? y.enjoi[1] : null}) {
        if (n == null || n.isBlank()) continue;
        byName.putIfAbsent(nfkc(n), y);
      }
      byFold.computeIfAbsent(fold(y.name), k -> new ArrayList<>()).add(y);
    }
    for (var e : map(ovr.get("aliases")).entrySet()) {
      if (e.getKey().startsWith("_")) continue;
      Y y = byName.get(nfkc(str(e.getValue())));
      if (y == null) throw new IllegalStateException("aliases の行き先が見つからない: " + e.getValue());
      byName.putIfAbsent(nfkc(e.getKey()), y);
    }
  }

  static Y find(String name) {
    if (name == null) return null;
    String n = name.replaceAll("[「」]", "").trim();
    if (n.isEmpty()) return null;
    Y y = byName.get(nfkc(n));
    if (y != null) return y;
    List<Y> f = byFold.get(fold(n));
    return f != null && f.size() == 1 ? f.get(0) : null;
  }

  static Y need(String name, String where) {
    Y y = find(name);
    if (y == null) unresolved.add(where + ": 「" + name + "」");
    return y;
  }

  static String nfkc(String s) { return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC).replaceAll("\\s+", "").replace("･", "・"); }

  // ひらがな・カタカナと長音・記号をそろえる（誤字の救済用。別の妖怪がぶつかったら使わない）
  static String fold(String s) {
    StringBuilder b = new StringBuilder();
    for (char c : nfkc(s).toCharArray()) {
      if (c >= 'ァ' && c <= 'ヶ') c = (char) (c - 0x60);
      if (c == '・' || c == '.' || c == '★' || c == '☆') continue;
      b.append(c);
    }
    return b.toString().toLowerCase();
  }

  // ================= バージョン・配信系・レジェンド =================

  static void applyVersionsAndFlags() {
    Map<String, Object> vo = map(ovr.get("versionOnly"));
    for (String v : ALL_VERSIONS) for (Object n : list(vo.get(v))) {
      Y y = need(str(n), "versionOnly");
      if (y != null) y.versions = new ArrayList<>(List.of(v));
    }
    for (Object n : list(ovr.get("versionUnsure"))) { Y y = need(str(n), "versionUnsure"); if (y != null) y.versionUnsure = true; }
    for (Object n : list(map(ovr.get("distribution")).get("names"))) { Y y = need(str(n), "distribution"); if (y != null) y.distribution = true; }
    // gamepedia の「元祖」「本家」「真打」の目印と食い違っていないか
    for (Y y : byNo.values()) {
      for (String v : ALL_VERSIONS) {
        boolean gpSays = y.gpFeatures.contains(VERSION_JA.get(v));
        boolean only = y.versions.size() == 1 && y.versions.get(0).equals(v);
        if (gpSays && !only) notes.add(y.name + "（" + y.id + "）: gamepedia では「" + VERSION_JA.get(v) + "」の目印があるが、" + VERSION_JA.get(v) + "限定にしていない");
      }
    }
    for (var e : map(ovr.get("legends")).entrySet()) {
      if (e.getKey().startsWith("_")) continue;
      Y lg = need(e.getKey(), "legends");
      if (lg == null) continue;
      if (!lg.legend) throw new IllegalStateException("レジェンドの番号ではない: " + e.getKey());
      for (Object n : list(e.getValue())) { Y m = need(str(n), "legends/" + e.getKey()); if (m != null) lg.legendUnlock.add(m.id); }
      lg.sources.add(URL_ENJOI_LEGEND);
      lg.sources.add(URL_WIKI + "レジェンド妖怪の開放条件");
    }
  }

  // ================= gamepedia の妖怪ページ =================

  static void loadPages() throws Exception {
    for (Y y : byNo.values()) {
      Path p = RAW.resolve("gppage/" + y.wpId + ".html");
      if (!Files.exists(p)) continue;
      List<String> lines = htmlLines(Files.readString(p));
      int st = indexOf(lines, l -> l.startsWith("URLをコピー"), 0);
      if (st < 0) continue;
      int en = indexOf(lines, l -> l.equals("関連妖怪") || l.endsWith("の扱い方") || l.endsWith("の攻略記事"), st);
      if (en < 0) en = Math.min(lines.size(), st + 200);
      List<String> L = lines.subList(st, en);
      PageInfo pi = new PageInfo();
      for (int i = 0; i < L.size(); i++) {
        String l = L.get(i), next = i + 1 < L.size() ? L.get(i + 1) : "";
        if (l.equals("好物")) pi.favorite = next;
        else if (l.startsWith("スキル ")) pi.skillEffect = next;
        else if (l.startsWith("こうげき ")) pi.attackLine = next;
        else if (l.startsWith("ようじゅつ ")) pi.techLine = next;
        else if (l.startsWith("とりつき ")) pi.inspiritEffect = next;
        else if (l.startsWith("必殺技 ")) {
          pi.soultimateLine = next;
          String after = i + 2 < L.size() ? L.get(i + 2) : "";
          if (next.startsWith("いりょく") && !after.isEmpty() && !after.endsWith("のわざ")) pi.soultimateEffect = after;
        }
      }
      y.page = pi;
    }
  }

  static int indexOf(List<String> l, java.util.function.Predicate<String> p, int from) {
    for (int i = from; i < l.size(); i++) if (p.test(l.get(i))) return i;
    return -1;
  }

  // ================= 技・スキル =================

  static Map<String, Map<String, Object>> gpSkills, gpAttacks, gpMagics, gpPossess, gpSpecials;
  static Map<String, List<Map<String, Object>>> gpSpecialsAll = new HashMap<>();

  static Map<String, Map<String, Object>> byMetaName(List<Map<String, Object>> all) {
    Map<String, Map<String, Object>> m = new HashMap<>();
    for (Map<String, Object> r : all) m.putIfAbsent(nfkc(str(map(r.get("meta")).get("name"))), map(r.get("meta")));
    return m;
  }

  static void buildMoves() throws Exception {
    gpSkills = byMetaName(gpAll("monster-skills"));
    gpAttacks = byMetaName(gpAll("monster-attacks"));
    gpMagics = byMetaName(gpAll("monster-magics"));
    gpPossess = byMetaName(gpAll("monster-possesions"));
    gpSpecials = byMetaName(gpAll("monster-specials"));
    checkMovesWithEnjoi();
  }

  // enjoi のレジェンド・怪魔・特別な妖怪のページにある技の表（約60体分）と、gamepedia の技の名前を比べる
  static void checkMovesWithEnjoi() throws Exception {
    List<Path> files = new ArrayList<>();
    for (String dir : List.of("enjoi", "enjoi1")) {
      Path d = RAW.resolve(dir);
      if (!Files.isDirectory(d)) continue;
      try (var s = Files.list(d)) { s.filter(p -> p.getFileName().toString().matches("youkai_(legend|kaima|gotouti|rea\\d+)\\.html")).sorted().forEach(files::add); }
    }
    Pattern br = Pattern.compile("【([^】]+)】");
    Set<String> done = new HashSet<>();
    for (Path f : files) {
      String url = (f.getParent().getFileName().toString().equals("enjoi") ? "https://enjoi7.sakura.ne.jp/youkai-wach2/" : "https://enjoi.sakura.ne.jp/youkai-watch2/") + f.getFileName();
      for (String line : htmlLines(Files.readString(f))) {
        String[] c = cells(line);
        if (c.length < 5 || !c[4].contains("■【")) continue;
        Y y = find(c[0]);
        if (y == null || !done.add(y.id)) continue;
        Map<String, String> en = new LinkedHashMap<>();
        en.put("ひっさつわざ", c[2].split(" / ")[0].trim());
        Matcher sk = br.matcher(c[3]);
        if (sk.find()) en.put("スキル", sk.group(1).trim());
        Matcher t = Pattern.compile("【(?:よ|ようじゅつ)】\\s*([^\\s（(/]+)").matcher(c[4]);
        if (t.find()) en.put("ようじゅつ", t.group(1));
        Matcher in = Pattern.compile("【(?:と|とりつく|とりつき)】\\s*([^\\s（(/]+)").matcher(c[4]);
        if (in.find()) en.put("とりつく", in.group(1));
        Map<String, String> gp = Map.of("ひっさつわざ", str(y.gp.get("monster_special")), "スキル", str(y.gp.get("skills")),
            "ようじゅつ", str(y.gp.get("monster_magic")), "とりつく", str(y.gp.get("monster_possesion")));
        boolean any = false;
        for (var e : en.entrySet()) {
          String g = gp.get(e.getKey());
          if (g.isEmpty() || e.getValue().isEmpty()) continue;
          any = true;
          String a = fold(g).replace("ー", "〜"), b = fold(e.getValue()).replace("ー", "〜");
          if (a.equals(b) || a.contains(b) || b.contains(a)) continue;
          // 1〜2文字だけの違いは書き間違いとみなす。ただし、ようじゅつは「火炎の術」と「火花の術」のように
          // 1文字違いで別の術になるので、enjoi の名前が実在しない術のときだけ書き間違いとする
          int d = distance(a, b), len = Math.min(a.length(), b.length());
          boolean small = (d <= 2 && len >= 4) || (d == 1 && len >= 3);
          boolean typo = e.getKey().equals("ようじゅつ") ? small && !gpMagics.containsKey(nfkc(e.getValue())) : small;
          Map<String, String> v = new LinkedHashMap<>();
          v.put("gamepedia", g);
          v.put("enjoi", e.getValue());
          conflict(y, e.getKey() + "の名前", v, g, typo ? "書き方の小さな違い。gamepedia を採用" : "gamepedia を採用（要確認）", typo);
        }
        if (any) { y.sources.add(url); y.movesChecked = true; }
      }
    }
  }

  static final Pattern POWER = Pattern.compile("いりょく\\s*(\\d+)(?:\\s*[x×]\\s*(\\d+))?\\s*(?:[(（](.+?)[)）])?");

  static Map<String, Object> moves(Y y) {
    Map<String, Object> mv = new LinkedHashMap<>();
    PageInfo pg = y.page;

    String an = str(y.gp.get("monster_attack"));
    Map<String, Object> atk = new LinkedHashMap<>();
    atk.put("name", orNull(an));
    Integer ap = null, ah = null;
    if (pg != null && pg.attackLine != null) {
      Matcher m = POWER.matcher(pg.attackLine);
      if (m.find()) { ap = Integer.valueOf(m.group(1)); ah = m.group(2) != null ? Integer.valueOf(m.group(2)) : 1; }
    }
    if (ap == null && gpAttacks.containsKey(nfkc(an))) ap = intOrNull(gpAttacks.get(nfkc(an)).get("power"));
    atk.put("power", ap);
    atk.put("hits", ah);
    mv.put("attack", atk);

    String tn = str(y.gp.get("monster_magic"));
    Map<String, Object> tech = new LinkedHashMap<>();
    Map<String, Object> mg = gpMagics.get(nfkc(tn));
    tech.put("name", orNull(tn));
    tech.put("element", mg != null ? orNull(str(mg.get("attribute"))) : null);
    tech.put("power", mg != null ? intOrNull(mg.get("power")) : null);
    mv.put("technique", tech);

    String in = str(y.gp.get("monster_possesion"));
    Map<String, Object> insp = new LinkedHashMap<>();
    insp.put("name", orNull(in));
    String ie = pg != null && pg.inspiritEffect != null ? pg.inspiritEffect : gpPossess.containsKey(nfkc(in)) ? str(gpPossess.get(nfkc(in)).get("effect")) : "";
    insp.put("effect", orNull(ie));
    mv.put("inspirit", insp);

    String sn = str(y.gp.get("monster_special"));
    Map<String, Object> sp = new LinkedHashMap<>();
    sp.put("name", orNull(sn));
    Integer spow = null, shit = null;
    String seff = null;
    if (pg != null && pg.soultimateLine != null) {
      Matcher m = POWER.matcher(pg.soultimateLine);
      if (m.find()) {
        spow = Integer.valueOf(m.group(1));
        shit = m.group(2) != null ? Integer.valueOf(m.group(2)) : 1;
        seff = m.group(3);
        if (pg.soultimateEffect != null) seff = seff == null ? pg.soultimateEffect : seff + "。" + pg.soultimateEffect;
      } else seff = pg.soultimateLine;
    } else if (gpSpecials.containsKey(nfkc(sn))) {
      Map<String, Object> s = gpSpecials.get(nfkc(sn));
      spow = intOrNull(s.get("power"));
      shit = intOrNull(s.get("count"));
      seff = orNull(str(s.get("effect")));
    }
    sp.put("power", spow);
    sp.put("hits", spow == null ? null : shit);
    sp.put("effect", orNull(seff));
    mv.put("soultimate", sp);
    return mv;
  }

  static Map<String, Object> skill(Y y) {
    String n = str(y.gp.get("skills"));
    String e = y.page != null && y.page.skillEffect != null ? y.page.skillEffect : gpSkills.containsKey(nfkc(n)) ? str(gpSkills.get(nfkc(n)).get("effect")) : "";
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", orNull(n));
    m.put("effect", orNull(e));
    return m;
  }

  // ================= 魂・好物 =================

  static void buildSoulAndFood() throws Exception {
    // Game8 の魂一覧（魂の名前 → 効果）
    Map<String, String> g8 = new HashMap<>();
    Matcher m = Pattern.compile("<td><a[^>]*>([^<]+の魂)</a></td>\\s*<td>(.*?)</td>", Pattern.DOTALL).matcher(read("game8/souls.html"));
    while (m.find()) g8.putIfAbsent(fold(m.group(1)), m.group(2).replaceAll("<[^>]+>", "").trim());
    // gamepedia の魂の名前は、何体にも同じ名前が入っている（コピーのまま）ことがあるので使わない
    Map<String, Long> count = byNo.values().stream().collect(Collectors.groupingBy(y -> str(y.gp.get("spirit")), Collectors.counting()));
    for (Y y : byNo.values()) {
      String gpSoul = str(y.gp.get("spirit"));
      boolean placeholder = count.get(gpSoul) > 1;
      String own = y.name + "の魂";
      String name = null, eff = null;
      boolean two = false;
      if (!placeholder && g8.containsKey(fold(gpSoul))) { name = gpSoul; eff = g8.get(fold(gpSoul)); two = true; }
      else if (g8.containsKey(fold(own))) {
        name = own;
        eff = g8.get(fold(own));
        if (!placeholder && !fold(gpSoul).equals(fold(own))) conflict(y, "魂の名前", Map.of("gamepedia", gpSoul, "Game8", own), own, "Game8 を採用（gamepedia の表記ゆれ）", true);
      } else if (!placeholder) name = gpSoul;
      // gamepedia と Game8 で名前の書き方だけ違うときは、採用した妖怪の名前にそろえる
      if (name != null && fold(name).equals(fold(own))) name = own;
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("name", orNull(name));
      s.put("effect", orNull(eff));
      y.soul = s;
      if (eff != null) y.sources.add(URL_GAME8_SOUL);
      if (name == null) notes.add(y.name + "（" + y.id + "）: 魂の名前・効果が見つからない");
      else if (eff == null) notes.add(y.name + "（" + y.id + "）: 魂の効果が見つからない（" + name + "）");
      if (name != null && !two) y.singleSource.add("魂");
    }
    // 好物: gamepedia・enjoi・Game8 の多数決（呼び方のゆれはそろえてから比べる）
    for (Y y : byNo.values()) {
      String a = str(y.gp.get("favorite"));
      if (a.isEmpty() && y.page != null && y.page.favorite != null && !y.page.favorite.endsWith("の入手方法")) a = y.page.favorite;
      Map<String, String> foods = new LinkedHashMap<>();
      foods.put("gamepedia", food(a));
      foods.put("enjoi", food(y.enjoiFood()));
      if (y.g8 != null) foods.put("Game8", food(y.g8[3]));
      foods.values().removeIf(String::isEmpty);
      y.favorite = foods.isEmpty() ? null : vote(y, "好物", foods, true);
    }
  }

  // 好物の呼び方のゆれ（すし = 寿司、バーガー = ハンバーガー など）をそろえる
  static String food(String s) {
    String x = nfkc(s).replace("系", "");
    return switch (x) {
      case "すし" -> "寿司";
      case "バーガー" -> "ハンバーガー";
      case "魚" -> "魚介";
      case "駄菓子" -> "スナック";
      case "シュース" -> "ジュース";
      case "-", "ー", "なし", "?", "？" -> "";
      default -> x;
    };
  }

  // ================= 進化 =================

  static void buildEvolution() throws Exception {
    // (進化元, 進化先) → 出典ごとのレベル
    Map<String, Map<String, Integer>> evo = new LinkedHashMap<>();
    for (Map<String, Object> r : gpAll("evolutions")) {
      if (!"レベルアップ".equals(str(r.get("evolution_type")))) continue;
      Map<String, Object> meta = map(r.get("meta"));
      Y a = need(str(meta.get("evolution_source")), "gamepedia の進化"), b = need(str(meta.get("evolution_result")), "gamepedia の進化");
      if (a == null || b == null) continue;
      evo.computeIfAbsent(a.id + ">" + b.id, k -> new LinkedHashMap<>()).put("gamepedia", intOrNull(meta.get("evolution_level")));
    }
    boolean inTable = false;
    for (String line : htmlLines(read("enjoi1/youkai_shinka.html"))) {
      String[] c = cells(line);
      if (c.length >= 2 && c[0].startsWith("進化前")) { inTable = true; continue; }
      if (!inTable || c.length < 3) continue;
      Y a = need(c[0], "enjoi の進化"), b = need(c[1], "enjoi の進化");
      if (a == null || b == null) continue;
      Matcher m = Pattern.compile("レベル\\s*(\\d+)").matcher(c[2]);
      evo.computeIfAbsent(a.id + ">" + b.id, k -> new LinkedHashMap<>()).put("enjoi", m.find() ? Integer.valueOf(m.group(1)) : null);
    }
    for (var e : evo.entrySet()) {
      String[] ids = e.getKey().split(">");
      Y a = byId(ids[0]), b = byId(ids[1]);
      Map<String, Integer> lv = e.getValue();
      Integer chosen = lv.get("gamepedia") != null ? lv.get("gamepedia") : lv.get("enjoi");
      Set<Integer> distinct = lv.values().stream().filter(Objects::nonNull).collect(Collectors.toSet());
      if (distinct.size() > 1) {
        Map<String, String> v = new LinkedHashMap<>();
        lv.forEach((k, x) -> v.put(k, "Lv" + x));
        for (Y t : List.of(a, b)) conflict(t, "進化レベル（" + a.name + "→" + b.name + "）", v, "Lv" + chosen, "gamepedia を採用（要確認）", false);
      }
      if (lv.size() < 2) { a.singleSource.add("進化"); b.singleSource.add("進化"); }
      a.evolvesTo.add(mapOf("id", b.id, "level", chosen));
      b.evolvesFrom.add(mapOf("id", a.id, "level", chosen));
      a.sources.add(URL_ENJOI_SHINKA);
      b.sources.add(URL_ENJOI_SHINKA);
    }
  }

  // ================= 合成 =================

  static void buildFusion() throws Exception {
    // 結果 + 素材の妖怪 → レシピ（出典ごとのアイテム名）
    Map<String, Map<String, Object>> rec = new LinkedHashMap<>();
    for (Map<String, Object> r : gpAll("evolutions")) {
      String type = str(r.get("evolution_type"));
      if (!type.equals("妖怪xアイテム") && !type.equals("妖怪x妖怪")) continue;
      Map<String, Object> meta = map(r.get("meta"));
      addRecipe(rec, "gamepedia", str(meta.get("evolution_source")), str(meta.get("evolution_opposite")), str(meta.get("evolution_result")), type.equals("妖怪x妖怪"));
    }
    boolean inTable = false;
    for (String line : htmlLines(read("enjoi1/youkai_shinka_gousei.html"))) {
      String[] c = cells(line);
      if (c.length >= 3 && c[0].equals("1つめ")) { inTable = true; continue; }
      if (!inTable || c.length < 3) continue;
      if (line.startsWith("■")) { inTable = false; continue; }
      addRecipe(rec, "enjoi", c[0], c[1], c[2], find(c[1]) != null);
    }
    // Game8 の合成レシピ一覧（妖怪どうしの表にアイテムがまざっているので、名前で見分ける）
    for (String line : htmlLines(read("game8/evo.html"))) {
      String[] c = cells(line);
      if (c.length == 4 && c[2].equals("→") && !c[0].equals("素材1")) addRecipe(rec, "Game8", c[0], c[1], c[3], find(c[1]) != null);
    }
    for (var e : rec.entrySet()) {
      Map<String, Object> r = e.getValue();
      @SuppressWarnings("unchecked") Map<String, String> items = (Map<String, String>) r.get("items");
      @SuppressWarnings("unchecked") List<Y> mats = (List<Y>) r.get("mats");
      @SuppressWarnings("unchecked") Set<String> from = (Set<String>) r.get("from");
      Y res = (Y) r.get("result");
      List<Object> materials = new ArrayList<>();
      for (Y m : mats) materials.add(mapOf("type", "yokai", "id", m.id));
      String item = items.isEmpty() ? null : vote(res, "合成アイテムの名前", items, true);
      if (item != null) {
        Map<String, Object> im = new LinkedHashMap<>();
        im.put("type", "item");
        im.put("name", item);
        materials.add(im);
      }
      if (materials.size() < 2) { notes.add(res.name + ": 合成の素材が1つしか読めなかった"); continue; }
      Map<String, Object> recipe = new LinkedHashMap<>();
      recipe.put("materials", materials);
      recipe.put("result", res.id);
      Set<Y> involved = new LinkedHashSet<>();
      involved.add(res);
      involved.addAll(mats);
      for (Y y : involved) {
        if (!y.fusion.contains(recipe)) y.fusion.add(recipe);
        if (from.contains("enjoi")) y.sources.add(URL_ENJOI_GOUSEI);
        if (from.contains("Game8")) y.sources.add(URL_GAME8_EVO);
        if (from.size() < 2) y.singleSource.add("合成");
      }
    }
  }

  static void addRecipe(Map<String, Map<String, Object>> rec, String src, String a, String b, String result, boolean bIsYokai) {
    Y ya = need(a, src + " の合成"), yr = need(result, src + " の合成");
    Y yb = bIsYokai ? need(b, src + " の合成") : null;
    if (ya == null || yr == null || (bIsYokai && yb == null)) return;
    List<Y> mats = new ArrayList<>(List.of(ya));
    if (yb != null) mats.add(yb);
    mats.sort(Comparator.comparing(y -> y.id));
    String key = yr.id + "<=" + mats.stream().map(y -> y.id).collect(Collectors.joining("+"));
    Map<String, Object> r = rec.computeIfAbsent(key, k -> {
      Map<String, Object> x = new HashMap<>();
      x.put("result", yr); x.put("mats", mats); x.put("items", new LinkedHashMap<String, String>()); x.put("from", new LinkedHashSet<String>());
      return x;
    });
    @SuppressWarnings("unchecked") Set<String> from = (Set<String>) r.get("from");
    from.add(src);
    if (!bIsYokai) { @SuppressWarnings("unchecked") Map<String, String> items = (Map<String, String>) r.get("items"); items.put(src, b); }
  }

  // ================= 入手方法 =================

  static void buildObtain() throws Exception {
    Map<String, Object> manual = map(ovr.get("obtain"));
    Map<String, List<String[]>> gasha = gashaByYokai();
    Map<String, List<Map<String, Object>>> gpLoc = new HashMap<>();
    for (Map<String, Object> r : gpAll("monster-locations")) {
      Map<String, Object> meta = map(r.get("meta"));
      Y y = need(str(meta.get("monster")), "gamepedia の出現場所");
      if (y != null) gpLoc.computeIfAbsent(y.id, k -> new ArrayList<>()).add(meta);
    }

    for (Y y : byNo.values()) {
      // 手で書いたもの
      if (manual.containsKey(y.name)) {
        for (Object o : list(manual.get(y.name))) y.obtain.add(normalizeObtain(y, map(o)));
        continue;
      }
      if (y.legend) {
        y.obtain.add(obtain(y, "legend", "必要な妖怪8体をすべて友達にして、妖怪大辞典のレジェンドの封印を解く"));
        continue;
      }
      // 進化・合成（集めたレシピから）
      for (Map<String, Object> e : y.evolvesFrom) {
        Y a = byId(str(e.get("id")));
        y.obtain.add(obtain(y, "evolve", a.name + "が" + (e.get("level") != null ? "Lv" + e.get("level") : "レベルアップ") + "で進化"));
      }
      for (Map<String, Object> r : y.fusion) {
        if (!y.id.equals(r.get("result"))) continue;
        y.obtain.add(obtain(y, "fusion", recipeText(r)));
      }
      // enjoi の出現場所の欄
      String loc = y.enjoi != null ? y.enjoi[3] : "";
      String other = y.enjoi != null && y.enjoi.length > 5 ? y.enjoi[5] : "";
      boolean textSaysGasha = false;
      for (String part : loc.split(" / ")) {
        String t = part.replaceFirst("^[・\\s　]+", "").strip();
        if (t.isEmpty() || t.contains("？？？")) continue;
        // 「（一番奥のトイレに…）」のように前の行の続きなら、前の入手方法の備考に足す
        if ((t.startsWith("（") || t.startsWith("(")) && !y.obtain.isEmpty()) {
          Map<String, Object> prev = y.obtain.get(y.obtain.size() - 1);
          String add = t.replaceAll("^[（(]|[）)]$", "");
          prev.put("note", str(prev.get("note")).isEmpty() ? add : prev.get("note") + "。" + add);
          continue;
        }
        if (t.contains("レジェンド") || t.contains("進化") || t.contains("合成")) continue;
        if (t.contains("ガシャ") || t.contains("コイン")) { textSaysGasha = true; if (!gasha.containsKey(y.id)) y.obtain.add(gashaFromText(y, t)); continue; }
        if (t.contains("クエスト")) { y.obtain.add(obtain(y, "quest", t)); continue; }
        if (t.contains("1日1回") || t.contains("ボス") || t.contains("バトル")) { y.obtain.add(obtain(y, "boss", t)); continue; }
        if (t.contains("ストーリー") || t.contains("イベント") || t.contains("すれちがい") || t.contains("横断歩道") || t.contains("お賽銭")) { y.obtain.add(obtain(y, "other", t)); continue; }
        y.obtain.add(wild(y, t, "enjoi"));
      }
      if (other.contains("クエスト") && other.contains("友達") && y.obtain.stream().noneMatch(o -> o.get("method").equals("quest")))
        y.obtain.add(obtain(y, "quest", other.replaceAll("■", "").replaceAll("\\s*/\\s*", "。").trim()));
      if (other.contains("ストーリー") && y.obtain.stream().noneMatch(o -> o.get("method").equals("other")))
        y.obtain.add(obtain(y, "other", "ストーリー中に友達になる"));
      // gamepedia の出現場所: enjoi に同じ場所がなければ足す
      for (Map<String, Object> g : gpLoc.getOrDefault(y.id, List.of())) {
        String area = str(g.get("area")), dun = str(g.get("dungeon")), rem = str(g.get("remarks"));
        String showArea = area, spot = dun;
        if (area.equals("ダンジョン")) { showArea = dun; spot = ""; }
        else if (area.equals("ゲートの間")) { showArea = "きまぐれゲート"; spot = dun; }
        if (!rem.isEmpty()) spot = spot.isEmpty() ? rem : spot + " " + rem;
        boolean matched = false;
        for (Map<String, Object> o : y.obtain) {
          if (!"wild".equals(o.get("method"))) continue;
          String txt = nfkc(str(o.get("area")) + str(o.get("spot")) + str(o.get("note")));
          String core = nfkc(showArea).replace("(過去)", "");
          if (txt.contains(core) || (!dun.isEmpty() && txt.contains(nfkc(dun)))) { matched = true; o.put("_both", true); }
        }
        if (!matched) {
          Map<String, Object> w = wild(y, showArea + (spot.isEmpty() ? "" : "「" + spot + "」"), "gamepedia");
          y.obtain.add(w);
        }
      }
      // ガシャ（gamepedia のコインごとの一覧から）
      if (gasha.containsKey(y.id)) y.obtain.addAll(gashaObtain(y, gasha.get(y.id)));
      else if (textSaysGasha && y.obtain.stream().noneMatch(o -> o.get("method").equals("gasha"))) notes.add(y.name + "（" + y.id + "）: ガシャで出るとあるがコインが分からない");
    }

    for (Y y : byNo.values()) {
      boolean anyWild = y.obtain.stream().anyMatch(o -> o.get("method").equals("wild"));
      boolean wildChecked = y.obtain.stream().anyMatch(o -> Boolean.TRUE.equals(o.get("_both")));
      if (anyWild && !wildChecked) y.singleSource.add("出現場所");
      for (Map<String, Object> o : y.obtain) o.remove("_both");
      if (y.obtain.isEmpty()) {
        y.obtain.add(obtain(y, "other", "入手方法は不明（出典に記載なし）"));
        notes.add(y.name + "（" + y.id + "）: 入手方法が見つからない");
      }
      // 野生 → その他の順にそろえる
      List<String> order = List.of("wild", "evolve", "fusion", "gasha", "quest", "boss", "legend", "qr", "password", "trade", "other");
      y.obtain.sort(Comparator.comparingInt(o -> order.indexOf(str(o.get("method")))));
    }
  }

  static Map<String, Object> obtain(Y y, String method, String note) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("method", method);
    o.put("versions", new ArrayList<>(y.versions));
    o.put("note", note);
    return o;
  }

  // overrides.json の入手方法に、足りない項目を入れる
  static Map<String, Object> normalizeObtain(Y y, Map<String, Object> src) {
    Map<String, Object> o = new LinkedHashMap<>();
    String method = str(src.get("method"));
    o.put("method", method);
    o.put("versions", src.containsKey("versions") ? src.get("versions") : new ArrayList<>(y.versions));
    if (method.equals("gasha")) {
      List<Object> coins = new ArrayList<>();
      for (Object c : list(src.get("coins"))) {
        Map<String, Object> cm = new LinkedHashMap<>();
        cm.put("coin", str(map(c).get("coin")));
        cm.put("rate", null);
        cm.put("rateSource", "");
        coins.add(cm);
      }
      o.put("coins", coins);
    }
    for (String k : List.of("area", "spot", "how", "era", "time", "weather")) if (src.containsKey(k) || method.equals("wild")) o.put(k, src.get(k));
    o.put("note", str(src.get("note")));
    return o;
  }

  static final List<String> HOW_WORDS = List.of("草むら", "木", "車の下", "自動販売機", "電柱", "川", "海", "池", "洞窟", "洞穴", "妖怪スポット", "シンボル",
      "ゴミ置き場", "竹やぶ", "墓", "田んぼ", "お地蔵様", "棚田", "お寺の鐘");

  // 「・さくら住宅街　「木」　（元祖限定）」のような文から野生の出現場所を作る
  static Map<String, Object> wild(Y y, String text, String src) {
    String t = Normalizer.normalize(text, Normalizer.Form.NFKC).replace('　', ' ').trim();
    List<String> vs = new ArrayList<>(y.versions);
    if (t.contains("元祖限定")) vs.retainAll(List.of("ganso"));
    if (t.contains("本家限定")) vs.retainAll(List.of("honke"));
    if (vs.isEmpty()) vs = new ArrayList<>(y.versions);
    String weather = t.contains("雨") ? "雨" : null;
    List<String> time = null;
    if (t.contains("夜")) time = List.of("night");
    else if (t.contains("昼")) time = List.of("day");
    // かっこの中は備考へ（バージョン・天気・時間帯は項目にしたので消す）
    List<String> noteParts = new ArrayList<>();
    Matcher pm = Pattern.compile("[(（]([^()（）]*)[)）]").matcher(t);
    StringBuilder rest = new StringBuilder();
    while (pm.find()) {
      String in = pm.group(1).trim();
      boolean keepInArea = in.equals("過去") && pm.start() > 0 && !Character.isWhitespace(t.charAt(pm.start() - 1));
      if (keepInArea) { pm.appendReplacement(rest, "（過去）"); continue; }
      if (!in.isEmpty() && !in.matches("(元祖|本家)限定|雨限定|夜|昼")) noteParts.add(in);
      pm.appendReplacement(rest, " ");
    }
    pm.appendTail(rest);
    String s = rest.toString().replaceAll("\\s+", " ").trim();
    // 妖怪スポット（おおもり山） → エリアはおおもり山
    String how = null;
    String area, spot;
    List<String> brackets = new ArrayList<>();
    Matcher bm = Pattern.compile("「([^」]*)」").matcher(s);
    while (bm.find()) if (!bm.group(1).isBlank()) brackets.add(bm.group(1).trim());
    String head = s.replaceAll("「[^」]*」", " ").replaceAll("\\s+", " ").trim();
    int sp = head.indexOf(' ');
    area = sp > 0 ? head.substring(0, sp) : head;
    String tail = sp > 0 ? head.substring(sp + 1).trim() : "";
    if (area.startsWith("妖怪スポット") && !noteParts.isEmpty()) {
      // 「妖怪スポット（そよ風ヒルズ ひょうたん池博物館）」→ エリアと場所に分ける
      String[] in = noteParts.remove(0).split(" ", 2);
      area = in[0];
      if (in.length > 1) tail = (in[1] + " " + tail).trim();
      how = "妖怪スポット";
    }
    if (area.isEmpty()) area = s;
    List<String> spotParts = new ArrayList<>(brackets);
    if (!tail.isEmpty()) spotParts.add(tail);
    // エリア名の表記ゆれをそろえる（逆引きでエリアごとにまとめるため）。時代は元の書き方から決める
    String rawArea = area;
    if (area.equals("妖怪スポット")) {
      // 「妖怪スポット「笑う看板」さくら住宅街の」→ エリアはさくら住宅街
      for (String p : List.copyOf(spotParts)) if (p.endsWith("の")) { area = p.substring(0, p.length() - 1); spotParts.remove(p); how = "妖怪スポット"; }
    }
    Matcher fm = Pattern.compile("^(ムゲン地獄|アミダ極楽)(\\d+(?:[〜~～]\\d+)?階層)$").matcher(area);
    if (fm.matches()) { area = fm.group(1); spotParts.add(0, fm.group(2)); }
    if (area.endsWith("で低確率で出現")) { area = area.replace("で低確率で出現", ""); noteParts.add("低確率"); }
    if (area.endsWith("のミラーなど")) { area = area.replace("のミラーなど", ""); spotParts.add(0, "ミラーなど"); }
    if (area.equals("ケマモト駅周辺")) { spotParts.add(0, area); area = "ケマモト村"; }
    area = area.replace("（過去）", "");
    area = AREA_ALIAS.getOrDefault(area, area);
    spotParts.removeIf(p -> p.matches("で?出現")); // 「えんえんトンネル で出現」の残りかす
    spot = String.join("・", spotParts);
    List<String> hows = new ArrayList<>();
    if (how != null) hows.add(how);
    for (String w : HOW_WORDS) if ((spot + " " + String.join(" ", noteParts)).contains(w) && !hows.contains(w)) hows.add(w);
    if (hows.contains("洞穴") && hows.contains("洞窟")) hows.remove("洞穴");
    String era = rawArea.contains("過去") ? "past" : eraOf(area);
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("method", "wild");
    o.put("versions", vs);
    o.put("area", area);
    o.put("spot", orNull(spot));
    o.put("how", hows.isEmpty() ? null : String.join("・", hows));
    o.put("era", era);
    o.put("time", time);
    o.put("weather", weather);
    o.put("note", String.join("。", noteParts) + (src.equals("gamepedia") ? (noteParts.isEmpty() ? "" : "。") + "gamepedia のみに記載" : ""));
    return o;
  }

  // 出典ごとのエリア名の書き方のちがい → 図鑑で使う名前
  static final Map<String, String> AREA_ALIAS = Map.of(
      "アミダの極楽", "アミダ極楽", "さくらEツリー", "さくらEXツリー", "さくら中央シティー", "さくら中央シティ",
      "桜地下水道", "桜町地下水道", "サギサキ", "ナギサキ");

  static String eraOf(String area) {
    if (area.contains("過去") || area.startsWith("桜町") || area.startsWith("桜地下")) return "past";
    if (area.matches(".*(えんえんトンネル|妖魔界|ムゲン地獄|アミダ極楽|きまぐれゲート|連動ダンジョン|ゲラゲラ|ナゾナゾの間|修羅の間|戦の間).*")) return null;
    return "present";
  }

  static String recipeText(Map<String, Object> r) {
    List<String> parts = new ArrayList<>();
    for (Object o : list(r.get("materials"))) {
      Map<String, Object> m = map(o);
      parts.add(m.get("type").equals("yokai") ? "妖怪「" + byId(str(m.get("id"))).name + "」" : "アイテム「" + m.get("name") + "」");
    }
    return String.join(" ＋ ", parts) + " を合成";
  }

  // ---- ガシャ ----
  static final List<String> COLOR_COINS = List.of("赤コイン", "黄色コイン", "オレンジコイン", "桃コイン", "緑コイン", "青コイン", "紫コイン", "水色コイン");

  // 妖怪の id → [コイン名, 時代] の一覧
  static Map<String, List<String[]>> gashaByYokai() throws Exception {
    // enjoi のガシャの表は、セルの色で現代（コインごとの色）と過去（灰色 #cccccc）を分けている。
    // 色つきコイン8種の表だけを読む（後ろのゲームコインなどの表は時代の区別がない）
    Map<String, String> eraByName = new HashMap<>();
    String html = read("enjoi1/gasya_2.html");
    int cut = html.indexOf("ゲームコイン");
    Matcher m = Pattern.compile("<td[^>]*bgcolor=\"#([0-9a-fA-F]{6})\"[^>]*>([^<]+)</td>").matcher(cut > 0 ? html.substring(0, cut) : html);
    while (m.find()) {
      Y y = find(m.group(2).trim());
      if (y != null && !m.group(1).equals("333333")) eraByName.merge(y.id, m.group(1).equalsIgnoreCase("cccccc") ? "past" : "present", (a, b) -> a.equals(b) ? a : "both");
    }
    Map<String, List<String[]>> out = new LinkedHashMap<>();
    for (Map<String, Object> r : gpAll("gasha-drops")) {
      Map<String, Object> meta = map(r.get("meta"));
      Y y = find(str(meta.get("drop_item")));
      if (y == null) continue;   // アイテム
      String coin = coinName(str(meta.get("gasha_name")));
      String era = COLOR_COINS.contains(coin) ? eraByName.get(y.id) : null;
      out.computeIfAbsent(y.id, k -> new ArrayList<>()).add(new String[]{coin, era});
    }
    return out;
  }

  static String coinName(String s) {
    String c = s.replace("(", "（").replace(")", "）");
    if (c.equals("鬼ガシャ")) return "鬼ガシャ（妖怪ウォッチバスターズ）";
    return c;
  }

  static List<Map<String, Object>> gashaObtain(Y y, List<String[]> coins) {
    // 時代ごとにまとめる
    Map<String, List<String>> byEra = new LinkedHashMap<>();
    for (String[] c : coins) {
      String era = c[1] == null ? "" : c[1];
      if (era.equals("both")) { byEra.computeIfAbsent("present", k -> new ArrayList<>()).add(c[0]); byEra.computeIfAbsent("past", k -> new ArrayList<>()).add(c[0]); }
      else byEra.computeIfAbsent(era, k -> new ArrayList<>()).add(c[0]);
    }
    List<Map<String, Object>> list = new ArrayList<>();
    for (var e : byEra.entrySet()) {
      Map<String, Object> o = obtain(y, "gasha", "");
      List<Object> cs = new ArrayList<>();
      for (String c : new LinkedHashSet<>(e.getValue())) {
        Map<String, Object> cm = new LinkedHashMap<>();
        cm.put("coin", c);
        cm.put("rate", null);
        cm.put("rateSource", "");
        cs.add(cm);
      }
      o.put("coins", cs);
      o.put("era", e.getKey().isEmpty() ? null : e.getKey());
      o.put("note", e.getKey().equals("present") ? "現代の妖怪ガシャ" : e.getKey().equals("past") ? "過去の妖怪ガシャ" : "");
      o.put("note", str(o.get("note")).isEmpty() ? "出現確率は公表されていない" : o.get("note") + "。出現確率は公表されていない");
      list.add(o);
    }
    if (byEra.containsKey("present") || byEra.containsKey("past")) y.sources.add(URL_ENJOI_GASHA);
    return list;
  }

  static Map<String, Object> gashaFromText(Y y, String t) {
    Map<String, Object> o = obtain(y, "gasha", t);
    Matcher m = Pattern.compile("([^\\s（(]*コイン(?:[（(][^）)]+[）)])?)").matcher(t.replace("限定（", "（"));
    List<Object> cs = new ArrayList<>();
    while (m.find()) {
      String c = m.group(1).replaceAll("^.*限定", "").replace("(", "（").replace(")", "）");
      if (c.equals("コイン")) continue;
      Map<String, Object> cm = new LinkedHashMap<>();
      cm.put("coin", c);
      cm.put("rate", null);
      cm.put("rateSource", "");
      cs.add(cm);
    }
    if (cs.isEmpty()) {
      Map<String, Object> cm = new LinkedHashMap<>();
      cm.put("coin", "不明");
      cm.put("rate", null);
      cm.put("rateSource", "");
      cs.add(cm);
    }
    o.put("coins", cs);
    o.put("note", "出現確率は公表されていない");
    return o;
  }

  // ================= ガシャの確率 =================
  // scripts/data/gasha-rates.json の値を入れる。記事にあって図鑑のガシャにないもの・その逆はレポートに出す
  static final List<String> rateNotes = new ArrayList<>();

  static void applyGashaRates() throws Exception {
    Path f = ROOT.resolve("scripts/data/gasha-rates.json");
    if (!Files.exists(f)) return;
    Map<String, Object> top = map(Json.parse(Files.readString(f)));
    String src = str(top.get("source"));
    // コイン → 時代（present / past / all）→ 妖怪の id → 確率
    Map<String, Map<String, Map<String, Object>>> rates = new LinkedHashMap<>();
    for (var ce : map(top.get("coins")).entrySet()) {
      Map<String, Map<String, Object>> byEra = new LinkedHashMap<>();
      for (var ee : map(ce.getValue()).entrySet()) {
        Map<String, Object> ids = new LinkedHashMap<>();
        for (var ye : map(ee.getValue()).entrySet()) {
          Y y = find(ye.getKey());
          if (y == null) rateNotes.add(ce.getKey() + ": 記事の「" + ye.getKey() + "」が図鑑の妖怪と結びつかない");
          else ids.put(y.id, ye.getValue());
        }
        byEra.put(ee.getKey(), ids);
      }
      rates.put(ce.getKey(), byEra);
    }
    Set<String> used = new HashSet<>(); // 「コイン|時代|id」
    for (Y y : byNo.values()) for (Map<String, Object> o : y.obtain) {
      if (!"gasha".equals(o.get("method"))) continue;
      String era = str(o.get("era"));
      boolean any = false;
      for (Object co : list(o.get("coins"))) {
        Map<String, Object> c = map(co);
        var byEra = rates.get(str(c.get("coin")));
        if (byEra == null) continue; // 記事にないコイン（鬼ガシャ・エラベールなど）は「不明」のまま
        Object rate = null;
        for (String k : era.isEmpty() ? List.of("all", "present", "past") : List.of(era, "all")) {
          rate = byEra.getOrDefault(k, Map.of()).get(y.id);
          if (rate != null) { used.add(c.get("coin") + "|" + k + "|" + y.id); break; }
        }
        // 色コインの現代・過去は enjoi の表の色から決めていたので、記事と逆なら記事に合わせる
        String other = era.equals("present") ? "past" : era.equals("past") ? "present" : null;
        if (rate == null && other != null && list(o.get("coins")).size() == 1 && byEra.getOrDefault(other, Map.of()).get(y.id) != null) {
          rate = byEra.get(other).get(y.id);
          used.add(c.get("coin") + "|" + other + "|" + y.id);
          o.put("era", other);
          o.put("note", str(o.get("note")).replace(era.equals("past") ? "過去" : "現代", other.equals("past") ? "過去" : "現代"));
          rateNotes.add(y.name + "（No." + y.no + "）: " + c.get("coin") + " の時代を記事に合わせた（enjoi の表の色: " + eraJa(era) + " → 記事: " + eraJa(other) + "）");
        }
        if (rate == null) { rateNotes.add(y.name + "（No." + y.no + "）: " + c.get("coin") + (era.isEmpty() ? "" : "（" + eraJa(era) + "）") + " で出ることになっているが、記事に確率がない"); continue; }
        c.put("rate", rate);
        c.put("rateSource", src);
        any = true;
      }
      if (any) {
        o.put("note", str(o.get("note")).replace("出現確率は公表されていない", "出現確率は公式には公表されていない（％は出典の解析記事の値）"));
        y.sources.add(src);
      }
    }
    // 記事にあって図鑑のガシャにないものは、入手方法に足す（gamepedia のコインの一覧に載っていない組み合わせ）
    for (var ce : rates.entrySet()) for (var ee : ce.getValue().entrySet()) for (var ie : ee.getValue().entrySet()) {
      String coin = ce.getKey(), era = ee.getKey(), id = ie.getKey();
      if (used.contains(coin + "|" + era + "|" + id)) continue;
      Y y = byId(id);
      // 色コインは時代ごとの入手方法に、それ以外は時代の区別のない入手方法に入れる
      String key = COLOR_COINS.contains(coin) ? era : "";
      Map<String, Object> o = y.obtain.stream().filter(x -> "gasha".equals(x.get("method")) && str(x.get("era")).equals(key)).findFirst().orElse(null);
      if (o == null) {
        o = obtain(y, "gasha", (key.isEmpty() ? "" : eraJa(key) + "の妖怪ガシャ。") + "出現確率は公式には公表されていない（％は出典の解析記事の値）");
        o.put("coins", new ArrayList<>());
        o.put("era", key.isEmpty() ? null : key);
        y.obtain.add(o);
      }
      Map<String, Object> cm = new LinkedHashMap<>();
      cm.put("coin", coin);
      cm.put("rate", ie.getValue());
      cm.put("rateSource", src);
      @SuppressWarnings("unchecked") List<Object> cs = (List<Object>) o.get("coins");
      cs.add(cm);
      y.sources.add(src);
      rateNotes.add(y.name + "（No." + y.no + "）: " + coin + (era.equals("all") ? "" : "（" + eraJa(era) + "）") + " は記事にだけあるので、入手方法に足した");
    }
    // 野生 → その他の順を保つ
    List<String> order = List.of("wild", "evolve", "fusion", "gasha", "quest", "boss", "legend", "qr", "password", "trade", "other");
    for (Y y : byNo.values()) y.obtain.sort(Comparator.comparingInt(o -> order.indexOf(str(o.get("method")))));
  }

  static String eraJa(String era) { return era.equals("past") ? "過去" : "現代"; }

  // ================= 仕上げ =================

  static void finish() {
    for (Y y : byNo.values()) {
      Map<String, Object> st = stats(y);
      for (String k : List.of("hp", "str", "spr", "def", "spd")) if (st.get(k) == null) { notes.add(y.name + "（" + y.id + "）: Lv60のステータスが見つからない"); break; }
      // 2つ以上の出典で比べられた項目がすべて一致していれば verified
      boolean open = y.conflicts.stream().anyMatch(c -> !Boolean.TRUE.equals(c.get("resolved")));
      y.verified = !open && Stream.of("名前", "ランク", "種族", "好物", "進化").noneMatch(y.singleSource::contains);
    }
  }

  static Map<String, Object> stats(Y y) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("level", 60);
    s.put("hp", intOrNull(y.gp.get("hp")));
    s.put("str", intOrNull(y.gp.get("attack")));
    s.put("spr", intOrNull(y.gp.get("magic")));
    s.put("def", intOrNull(y.gp.get("defense")));
    s.put("spd", intOrNull(y.gp.get("speed")));
    return s;
  }

  static Map<String, Object> toJson(Y y, int order) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("id", y.id);
    o.put("dexNo", y.no);
    o.put("isLegend", y.legend);
    o.put("isDistribution", y.distribution);
    o.put("sortOrder", order);
    o.put("name", y.name);
    o.put("kana", y.kana);
    o.put("tribe", y.tribe);
    o.put("rank", y.rank);
    o.put("versions", y.versions);
    o.put("stats", stats(y));
    o.put("obtain", y.obtain);
    o.put("legendUnlock", y.legendUnlock);
    o.put("favoriteFood", y.favorite);
    o.put("skill", skill(y));
    o.put("moves", moves(y));
    o.put("soul", y.soul);
    o.put("evolvesTo", y.evolvesTo);
    o.put("evolvesFrom", y.evolvesFrom);
    o.put("fusion", y.fusion);
    o.put("sources", new ArrayList<>(y.sources));
    o.put("verified", y.verified);
    o.put("conflicts", y.conflicts);
    return o;
  }

  // ================= レポート =================

  static String report(List<Y> all) {
    StringBuilder b = new StringBuilder();
    b.append("# データ収集レポート\n\n");
    b.append("`scripts/build/Build.java` が自動で書き出す。手で直さない（直すときは `scripts/data/overrides.json` を変えて作り直す）。\n\n");
    b.append("## 件数\n\n");
    b.append("- 収録: **").append(all.size()).append(" 体**（gamepedia の妖怪大辞典の全件）\n");
    b.append("- Wiki の種族ページの一覧: ").append(all.stream().filter(y -> y.wiki != null).count()).append(" 体\n");
    b.append("- enjoi の全妖怪リスト: ").append(all.stream().filter(y -> y.enjoi != null).count()).append(" 体\n");
    b.append("- Game8 の全妖怪一覧: ").append(all.stream().filter(y -> y.g8 != null).count()).append(" 体（ボスを除く）\n");
    b.append("- verified（2つ以上の出典で比べた項目がすべて一致）: ").append(all.stream().filter(y -> y.verified).count()).append(" 体\n\n");
    b.append("### 1つの出典にしかない妖怪\n\n");
    List<Y> noWiki = all.stream().filter(y -> y.wiki == null).toList(), noEnjoi = all.stream().filter(y -> y.enjoi == null).toList();
    b.append("- Wiki にない: ").append(noWiki.isEmpty() ? "なし" : noWiki.stream().map(y -> y.name + "（No." + y.no + "）").collect(Collectors.joining("、"))).append('\n');
    b.append("- enjoi にない: ").append(noEnjoi.isEmpty() ? "なし" : noEnjoi.stream().map(y -> y.name + "（No." + y.no + "）").collect(Collectors.joining("、"))).append('\n');
    b.append("- gamepedia にない: なし（gamepedia を骨格にしたため。Wiki・enjoi にだけある番号は下の「メモ」に出る）\n\n");

    for (boolean resolved : new boolean[]{false, true}) {
      b.append(resolved ? "\n## 食い違い（多数決か、書き方の小さな違いとして決めたもの）\n\n" : "## 食い違い（ユーザーに確認したいもの）\n\n");
      b.append("| 妖怪 | 項目 | 出典ごとの値 | 採用 | 決め方 |\n|---|---|---|---|---|\n");
      Set<String> seen = new HashSet<>();
      for (Y y : all) for (Map<String, Object> c : y.conflicts) {
        if (Boolean.TRUE.equals(c.get("resolved")) != resolved) continue;
        @SuppressWarnings("unchecked") Map<String, String> v = (Map<String, String>) c.get("values");
        String vals = v.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).collect(Collectors.joining(" / "));
        if (str(c.get("field")).startsWith("進化レベル") && !seen.add(c.get("field") + vals)) continue;   // 進化元と進化先で同じ行が2回出ないように
        b.append("| ").append(y.name).append("（No.").append(y.no).append("） | ").append(c.get("field")).append(" | ")
         .append(vals).append(" | ").append(c.get("chosen")).append(" | ").append(c.get("note")).append(" |\n");
      }
    }
    b.append("\n## バージョンが確かでない妖怪\n\n");
    b.append("連動ダンジョンなどで入手する妖怪。今は「真打のみ」にしてある。\n\n");
    for (Y y : all) if (y.versionUnsure) b.append("- ").append(y.name).append("（No.").append(y.no).append("）\n");

    b.append("\n## 1つの出典でしか確かめられなかった項目\n\n");
    b.append("- ステータス（Lv60）・読みがな: すべて gamepedia のみ（全妖怪の一覧がほかに見つからなかった）\n");
    b.append("- 技・スキル: gamepedia のみ。ただし ").append(all.stream().filter(y -> y.movesChecked).count())
     .append(" 体（レジェンド・怪魔・特別な妖怪など）は enjoi の技の表と名前を比べた\n");
    b.append("- 魂の効果: すべて Game8 の魂一覧のみ\n");
    b.append("- ガシャのコイン: gamepedia のみ（現代・過去の区別は enjoi のガシャ表の色から）\n");
    b.append("- ガシャの出現確率: 公式には公表されていない。scripts/data/gasha-rates.json（解析記事の値）だけ。記事にない組み合わせは「不明」\n");
    Map<String, List<String>> single = new TreeMap<>();
    for (Y y : all) for (String f : new LinkedHashSet<>(y.singleSource)) single.computeIfAbsent(f, k -> new ArrayList<>()).add(y.name);
    for (var e : single.entrySet()) b.append("- ").append(e.getKey()).append("（").append(e.getValue().size()).append(" 体）: ")
      .append(e.getValue().size() > 40 ? String.join("、", e.getValue().subList(0, 40)) + " ほか" : String.join("、", e.getValue())).append('\n');

    b.append("\n## 名前を特定できなかったもの\n\n");
    if (unresolved.isEmpty()) b.append("なし\n");
    for (String u : new LinkedHashSet<>(unresolved)) b.append("- ").append(u).append('\n');
    b.append("\n## ガシャの確率で合わなかったもの\n\n");
    if (rateNotes.isEmpty()) b.append("なし\n");
    for (String n : rateNotes) b.append("- ").append(n).append('\n');
    b.append("\n## メモ\n\n");
    if (notes.isEmpty()) b.append("なし\n");
    for (String n : notes) b.append("- ").append(n).append('\n');
    return b.toString();
  }

  // ================= 小道具 =================

  static Y byId(String id) { return byNo.get(Integer.parseInt(id.substring(1))); }

  // 2つの文字列の編集距離（何文字直せば同じになるか）
  static int distance(String a, String b) {
    int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) prev[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      cur[0] = i;
      for (int j = 1; j <= b.length(); j++)
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
      int[] t = prev; prev = cur; cur = t;
    }
    return prev[b.length()];
  }
  static Map<String, Object> mapOf(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }
  static String read(String rel) throws Exception { return Files.readString(RAW.resolve(rel)); }
  static String orNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
  static Integer intOrNull(Object o) {
    String s = str(o);
    return s.matches("\\d+") ? Integer.valueOf(s) : null;
  }

  // HTML を行のテキストにする（表のセルは " | " でつなぐ）
  static List<String> htmlLines(String h) {
    h = h.replaceAll("(?is)<script.*?</script>", "").replaceAll("(?is)<style.*?</style>", "").replaceAll("(?s)<!--.*?-->", "");
    h = h.replaceAll("\\s+", " ");
    h = h.replaceAll("(?i)<tr[^>]*>", "\n").replaceAll("(?i)<br\\s*/?>", " / ").replaceAll("(?i)<t[dh][^>]*>", " | ")
         .replaceAll("(?i)</?(p|div|h\\d|li|table|ul)[^>]*>", "\n").replaceAll("<[^>]+>", "")
         .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#8211;", "－");
    List<String> out = new ArrayList<>();
    for (String line : h.split("\n")) { String t = line.replaceAll("[ 　]+", " ").trim(); if (!t.isEmpty() && !t.equals("|")) out.add(t); }
    return out;
  }

  static String[] cells(String line) {
    if (!line.startsWith("|")) return new String[0];
    return Arrays.stream(line.substring(1).split("\\|")).map(String::trim).toArray(String[]::new);
  }
}
