// 合成一张停车场的照片，给 PlatePhotoOcrTest 用真模型读：app/src/test/resources/ppocr/plate-photo.jpg
//
//   java tools/test-images/PlatePhoto.java app/src/test/resources/ppocr/plate-photo.jpg
//
// 七块牌子，号码全是编的，一块一排（同一排的框会被 OCR 串成一行）：
// - 蓝牌「苏B·1LE22」，「1」画成一根竖条：用户那张照片上就是这样的「1」，OCR 读成「I」；
// - 小型新能源「粤B·D12345」，上浅下绿的渐变；
// - 黄色双层牌「苏·E」/「05863」、日本的「品川 330」/「さ 12-34」：OCR 读成上下两行；
// - 英国「AB12 CDE」、法国「AB-123-CD」：写法独特，打码；
// - 德国「B MW 1234」：写法不够独特，单独成行时只圈出。
// 字体按系统上有的挑（雅黑或黑体、MS Gothic 或 Noto CJK、Arial Narrow 或 DejaVu）；图已经提交，单测不依赖字体。
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class PlatePhoto {

    static final int W = 900, H = 1390;
    static final Color BLUE = new Color(28, 96, 196), WHITE = new Color(240, 244, 248), BLACK = new Color(20, 20, 22);
    static final Color YELLOW = new Color(250, 196, 30), JP_GREEN = new Color(0, 100, 55), EU_BLUE = new Color(0, 51, 153);

    static Font han, kana, latin;

    public static void main(String[] args) throws Exception {
        han = pick("Noto Sans SC", "Noto Sans CJK SC", "Microsoft YaHei", "SimHei", "WenQuanYi Zen Hei").deriveFont(Font.BOLD);
        kana = pick("MS Gothic", "Yu Gothic", "Noto Sans JP", "Noto Sans CJK JP");
        latin = pick("Arial Narrow", "Arial", "DejaVu Sans Condensed", "DejaVu Sans").deriveFont(Font.BOLD);

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 地面：灰色、带噪点；每块牌子装在一截深色的车头上
        g.setPaint(new GradientPaint(0, 0, new Color(150, 152, 150), 0, H, new Color(108, 110, 112)));
        g.fillRect(0, 0, W, H);
        Random rnd = new Random(7);
        for (int i = 0; i < 9000; i++) {
            int v = 90 + rnd.nextInt(80);
            g.setColor(new Color(v, v, v + rnd.nextInt(8)));
            g.fillRect(rnd.nextInt(W), rnd.nextInt(H), 2, 2);
        }

        int y = 30;
        y = car(g, 120, y, 340, 96, (p, w, h) -> cnBlue(p, w, h));
        y = car(g, 400, y, 370, 96, (p, w, h) -> cnNev(p, w, h));
        y = car(g, 200, y, 230, 120, (p, w, h) -> cnYellowTwoRow(p, w, h));
        y = car(g, 470, y, 220, 112, (p, w, h) -> japan(p, w, h));
        y = car(g, 90, y, 330, 76, (p, w, h) -> uk(p, w, h));
        y = car(g, 430, y, 360, 76, (p, w, h) -> euro(p, w, h, "F", "AB-123-CD"));
        car(g, 160, y, 340, 76, (p, w, h) -> euro(p, w, h, "D", "B MW 1234"));
        g.dispose();

        writeJpeg(img, new File(args[0]));
    }

    interface PlateArt { void draw(Graphics2D g, int w, int h); }

    /** 一截车头，牌子在中间。返回下一辆车的 y。 */
    static int car(Graphics2D g, int x, int y, int pw, int ph, PlateArt art) {
        int cw = pw + 120, ch = ph + 70;
        g.setColor(new Color(46, 48, 54));
        g.fillRoundRect(x - 60, y, cw, ch, 40, 40);
        g.setColor(new Color(70, 72, 80));
        g.fillRect(x - 40, y + 10, cw - 40, 10);
        Graphics2D p = (Graphics2D) g.create(x, y + 35, pw, ph);
        art.draw(p, pw, ph);
        p.dispose();
        return y + ch + 30;
    }

    static void cnBlue(Graphics2D g, int w, int h) {
        plate(g, w, h, BLUE, WHITE);
        int glyph = (int) (h * 0.62), top = (h - glyph) / 2;
        int x = 16;
        x = glyphs(g, "苏", han, x, top, glyph, 0.9, WHITE) + 6;
        x = glyphs(g, "B", latin, x, top, glyph, 0.55, WHITE) + 10;
        g.fillOval(x, h / 2 - 4, 8, 8);
        x += 22;
        // 「1」是一根竖条
        g.fillRect(x + 10, top, (int) (glyph * 0.11), glyph);
        x += 34;
        for (String c : List.of("L", "E", "2", "2")) x = glyphs(g, c, latin, x, top, glyph, 0.55, WHITE) + 9;
    }

    static void cnNev(Graphics2D g, int w, int h) {
        g.setPaint(new GradientPaint(0, 0, new Color(238, 248, 240), 0, h, new Color(70, 184, 100)));
        g.fillRoundRect(0, 0, w, h, 12, 12);
        g.setColor(BLACK);
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(4, 4, w - 9, h - 9, 10, 10);
        int glyph = (int) (h * 0.6), top = (h - glyph) / 2;
        int x = 14;
        x = glyphs(g, "粤", han, x, top, glyph, 0.9, BLACK) + 6;
        x = glyphs(g, "B", latin, x, top, glyph, 0.5, BLACK) + 8;
        g.fillOval(x, h / 2 - 4, 8, 8);
        x += 18;
        for (char c : "D12345".toCharArray()) x = glyphs(g, String.valueOf(c), latin, x, top, glyph, 0.5, BLACK) + 7;
    }

    static void cnYellowTwoRow(Graphics2D g, int w, int h) {
        plate(g, w, h, YELLOW, BLACK);
        int small = (int) (h * 0.26);
        int x = 62;
        x = glyphs(g, "苏", han, x, 10, small, 0.9, BLACK) + 10;
        g.fillOval(x, 10 + small / 2 - 3, 7, 7);
        x += 18;
        glyphs(g, "E", latin, x, 10, small, 0.55, BLACK);
        int big = (int) (h * 0.48);
        x = 22;
        for (char c : "05863".toCharArray()) x = glyphs(g, String.valueOf(c), latin, x, h - big - 10, big, 0.52, BLACK) + 8;
    }

    static void japan(Graphics2D g, int w, int h) {
        g.setColor(WHITE);
        g.fillRoundRect(0, 0, w, h, 12, 12);
        g.setColor(JP_GREEN);
        g.setStroke(new BasicStroke(4f));
        g.drawRoundRect(3, 3, w - 7, h - 7, 10, 10);
        int small = (int) (h * 0.2);
        int x = 52;
        x = glyphs(g, "品川", kana, x, 9, small, 1.0, JP_GREEN) + 12;
        glyphs(g, "330", latin, x, 9, small, 0.6, JP_GREEN);
        int kanaH = (int) (h * 0.24), big = (int) (h * 0.5);
        glyphs(g, "さ", kana, 14, h - kanaH - 22, kanaH, 1.0, JP_GREEN);
        x = 62;
        for (char c : "12-34".toCharArray()) {
            x = c == '-' ? dash(g, x, h - 12 - big / 2, big, JP_GREEN) : glyphs(g, String.valueOf(c), latin, x, h - big - 12, big, 0.5, JP_GREEN) + 5;
        }
    }

    static void uk(Graphics2D g, int w, int h) {
        plate(g, w, h, YELLOW, BLACK);
        int glyph = (int) (h * 0.66), top = (h - glyph) / 2;
        int x = 22;
        for (char c : "AB12".toCharArray()) x = glyphs(g, String.valueOf(c), latin, x, top, glyph, 0.55, BLACK) + 5;
        x += 30;
        for (char c : "CDE".toCharArray()) x = glyphs(g, String.valueOf(c), latin, x, top, glyph, 0.55, BLACK) + 5;
    }

    /** 欧盟样式：左边蓝条上一圈星、下面国别字母。 */
    static void euro(Graphics2D g, int w, int h, String country, String number) {
        plate(g, w, h, WHITE, BLACK);
        g.setColor(EU_BLUE);
        g.fillRoundRect(3, 3, 40, h - 6, 8, 8);
        g.setColor(YELLOW);
        for (int k = 0; k < 12; k++) {
            double a = k * Math.PI / 6;
            g.fillOval((int) (23 + 11 * Math.cos(a)) - 2, (int) (22 + 11 * Math.sin(a)) - 2, 4, 4);
        }
        glyphs(g, country, latin, 14, h - 30, 18, 0.6, WHITE);
        int glyph = (int) (h * 0.64), top = (h - glyph) / 2;
        int x = 58;
        for (char c : number.toCharArray()) {
            if (c == ' ') { x += 22; continue; }
            x = c == '-' ? dash(g, x, top + glyph / 2, glyph, BLACK) : glyphs(g, String.valueOf(c), latin, x, top, glyph, 0.55, BLACK) + 4;
        }
    }

    /** 连字符：字高 [hh] 的字中间一道短横，中线在 [mid]。返回右沿再加点空。 */
    static int dash(Graphics2D g, int x, int mid, int hh, Color c) {
        int len = (int) (hh * 0.3), t = Math.max(3, hh / 9);
        g.setColor(c);
        g.fillRect(x + 4, mid - t / 2, len, t);
        return x + len + 10;
    }

    static void plate(Graphics2D g, int w, int h, Color bg, Color rim) {
        g.setColor(bg);
        g.fillRoundRect(0, 0, w, h, 12, 12);
        g.setColor(rim);
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(4, 4, w - 9, h - 9, 10, 10);
    }

    /** 画一段字：墨迹高 [hh] 像素、上沿 [top]，横向按 [aspect]（宽/高）压扁。返回右沿。 */
    static int glyphs(Graphics2D g, String s, Font font, int x, int top, int hh, double aspect, Color c) {
        FontRenderContext frc = g.getFontRenderContext();
        GlyphVector gv = font.deriveFont(100f).createGlyphVector(frc, s);
        Shape outline = gv.getOutline();
        Rectangle2D b = outline.getBounds2D();
        double sy = hh / b.getHeight();
        double sx = Math.min(sy, hh * aspect * s.length() / b.getWidth());
        AffineTransform t = new AffineTransform();
        t.translate(x, top);
        t.scale(sx, sy);
        t.translate(-b.getX(), -b.getY());
        g.setColor(c);
        g.fill(t.createTransformedShape(outline));
        return (int) Math.round(x + b.getWidth() * sx);
    }

    static Font pick(String... families) {
        List<String> have = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(java.util.Locale.ROOT));
        for (String f : families) if (have.contains(f)) return new Font(f, Font.PLAIN, 100);
        throw new IllegalStateException("no font among " + Arrays.toString(families));
    }

    static void writeJpeg(BufferedImage img, File out) throws Exception {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(0.88f);
        try (ImageOutputStream s = ImageIO.createImageOutputStream(out)) {
            w.setOutput(s);
            w.write(null, new IIOImage(img, null, null), p);
        }
        w.dispose();
    }
}
