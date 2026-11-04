import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

// ホーム画面のアイコンを作る（公式のイラストは使わず、文字と色だけ）
// 使い方: java scripts/icons/MakeIcons.java icons
// 背景は全面を塗る（Android の丸や角丸に切り抜かれても欠けないよう、文字は中央の 6 割に収める）
public class MakeIcons {
  static final Color BG = new Color(0xd9482b);
  static final Color FG = Color.WHITE;

  public static void main(String[] a) throws Exception {
    Path out = Path.of(a.length > 0 ? a[0] : "icons");
    Files.createDirectories(out);
    for (int size : new int[]{192, 512}) ImageIO.write(draw(size), "png", out.resolve("icon-" + size + ".png").toFile());
    ImageIO.write(draw(180), "png", out.resolve("apple-touch-icon.png").toFile());
    System.out.println("できました: " + out.toAbsolutePath());
  }

  static BufferedImage draw(int size) {
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.setColor(BG);
    g.fillRect(0, 0, size, size);
    // 「妖」の字の外形を、中央の 56% の大きさにそろえて塗る
    Font font = pickFont().deriveFont(Font.BOLD, size);
    GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), "妖");
    Shape glyph = gv.getOutline();
    Rectangle2D b = glyph.getBounds2D();
    double scale = size * 0.56 / Math.max(b.getWidth(), b.getHeight());
    g.translate(size / 2.0, size / 2.0);
    g.scale(scale, scale);
    g.translate(-b.getCenterX(), -b.getCenterY());
    g.setColor(FG);
    g.fill(glyph);
    g.dispose();
    return img;
  }

  static Font pickFont() {
    String[] names = {"Yu Gothic UI", "Yu Gothic", "Meiryo", "Noto Sans JP", "Hiragino Sans"};
    java.util.Set<String> have = java.util.Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
    for (String n : names) if (have.contains(n)) return new Font(n, Font.BOLD, 12);
    return new Font(Font.SANS_SERIF, Font.BOLD, 12);
  }
}
