import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

// 図鑑データの元になるページを攻略サイトから集めて、キャッシュのフォルダに保存する。
// 使い方: java scripts/fetch/Fetch.java <キャッシュのフォルダ>
//   - 保存済みのファイルは取りに行かない（何度実行しても同じページを取り直さない）
//   - 各サイトの robots.txt で禁止されていないページだけを、間隔を空けて取る
//     （gamepedia と enjoi は 3 秒、Wiki は 429 を返されたので 20 秒、Game8 は一覧の 4 ページだけ）
//   - 取るのは事実データだけ。解説文は Build.java でも使わない
public class Fetch {
  static Path OUT;
  static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

  public static void main(String[] a) throws Exception {
    OUT = Path.of(a[0]);
    // gamepedia（WordPress の REST API。robots.txt で禁止されていない）
    String[] types = {"monsters", "monster-locations", "evolutions", "gasha-drops", "monster-attacks", "monster-specials",
        "monster-skills", "monster-possesions", "monster-magics"};
    for (String t : types) gpType(t);
    for (Object o : gpMonsterIds()) get("https://gamepedia.jp/youkai-yw2/monsters/" + o, "gppage/" + o + ".html", "UTF-8", 3);
    // enjoi（全妖怪リスト・進化・合成・ガシャ・限定妖怪・怪魔・レジェンド）
    get("https://enjoi7.sakura.ne.jp/youkai-wach2/youkai_list.html", "enjoi/youkai_list.html", "Shift_JIS", 3);
    get("https://enjoi7.sakura.ne.jp/youkai-wach2/youkai_kaima.html", "enjoi/youkai_kaima.html", "Shift_JIS", 3);
    get("https://enjoi7.sakura.ne.jp/youkai-wach2/youkai_legend.html", "enjoi/youkai_legend.html", "Shift_JIS", 3);
    for (String p : List.of("youkai_shinka", "youkai_shinka_gousei", "gasya_2", "youkai_gentei"))
      get("https://enjoi.sakura.ne.jp/youkai-watch2/" + p + ".html", "enjoi1/" + p + ".html", "Shift_JIS", 3);
    // 元祖・本家 Wiki*（種族ごとの一覧）
    String[] tribes = {"イサマシ族", "フシギ族", "ゴーケツ族", "プリチー族", "ポカポカ族", "ウスラカゲ族", "ブキミー族", "ニョロロン族", "怪魔"};
    for (int i = 0; i < tribes.length; i++)
      get("https://wikiwiki.jp/2ganso-honke/" + URLEncoder.encode(tribes[i], StandardCharsets.UTF_8), "wiki/t" + (i + 1) + ".html", "UTF-8", 20);
    // Game8（全妖怪一覧・魂一覧・合成レシピ一覧）
    get("https://game8.jp/youkai-watch2/491365", "game8/list.html", "UTF-8", 5);
    get("https://game8.jp/youkai-watch2/504902", "game8/souls.html", "UTF-8", 5);
    get("https://game8.jp/youkai-watch2/504828", "game8/evo.html", "UTF-8", 5);

    enjoiTsv();
    wikiTsv(tribes);
    System.out.println("できました: " + OUT);
  }

  // ---- 取得 ----

