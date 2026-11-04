import java.util.*;

// 最小限の JSON 読み書き（外部ライブラリなしで動かすため）
public final class Json {
  private final String s;
  private int i;

  private Json(String s) { this.s = s; }

  public static Object parse(String text) {
    Json p = new Json(text);
    p.ws();
    Object v = p.value();
    p.ws();
    if (p.i != p.s.length()) throw p.error("末尾に余分な文字");
    return v;
  }

  private RuntimeException error(String msg) { return new IllegalArgumentException("JSON " + msg + " (位置 " + i + ")"); }

  private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

  private Object value() {
    if (i >= s.length()) throw error("途中で終わった");
    char c = s.charAt(i);
    switch (c) {
      case '{': return object();
      case '[': return array();
      case '"': return string();
      case 't': expect("true"); return Boolean.TRUE;
      case 'f': expect("false"); return Boolean.FALSE;
      case 'n': expect("null"); return null;
      default: return number();
    }
  }

  private void expect(String w) {
    if (!s.startsWith(w, i)) throw error(w + " のはず");
    i += w.length();
  }

  private Map<String, Object> object() {
    Map<String, Object> m = new LinkedHashMap<>();
    i++; ws();
    if (s.charAt(i) == '}') { i++; return m; }
    while (true) {
      ws();
      String k = string();
      ws();
      if (s.charAt(i++) != ':') throw error(": がない");
      ws();
      m.put(k, value());
      ws();
      char c = s.charAt(i++);
      if (c == '}') return m;
      if (c != ',') throw error(", がない");
    }
  }

  private List<Object> array() {
    List<Object> a = new ArrayList<>();
    i++; ws();
    if (s.charAt(i) == ']') { i++; return a; }
    while (true) {
      ws();
      a.add(value());
      ws();
      char c = s.charAt(i++);
      if (c == ']') return a;
      if (c != ',') throw error(", がない");
    }
  }

  private String string() {
    if (s.charAt(i) != '"') throw error("文字列のはず");
    i++;
    StringBuilder b = new StringBuilder();
    while (true) {
      char c = s.charAt(i++);
      if (c == '"') return b.toString();
      if (c != '\\') { b.append(c); continue; }
      char e = s.charAt(i++);
      switch (e) {
        case 'n': b.append('\n'); break;
        case 't': b.append('\t'); break;
        case 'r': b.append('\r'); break;
        case 'b': b.append('\b'); break;
        case 'f': b.append('\f'); break;
        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
        default: b.append(e);
      }
    }
  }

  private Object number() {
    int st = i;
    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
    String n = s.substring(st, i);
    if (n.isEmpty()) throw error("値がない");
    if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
    return Long.parseLong(n);
  }

  // ---- 書き出し（短い配列・オブジェクトは1行にまとめる） ----
  public static String write(Object v) {
    StringBuilder b = new StringBuilder();
    write(b, v, 0);
    return b.append('\n').toString();
  }

  private static void write(StringBuilder b, Object v, int depth) {
    String flat = flat(v);
    if (flat.length() + depth * 2 <= 110 || !(v instanceof Map || v instanceof List)) { b.append(flat); return; }
    String pad = "  ".repeat(depth + 1);
    if (v instanceof Map<?, ?> m) {
      b.append("{\n");
      int n = 0;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        b.append(pad).append(quote(e.getKey().toString())).append(": ");
        write(b, e.getValue(), depth + 1);
        if (++n < m.size()) b.append(',');
        b.append('\n');
      }
      b.append("  ".repeat(depth)).append('}');
    } else {
      List<?> a = (List<?>) v;
      b.append("[\n");
      for (int k = 0; k < a.size(); k++) {
        b.append(pad);
        write(b, a.get(k), depth + 1);
        if (k + 1 < a.size()) b.append(',');
        b.append('\n');
      }
      b.append("  ".repeat(depth)).append(']');
    }
  }

  private static String flat(Object v) {
    if (v == null) return "null";
    if (v instanceof String str) return quote(str);
    if (v instanceof Number || v instanceof Boolean) return v.toString();
    StringBuilder b = new StringBuilder();
    if (v instanceof Map<?, ?> m) {
      b.append("{ ");
      int n = 0;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (n++ > 0) b.append(", ");
        b.append(quote(e.getKey().toString())).append(": ").append(flat(e.getValue()));
      }
      return m.isEmpty() ? "{}" : b.append(" }").toString();
    }
    List<?> a = (List<?>) v;
    if (a.isEmpty()) return "[]";
    b.append('[');
    for (int k = 0; k < a.size(); k++) { if (k > 0) b.append(", "); b.append(flat(a.get(k))); }
    return b.append(']').toString();
  }

  private static String quote(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"': b.append("\\\""); break;
        case '\\': b.append("\\\\"); break;
        case '\n': b.append("\\n"); break;
        case '\t': b.append("\\t"); break;
        default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
      }
    }
    return b.append('"').toString();
  }
}
