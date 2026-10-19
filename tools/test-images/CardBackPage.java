// 合成银行卡的背面，给 PP-OCR 的真模型单测用：
//
//   java tools/test-images/CardBackPage.java app/src/test/resources/ppocr
//
// 写出三张图：
// - card-back-cn.jpg：深色的银联信用卡背面。磁条下面一行小字「持卡人签名 AUTHORIZED SIGNATURE」，
//   签名条上斜着底纹，右头印着斜体的卡号末四位和安全码「8810 357」，没有字段名；
//   右边一块镭射标，下面是章程、客服热线、银行名和银联标；
// - card-back-en.jpg：浅色的 Visa 卡背面。签名条上面「AUTHORIZED SIGNATURE」、下面「NOT VALID UNLESS SIGNED」，
//   安全码「482」单独印在签名条右边的白框里；下面是发卡说明、客服电话；
// - card-back-photo.jpg：card-back-cn 放在木纹桌上拍的样子，逆时针歪 7°，压缩得狠一点。
//
// 内容全是编的：银行、网址、热线都是虚构的，客服电话在 555-01xx 这一段。
// 字体用黑体（Windows 上是 SimHei）和 Arial；图已经提交，单测不依赖字体。
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;
import java.util.Map;
import java.util.Random;

public class CardBackPage {