  static void get(String url, String rel, String charset, int waitSec) throws Exception {
    Path f = OUT.resolve(rel);
    if (Files.exists(f)) return;
    Files.createDirectories(f.getParent());
    HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "yw2-zukan-personal/0.1").build(),
        HttpResponse.BodyHandlers.ofByteArray());
    if (r.statusCode() != 200) throw new IllegalStateException(r.statusCode() + " " + url + "（時間をおいてやり直してください）");
    Files.writeString(f, new String(r.body(), Charset.forName(charset)));
    System.out.println("保存 " + rel);
    Thread.sleep(waitSec * 1000L);
  }

  static void gpType(String t) throws Exception {
    Path meta = OUT.resolve("gp/" + t + ".pages");
    int pages = Files.exists(meta) ? Integer.parseInt(Files.readString(meta).trim()) : Integer.MAX_VALUE;
    for (int p = 1; p <= pages; p++) {
      Path f = OUT.resolve("gp/" + t + "-" + p + ".json");
      if (Files.exists(f) && Files.exists(meta)) continue;
      Files.createDirectories(f.getParent());
      HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://gamepedia.jp/youkai-yw2/wp-json/wp/v2/" + t + "?per_page=100&page=" + p))
          .header("User-Agent", "yw2-zukan-personal/0.1").build(), HttpResponse.BodyHandlers.ofByteArray());
      if (r.statusCode() != 200) throw new IllegalStateException(r.statusCode() + " " + t);
      Files.write(f, r.body());
      pages = Integer.parseInt(r.headers().firstValue("X-WP-TotalPages").orElse("1"));
      Files.writeString(meta, String.valueOf(pages));
      System.out.println("保存 gp/" + t + "-" + p);
      Thread.sleep(3000);
    }
  }

  static List<Object> gpMonsterIds() throws Exception {
    List<Object> ids = new ArrayList<>();
    int pages = Integer.parseInt(Files.readString(OUT.resolve("gp/monsters.pages")).trim());
    Pattern id = Pattern.compile("\"link\":\"https:\\\\/\\\\/gamepedia\\.jp\\\\/youkai-yw2\\\\/monsters\\\\/(\\d+)\"");
    for (int p = 1; p <= pages; p++) {
      Matcher m = id.matcher(Files.readString(OUT.resolve("gp/monsters-" + p + ".json")));
      while (m.find()) ids.add(m.group(1));
    }
    return ids;
  }

  // ---- 一覧表を TSV にする ----

  // enjoi の全妖怪リスト → 番号, 名前, ランク, 出現場所, 好物, その他
  static void enjoiTsv() throws Exception {
    String html = Files.readString(OUT.resolve("enjoi/youkai_list.html"));
    Matcher row = Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL).matcher(html);
    Pattern td = Pattern.compile("<td([^>]*)>(.*?)</td>", Pattern.DOTALL);
    StringBuilder sb = new StringBuilder();
    while (row.find()) {
      Matcher m = td.matcher(row.group(1));
      List<String> cells = new ArrayList<>(), attrs = new ArrayList<>();
      while (m.find()) { attrs.add(m.group(1)); cells.add(text(m.group(2))); }
      if (cells.size() < 4 || !attrs.get(0).contains("#666666") || !cells.get(0).matches("\\d+")) continue;
      sb.append(cells.get(0)).append('\t').append(cells.get(1)).append('\t').append(cells.get(2)).append('\t').append(cells.get(3)).append('\t')
        .append(cells.size() > 4 ? cells.get(4) : "").append('\t').append(cells.size() > 5 ? cells.get(5) : "").append('\n');
    }
    Files.writeString(OUT.resolve("enjoi.tsv"), sb);
  }

  // Wiki の種族ページ → 番号, 名前, ランク, 種族
  static void wikiTsv(String[] tribes) throws Exception {
    StringBuilder sb = new StringBuilder();
    Pattern row = Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL), td = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);
    for (int i = 0; i < tribes.length; i++) {
      String tribe = tribes[i].replace("族", "");
      Matcher r = row.matcher(Files.readString(OUT.resolve("wiki/t" + (i + 1) + ".html")));
      while (r.find()) {
        Matcher m = td.matcher(r.group(1));
        List<String> c = new ArrayList<>();
        while (m.find()) c.add(m.group(1).replaceAll("<[^>]+>", "").replace("&amp;", "&").trim());
        if (c.size() >= 3 && c.get(0).matches("\\d+")) sb.append(c.get(0)).append('\t').append(c.get(1)).append('\t').append(c.get(2)).append('\t').append(tribe).append('\n');
      }
    }
    Files.writeString(OUT.resolve("wiki.tsv"), sb);
  }

  static String text(String s) {
    return s.replaceAll("<br\\s*/?>", " / ").replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&amp;", "&").replaceAll("\\s+", " ").trim();
  }
}
