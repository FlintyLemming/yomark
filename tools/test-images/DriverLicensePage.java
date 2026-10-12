// 合成一张机动车驾驶证正页，给 PP-OCR 的真模型单测用：
//
//   java tools/test-images/DriverLicensePage.java app/src/test/resources/ppocr
//
// 写出 driver-license.jpg。照着驾驶证正页的排版：
// - 字段名中文在前，英文小字紧跟在后（「姓名 Name」「出生日期 Date of Birth」），值在右边一栏，和字段名隔得远；
// - 姓名一排三个字段（姓名、性别、国籍）；
// - 住址折成两行，第二行和值对齐、不带字段名；
// - 证号是 18 位身份证号，按 GB 11643 算出校验码；
// - 右边一张灰色剪影照片，左下角压着半透明的红色印章圈。
//
// 内容全是编的。字体用黑体（Windows 上是 SimHei）；图已经提交，单测不依赖字体。
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;

public class DriverLicensePage {

    static final int W = 1100, H = 700;

    static final Color LABEL = new Color(40, 40, 48);
    static final Color INK = new Color(20, 20, 24);

    public static final String NAME = "周以宁";
    public static final String ADDRESS_1 = "江苏省南京市玄武区北京东路";
    public static final String ADDRESS_2 = "41号3栋2单元602室";
    public static final String BIRTH = "1986-02-14";
    public static final String ID = withCheck("32010519860214372");

    /** 值那一栏的左沿。 */
    static final int VX = 380;

    public static void main(String[] args) throws Exception {
        write(licence(), new File(args[0], "driver-license.jpg"), 0.88f);
        System.out.println(ID);
    }

    static BufferedImage licence() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        g.setPaint(new GradientPaint(0, 0, new Color(246, 236, 236), W, H, new Color(238, 242, 232)));
        g.fillRect(0, 0, W, H);
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(226, 206, 212));
        for (int k = 0; k < 36; k++) {
            int prevX = 0, prevY = 0;
            for (int x = 0; x <= W; x += 6) {
                int y = (int) (12 + k * 20 + 7 * Math.sin(x / 33.0 + k * 0.9));
                if (x > 0) g.drawLine(prevX, prevY, x, y);
                prevX = x; prevY = y;
            }
        }

        center(g, "中华人民共和国机动车驾驶证", 74, font(Font.BOLD, 40), INK, 0.08f);
        center(g, "Driving License of the People's Republic of China", 110, new Font("Arial", Font.PLAIN, 1).deriveFont(20f), INK, 0);

        Font val = font(Font.PLAIN, 30);
        label(g, "证号", null, 40, 175);
        text(g, ID, VX, 177, new Font("Consolas", Font.BOLD, 1).deriveFont(32f), new Color(70, 40, 90), 0.08f);

        label(g, "姓名", "Name", 40, 235);
        text(g, NAME, VX, 237, val, INK, 0.05f);
        label(g, "性别", "Sex", 560, 235);
        text(g, "女", 680, 237, val, INK, 0);
        label(g, "国籍", "Nationality", 760, 235);
        text(g, "中国", 960, 237, val, INK, 0);

        label(g, "住址", "Address", 40, 295);
        text(g, ADDRESS_1, VX, 297, val, INK, 0.02f);
        text(g, ADDRESS_2, VX, 345, val, INK, 0.02f);

        label(g, "出生日期", "Date of Birth", 40, 405);
        text(g, BIRTH, VX, 407, val, INK, 0.02f);
        label(g, "初次领证日期", "Date of First Issue", 40, 465);
        text(g, "2008-05-20", VX, 467, val, INK, 0.02f);
        label(g, "准驾车型", "Class", 40, 525);
        text(g, "C1", VX, 527, val, INK, 0.02f);
        label(g, "有效期限", "Valid Period", 40, 585);
        text(g, "2018-05-20 至 2028-05-20", VX, 587, val, INK, 0.02f);

        // 照片：灰色剪影
        g.setColor(new Color(214, 218, 224));
        g.fillRect(860, 270, 200, 260);
        g.setColor(new Color(150, 152, 160));
        g.fillOval(915, 300, 90, 115);
        g.fillRoundRect(885, 420, 150, 110, 70, 70);

        // 印章：半透明的红圈和五角星，压在左下角的字上
        g.setColor(new Color(210, 40, 40, 110));
        g.setStroke(new BasicStroke(5f));
        g.drawOval(120, 500, 170, 170);
        g.fill(star(205, 585, 30));
        g.dispose();
        return img;
    }

    /** 字段名：中文，后面紧跟一段小字英文。 */
    static void label(Graphics2D g, String zh, String en, int x, int y) {
        Font f = font(Font.PLAIN, 26);
        text(g, zh, x, y, f, LABEL, 0);
        if (en == null) return;
        int w = g.getFontMetrics(f).stringWidth(zh);
        text(g, en, x + w + 8, y, new Font("Arial", Font.PLAIN, 1).deriveFont(15f), LABEL, 0);
    }

    static Shape star(double cx, double cy, double r) {
        Polygon p = new Polygon();
        for (int i = 0; i < 10; i++) {
            double a = Math.PI / 2 + i * Math.PI / 5;
            double rr = i % 2 == 0 ? r : r * 0.4;
            p.addPoint((int) (cx + rr * Math.cos(a)), (int) (cy - rr * Math.sin(a)));
        }
        return p;
    }

    static void center(Graphics2D g, String s, int y, Font f, Color c, float tracking) {
        Font ff = tracking == 0 ? f : f.deriveFont(Map.of(TextAttribute.TRACKING, tracking));
        int w = g.getFontMetrics(ff).stringWidth(s);
        text(g, s, (W - w) / 2, y, f, c, tracking);
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