    static final int W = 1000, H = 630;

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        BufferedImage cn = chinese();
        write(cn, new File(dir, "card-back-cn.jpg"), 0.88f);
        write(english(), new File(dir, "card-back-en.jpg"), 0.88f);
        write(photo(cn), new File(dir, "card-back-photo.jpg"), 0.72f);
    }

    static BufferedImage chinese() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        g.setPaint(new GradientPaint(0, 0, new Color(28, 44, 84), W, H, new Color(14, 24, 52)));
        g.fillRect(0, 0, W, H);
        waves(g, new Color(36, 54, 98));
        stripe(g, 50);

        Color light = new Color(214, 220, 236);
        text(g, "持卡人签名 AUTHORIZED SIGNATURE", 60, 212, font(Font.PLAIN, 20), light, 0.02f);
        panel(g, 60, 226, 640, 104, new Color(200, 214, 232));
        text(g, "8810 357", 530, 292, new Font("Arial", Font.ITALIC, 1).deriveFont(34f), new Color(26, 26, 32), 0.04f);
        hologram(g, 730, 226, 200, 104);

        text(g, "本卡仅限持卡人本人使用，请妥善保管，不得转借他人。", 60, 392, font(Font.PLAIN, 22), light, 0.02f);
        text(g, "使用本卡须遵守《示例银行信用卡章程》及相关协议。", 60, 424, font(Font.PLAIN, 22), light, 0.02f);
        text(g, "客户服务热线 96000    www.example.com.cn", 60, 480, font(Font.PLAIN, 24), light, 0.02f);
        text(g, "示例银行 EXAMPLE BANK", 60, 575, font(Font.BOLD, 30), Color.WHITE, 0.04f);
        unionPay(g, 820, 512);
        g.dispose();
        return img;
    }

    static BufferedImage english() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        g.setPaint(new GradientPaint(0, 0, new Color(234, 236, 240), W, H, new Color(204, 208, 218)));
        g.fillRect(0, 0, W, H);
        waves(g, new Color(218, 222, 232));
        stripe(g, 48);

        Color ink = new Color(52, 54, 62);
        text(g, "AUTHORIZED SIGNATURE", 60, 208, new Font("Arial", Font.PLAIN, 1).deriveFont(17f), ink, 0.04f);
        panel(g, 60, 220, 600, 100, new Color(206, 214, 228));
        text(g, "NOT VALID UNLESS SIGNED", 60, 342, new Font("Arial", Font.PLAIN, 1).deriveFont(15f), ink, 0.04f);
        g.setColor(new Color(252, 252, 250));
        g.fillRect(676, 238, 120, 64);
        text(g, "482", 704, 282, new Font("Arial", Font.ITALIC, 1).deriveFont(34f), new Color(20, 20, 26), 0.04f);

        Font small = new Font("Arial", Font.PLAIN, 1).deriveFont(18f);
        text(g, "This card is issued by Example Bank, N.A. pursuant to a license from Visa U.S.A. Inc.", 60, 400, small, ink, 0);
        text(g, "Use of this card is subject to the Cardholder Agreement.", 60, 428, small, ink, 0);
        text(g, "Customer Service 1-800-555-0199", 60, 480, new Font("Arial", Font.PLAIN, 1).deriveFont(20f), ink, 0);
        text(g, "VISA", 800, 590, new Font("Arial", Font.BOLD | Font.ITALIC, 1).deriveFont(64f), new Color(26, 31, 113), 0.02f);
        g.dispose();
        return img;
    }

    /** 卡放在木纹桌上拍：逆时针歪 7°，四周是桌面，稍微糊一点。 */
    static BufferedImage photo(BufferedImage card) {
        int pw = 1200, ph = 900;
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        Random r = new Random(11);
        for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) {
            int n = r.nextInt(18);
            int v = 150 + (int) (22 * Math.sin(y / 7.0 + 3 * Math.sin(x / 90.0))) + n;
            img.setRGB(x, y, new Color(v, (int) (v * 0.78), (int) (v * 0.55)).getRGB());
        }
        AffineTransform t = new AffineTransform();
        t.translate(pw / 2.0, ph / 2.0);
        t.rotate(Math.toRadians(-7));
        t.scale(0.95, 0.95);
        t.translate(-W / 2.0, -H / 2.0);
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(t.createTransformedShape(new Rectangle(10, 10, W, H)));
        g.drawImage(card, t, null);
        g.dispose();
        float k = 1f / 9;
        return new ConvolveOp(new Kernel(3, 3, new float[]{k, k, k, k, k, k, k, k, k}), ConvolveOp.EDGE_NO_OP, null).filter(img, null);
    }

    /** 防伪底纹：几十道横着的细波纹。 */
    static void waves(Graphics2D g, Color c) {
        g.setStroke(new BasicStroke(1f));
        g.setColor(c);
        for (int k = 0; k < 34; k++) {
            int prevX = 0, prevY = 0;
            for (int x = 0; x <= W; x += 6) {
                int y = (int) (10 + k * 19 + 8 * Math.sin(x / 47.0 + k * 0.6));
                if (x > 0) g.drawLine(prevX, prevY, x, y);
                prevX = x; prevY = y;
            }
        }
    }

    /** 磁条：从 [top] 起一道黑带。 */
    static void stripe(Graphics2D g, int top) {
        g.setColor(new Color(18, 18, 20));
        g.fillRect(0, top, W, 116);
    }

    /** 签名条：米白的底，斜着一道道细线。 */
    static void panel(Graphics2D g, int x, int y, int w, int h, Color hatch) {
        g.setColor(new Color(248, 247, 240));
        g.fillRect(x, y, w, h);
        Shape clip = g.getClip();
        g.clipRect(x, y, w, h);
        g.setStroke(new BasicStroke(2f));
        g.setColor(hatch);
        for (int k = -h; k < w; k += 9) g.drawLine(x + k, y + h, x + k + h, y);
        g.setClip(clip);
    }

    /** 镭射标：银灰的渐变，几道弧。 */
    static void hologram(Graphics2D g, int x, int y, int w, int h) {
        g.setPaint(new GradientPaint(x, y, new Color(186, 190, 204), x + w, y + h, new Color(240, 240, 248)));
        g.fillRect(x, y, w, h);
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(214, 206, 226));
        for (int k = 0; k < 4; k++) g.drawArc(x + 20 + k * 12, y + 10 + k * 8, 120 - k * 24, 84 - k * 16, 20, 300);
    }

    /** 银联标：红、蓝、绿三块斜着并排，上面压着白字。 */
    static void unionPay(Graphics2D g, int x, int y) {
        Color[] colors = {new Color(206, 32, 46), new Color(0, 70, 140), new Color(0, 130, 110)};
        for (int k = 0; k < 3; k++) {
            int left = x + k * 44;
            g.setColor(colors[k]);
            g.fillPolygon(new int[]{left + 14, left + 58, left + 44, left}, new int[]{y, y, y + 86, y + 86}, 4);
        }
        text(g, "银联", x + 34, y + 44, font(Font.BOLD, 30), Color.WHITE, 0.05f);
        text(g, "UnionPay", x + 20, y + 74, new Font("Arial", Font.BOLD, 1).deriveFont(20f), Color.WHITE, 0);
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    static Font font(int style, float size) {
        return new Font("SimHei", style, 1).deriveFont(size);
    }

    /** 画一行字，tracking 是字距（按字号的比例）。 */
    static void text(Graphics2D g, String s, int x, int y, Font f, Color c, float tracking) {
        g.setFont(tracking == 0 ? f : f.deriveFont(Map.of(TextAttribute.TRACKING, tracking)));
        g.setColor(c);
        g.drawString(s, x, y);
    }

    static void write(BufferedImage img, File out, float quality) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        try (ImageOutputStream os = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(os);
            writer.write(null, new IIOImage(img, null, null), p);
        }
        writer.dispose();
    }
}
