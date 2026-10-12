// 合成居民身份证的两面和一张「拍歪了的照片」，给 PP-OCR 的真模型单测用：
//
//   java tools/test-images/IdCardPage.java app/src/test/resources/ppocr
//
// 写出三张图：
// - id-card-front.jpg：人像面，正着放。字段名淡蓝、值黑色，「姓 名」两个字之间拉开一格，
//   出生一行的年月日是字段色、数字是值色，住址折成两行（第二行和值对齐，不和字段名对齐），
//   公民身份号码用等宽字、字距拉开；
// - id-card-back.jpg：国徽面，签发机关、有效期限；
// - id-card-photo.jpg：人像面放在皮纹桌面上拍的样子，顺时针转了 90° 再歪 4°，压缩得狠一点。
//   用户反馈的两张都是这样竖着拍的：0.15.0 把一列列字串成一行，姓名、出生、号码一处都没遮上。
//
// 内容全是编的：号码按 GB 11643 算出校验码，地址是随手写的门牌，照片是灰色剪影（单测不跑人脸）。
// 字体用黑体（Windows 上是 SimHei）；图已经提交，单测不依赖字体。
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

public class IdCardPage {

    static final int W = 1000, H = 630;

    static final Color LABEL = new Color(52, 118, 186);
    static final Color INK = new Color(24, 24, 28);

    public static final String NAME = "林知夏";
    public static final String ADDRESS_1 = "浙江省杭州市西湖区文三路";
    public static final String ADDRESS_2 = "88号6幢2单元501室";
    public static final String ID = withCheck("33010619930718042");

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        BufferedImage front = front();
        write(front, new File(dir, "id-card-front.jpg"), 0.88f);
        write(back(), new File(dir, "id-card-back.jpg"), 0.88f);
        write(photo(front), new File(dir, "id-card-photo.jpg"), 0.72f);
        System.out.println(ID);
    }

    static BufferedImage front() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = card(img);

        Font lab = font(Font.PLAIN, 27);
        Font val = font(Font.PLAIN, 31);

        text(g, "姓", 70, 112, lab, LABEL, 0);
        text(g, "名", 120, 112, lab, LABEL, 0);
        text(g, NAME, 180, 114, font(Font.PLAIN, 36), INK, 0.05f);

        text(g, "性", 70, 190, lab, LABEL, 0);
        text(g, "别", 120, 190, lab, LABEL, 0);
        text(g, "女", 180, 190, val, INK, 0);
        text(g, "民", 300, 190, lab, LABEL, 0);
        text(g, "族", 350, 190, lab, LABEL, 0);
        text(g, "汉", 410, 190, val, INK, 0);

        text(g, "出", 70, 268, lab, LABEL, 0);
        text(g, "生", 120, 268, lab, LABEL, 0);
        text(g, "1993", 180, 268, val, INK, 0.02f);
        text(g, "年", 262, 268, lab, LABEL, 0);
        text(g, "7", 318, 268, val, INK, 0);
        text(g, "月", 350, 268, lab, LABEL, 0);
        text(g, "18", 404, 268, val, INK, 0);
        text(g, "日", 450, 268, lab, LABEL, 0);

        text(g, "住", 70, 346, lab, LABEL, 0);
        text(g, "址", 120, 346, lab, LABEL, 0);
        text(g, ADDRESS_1, 180, 346, val, INK, 0.02f);
        text(g, ADDRESS_2, 180, 394, val, INK, 0.02f);

        // 照片：灰色剪影
        g.setColor(new Color(214, 218, 224));
        g.fillRect(640, 70, 290, 360);
        g.setColor(new Color(150, 152, 160));
        g.fillOval(725, 120, 120, 150);
        g.fillRoundRect(680, 280, 210, 150, 90, 90);

        text(g, "公民身份号码", 70, 540, lab, LABEL, 0.04f);
        text(g, ID, 330, 543, new Font("Consolas", Font.BOLD, 1).deriveFont(38f), INK, 0.16f);
        g.dispose();
        return img;
    }

    static BufferedImage back() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = card(img);
        // 国徽：红圈加五颗星的样子
        g.setColor(new Color(200, 40, 40));
        g.fillOval(70, 60, 150, 150);
        g.setColor(new Color(240, 200, 60));
        g.fillOval(120, 100, 50, 50);

        text(g, "中华人民共和国", 300, 140, font(Font.BOLD, 52), INK, 0.12f);
        text(g, "居民身份证", 340, 260, font(Font.BOLD, 70), INK, 0.2f);

        Font lab = font(Font.PLAIN, 27);
        Font val = font(Font.PLAIN, 31);
        text(g, "签发机关", 240, 460, lab, LABEL, 0.1f);
        text(g, "杭州市公安局西湖分局", 420, 462, val, INK, 0.02f);
        text(g, "有效期限", 240, 540, lab, LABEL, 0.1f);
        text(g, "2015.08.20-2035.08.20", 420, 542, val, INK, 0.02f);
        g.dispose();
        return img;
    }

    /** 人像面放在桌上拍：顺时针 90° 再歪 4°，四周是深色皮纹，稍微糊一点。 */
    static BufferedImage photo(BufferedImage card) {
        int pw = 900, ph = 1200;
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        Random r = new Random(7);
        for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) {
            int n = r.nextInt(26);
            int v = 120 + (int) (12 * Math.sin(x / 9.0) * Math.cos(y / 11.0)) + n;
            img.setRGB(x, y, new Color(v, (int) (v * 0.9), (int) (v * 0.7)).getRGB());
        }
        AffineTransform t = new AffineTransform();
        t.translate(pw / 2.0, ph / 2.0);
        t.rotate(Math.toRadians(90 + 4));
        t.scale(0.98, 0.98);
        t.translate(-W / 2.0, -H / 2.0);
        g.setColor(new Color(0, 0, 0, 60));
        g.fill(t.createTransformedShape(new Rectangle(8, 8, W, H)));
        g.drawImage(card, t, null);
        g.dispose();
        float k = 1f / 9;
        return new ConvolveOp(new Kernel(3, 3, new float[]{k, k, k, k, k, k, k, k, k}), ConvolveOp.EDGE_NO_OP, null).filter(img, null);
    }

    /** 卡面的底：浅青到浅粉的渐变，加几道防伪底纹。 */
    static Graphics2D card(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        hints(g);
        g.setPaint(new GradientPaint(0, 0, new Color(222, 238, 240), W, H, new Color(242, 228, 234)));
        g.fillRect(0, 0, W, H);
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(196, 214, 222));
        for (int k = 0; k < 34; k++) {
            int prevX = 0, prevY = 0;
            for (int x = 0; x <= W; x += 6) {
                int y = (int) (10 + k * 19 + 8 * Math.sin(x / 41.0 + k * 0.7));
                if (x > 0) g.drawLine(prevX, prevY, x, y);
                prevX = x; prevY = y;
            }
        }
        return g;
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

    /** GB 11643 的校验码：前 17 位按权重求和，模 11 查表。 */
    static String withCheck(String first17) {
        int[] w = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        int sum = 0;
        for (int i = 0; i < 17; i++) sum += (first17.charAt(i) - '0') * w[i];
        return first17 + "10X98765432".charAt(sum % 11);
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
