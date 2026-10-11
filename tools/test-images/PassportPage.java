// 合成一张护照资料页，给 PaddleTextRecognizerTest 用真模型读：app/src/test/resources/ppocr/passport-page.jpg
//
//   java tools/test-images/PassportPage.java app/src/test/resources/ppocr/passport-page.jpg
//
// 照着用户反馈里那张美国护照样张的版式排，内容全是编的：签发国是 ICAO 9303 样例里虚构的 Utopia（UTO），
// 照片是灰色剪影。样张上让 0.11.1 什么都没认出来的几样都留着：
// - 值是字距拉开的全大写字（按像素补空格时被拆成「TRAVE L E R」）；
// - 字段名带法文、西文，在值的上面；一排三个字段名（Type、Code、Passport No.）；
// - 斜压在版面上的半透明水印「SPECIMEN」（检测框高，把它竖直范围里的行串成一行）；
// - 等宽的机读码，第二行只有一个「<」，要靠校验位认；幽灵像旁边一行小字的生日。
// 字体用 DejaVu（Linux 上都有）；图已经提交，单测不依赖字体。
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;

public class PassportPage {

    static final int W = 900, H = 600;

    public static void main(String[] args) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 底：淡紫的渐变，加几道浅蓝的波纹（防伪底纹的样子）
        g.setPaint(new GradientPaint(0, 0, new Color(236, 234, 246), W, H, new Color(226, 230, 244)));
        g.fillRect(0, 0, W, H);
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(196, 204, 232));
        for (int k = 0; k < 26; k++) {
            int prevX = 0, prevY = 0;
            for (int x = 0; x <= W; x += 6) {
                int y = (int) (40 + k * 18 + 7 * Math.sin(x / 37.0 + k));
                if (x > 0) g.drawLine(prevX, prevY, x, y);
                prevX = x; prevY = y;
            }
        }

        // 抬头
        Color ink = new Color(30, 30, 40);
        Color label = new Color(70, 70, 120);
        text(g, "PASSPORT", 62, 58, font("DejaVu Sans", Font.BOLD, 20), label, 0);
        text(g, "PASSEPORT/PASAPORTE", 34, 76, font("DejaVu Sans", Font.PLAIN, 11), label, 0);
        text(g, "REPUBLIC OF UTOPIA", 300, 64, font("DejaVu Serif", Font.BOLD, 30), new Color(60, 60, 130), 0.05f);
        text(g, "UTO", 60, 150, font("DejaVu Sans", Font.BOLD, 40), new Color(220, 120, 60), 0.05f);

        // 照片：灰色剪影
        g.setColor(new Color(170, 170, 178));
        g.fillRoundRect(28, 175, 200, 245, 10, 10);
        g.setColor(new Color(120, 120, 130));
        g.fillOval(88, 205, 80, 95);
        g.fillRoundRect(58, 305, 140, 115, 60, 60);

        Font lab = font("DejaVu Sans", Font.PLAIN, 11);
        Font val = font("DejaVu Sans", Font.PLAIN, 19);
        float spread = 0.12f;

        // 一排三个字段名，值各在各的下面
        text(g, "Type/Type/Tipo", 275, 100, lab, label, 0);
        text(g, "Code/Code/Código", 390, 100, lab, label, 0);
        text(g, "Passport No./No. du Passeport/No. de Pasaporte", 530, 100, lab, label, 0);
        text(g, "P", 318, 124, val, ink, spread);
        text(g, "UTO", 430, 124, val, ink, spread);
        text(g, "X52819374", 590, 128, font("DejaVu Sans", Font.PLAIN, 24), ink, spread);

        String[][] fields = {
            {"Surname/Nom/Apellidos", "MARLOWE"},
            {"Given names/Prénoms/Nombres", "ANNA BEATRICE"},
            {"Nationality/Nationalité/Nacionalidad", "UTOPIAN"},
            {"Date of birth/Date de naissance/Fecha de nacimiento", "12 AUG 1974"},
            {"Sex/Sexe/Sexo", "F"},
            {"Place of birth/Lieu de naissance/Lugar de nacimiento", "ZENITH CITY, UTOPIA"},
            {"Date of issue/Date de délivrance/Fecha de expedición", "03 MAR 2021"},
            {"Date of expiration/Date d'expiration/Fecha de caducidad", "02 MAR 2031"},
            {"Authority/Autorité/Autoridad", "MINISTRY OF FOREIGN AFFAIRS"},
        };
        int y = 152;
        for (String[] f : fields) {
            text(g, f[0], 255, y, lab, label, 0);
            text(g, f[1], 257, y + 21, val, ink, spread);
            y += 36;
        }

        // 幽灵像和它旁边的一行小字生日
        g.setColor(new Color(205, 205, 215));
        g.fillRoundRect(735, 380, 60, 72, 8, 8);
        text(g, "12 08 74", 738, 470, font("DejaVu Sans", Font.PLAIN, 12), new Color(120, 120, 135), 0);

        // 水印：斜压在中间几行上
        Graphics2D w = (Graphics2D) g.create();
        w.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
        w.setColor(new Color(170, 60, 70));
        w.rotate(Math.toRadians(-22), 520, 300);
        w.setFont(font("DejaVu Sans", Font.BOLD, 64));
        w.drawString("SPECIMEN", 360, 320);
        w.dispose();

        // 机读码
        String doc = "X52819374", birth = "740812", expiry = "310302", personal = "581029473<1185";
        String line1 = pad("P<UTOMARLOWE<<ANNA<BEATRICE", 44);
        String l2 = doc + check(doc) + "UTO" + birth + check(birth) + "F" + expiry + check(expiry) + personal + check(personal);
        String composite = l2.substring(0, 10) + l2.substring(13, 20) + l2.substring(21, 43);
        String line2 = l2 + check(composite);
        Font mono = font("DejaVu Sans Mono", Font.PLAIN, 24);
        text(g, line1, 24, 530, mono, ink, 0.18f);
        text(g, line2, 24, 576, mono, ink, 0.18f);
        g.dispose();

        System.out.println(line1 + "\n" + line2);
        File out = new File(args[0]);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(0.85f);
        try (ImageOutputStream os = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(os);
            writer.write(null, new IIOImage(img, null, null), p);
        }
        writer.dispose();
    }

    static Font font(String family, int style, float size) {
        return new Font(family, style, 1).deriveFont(size);
    }

    /** 画一行字，tracking 是字距（按字号的比例）。 */
    static void text(Graphics2D g, String s, int x, int y, Font f, Color c, float tracking) {
        g.setFont(tracking == 0 ? f : f.deriveFont(Map.of(TextAttribute.TRACKING, tracking)));
        g.setColor(c);
        g.drawString(s, x, y);
    }

    static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append('<');
        return sb.toString();
    }

    /** ICAO 9303 的校验位：权重 7、3、1 循环，字母 A=10……Z=35，< 算 0。 */
    static char check(String field) {
        int[] w = {7, 3, 1};
        int sum = 0;
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            int v = Character.isDigit(c) ? c - '0' : c == '<' ? 0 : c - 'A' + 10;
            sum += v * w[i % 3];
        }
        return (char) ('0' + sum % 10);
    }
}
