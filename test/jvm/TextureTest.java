import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * 纸张纹理资源的验证。
 *
 * 这些贴图是 tools/make_textures.py 生成的，不是手画的，所以容易被改坏而没人发现。
 * 这里锁住四条：
 *   - 五张都在、都是 512×512 的纯 alpha 图（RGB 全白，代码里才着色）
 *   - **平铺无缝**：左右/上下接缝处的跳变，不能明显大于图内部相邻像素的跳变
 *   - 够淡：alpha 均值压得很低，它是背景，不能抢文字
 *   - 又够有：峰值要够，否则等于没开
 */
public class TextureTest {

    private static int pass = 0;
    private static int fail = 0;

    private static final String DIR = "app/src/main/res/drawable-nodpi/";
    private static final String[] NAMES = {
            "tex_wood", "tex_linen", "tex_leather", "tex_marble", "tex_antique"
    };
    private static final String[] LABELS = {
            "木纹", "亚麻", "皮革", "石纹", "旧纸"
    };

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name + (detail.isEmpty() ? "" : "  (" + detail + ")"));
        } else {
            fail++;
            System.out.println("  ✗ " + name + "  (" + detail + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 纸张纹理验证 ===\n");

        System.out.println("【文件与格式】");
        for (int i = 0; i < NAMES.length; i++) {
            File f = new File(DIR + NAMES[i] + ".png");
            check(LABELS[i] + " 存在", f.isFile(),
                    f.isFile() ? (f.length() / 1024) + " KB" : "缺文件 " + f.getPath());
        }

        for (int i = 0; i < NAMES.length; i++) {
            BufferedImage img = ImageIO.read(new File(DIR + NAMES[i] + ".png"));
            if (img == null) {
                check(LABELS[i] + " 能解码", false, "ImageIO 读不出来");
                continue;
            }
            check(LABELS[i] + " 尺寸 512×512", img.getWidth() == 512 && img.getHeight() == 512,
                    img.getWidth() + "×" + img.getHeight());
            check(LABELS[i] + " 带 alpha 通道", img.getColorModel().hasAlpha(), "");
        }

        System.out.println("\n【纯 alpha 贴图：RGB 必须是白的，颜色留给代码着色】");
        for (int i = 0; i < NAMES.length; i++) {
            BufferedImage img = ImageIO.read(new File(DIR + NAMES[i] + ".png"));
            boolean white = true;
            for (int y = 0; y < img.getHeight() && white; y += 7) {
                for (int x = 0; x < img.getWidth(); x += 7) {
                    int p = img.getRGB(x, y);
                    if (((p >> 16) & 0xFF) != 255 || ((p >> 8) & 0xFF) != 255 || (p & 0xFF) != 255) {
                        white = false;
                        break;
                    }
                }
            }
            check(LABELS[i] + " RGB 全白", white, "");
        }

        System.out.println("\n【强度：背景不能抢文字，但也不能等于没开】");
        for (int i = 0; i < NAMES.length; i++) {
            double[] st = stats(NAMES[i]);
            double mean = st[0];
            double peak = st[1];
            check(LABELS[i] + " 够淡（alpha 均值 < 0.20）", mean < 0.20,
                    String.format("均值=%.3f", mean));
            check(LABELS[i] + " 够有（峰值 > 0.30）", peak > 0.30,
                    String.format("峰值=%.3f", peak));
        }

        System.out.println("\n【平铺无缝：接缝处的跳变不能大于图内部】");
        for (int i = 0; i < NAMES.length; i++) {
            BufferedImage img = ImageIO.read(new File(DIR + NAMES[i] + ".png"));
            int w = img.getWidth();
            int h = img.getHeight();

            double innerH = 0, seamH = 0, innerV = 0, seamV = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 1; x < w; x++) {
                    innerH += Math.abs(alpha(img, x, y) - alpha(img, x - 1, y));
                }
                seamH += Math.abs(alpha(img, 0, y) - alpha(img, w - 1, y));
            }
            innerH /= (double) h * (w - 1);
            seamH /= h;

            for (int x = 0; x < w; x++) {
                for (int y = 1; y < h; y++) {
                    innerV += Math.abs(alpha(img, x, y) - alpha(img, x, y - 1));
                }
                seamV += Math.abs(alpha(img, x, 0) - alpha(img, x, h - 1));
            }
            innerV /= (double) w * (h - 1);
            seamV /= w;

            boolean okH = seamH <= innerH * 3 + 2;
            boolean okV = seamV <= innerV * 3 + 2;
            check(LABELS[i] + " 左右接缝连续", okH,
                    String.format("接缝=%.2f 内部=%.2f", seamH, innerH));
            check(LABELS[i] + " 上下接缝连续", okV,
                    String.format("接缝=%.2f 内部=%.2f", seamV, innerV));
        }

        System.out.println("\n==================================");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("==================================");
        if (fail > 0) System.exit(1);
    }

    private static int alpha(BufferedImage img, int x, int y) {
        return (img.getRGB(x, y) >>> 24) & 0xFF;
    }

    /** 返回 {alpha 均值, alpha 峰值}（归一化到 0–1） */
    private static double[] stats(String name) throws Exception {
        BufferedImage img = ImageIO.read(new File(DIR + name + ".png"));
        long sum = 0;
        int peak = 0;
        int n = 0;
        for (int y = 0; y < img.getHeight(); y += 2) {
            for (int x = 0; x < img.getWidth(); x += 2) {
                int a = alpha(img, x, y);
                sum += a;
                if (a > peak) peak = a;
                n++;
            }
        }
        return new double[]{sum / (double) n / 255.0, peak / 255.0};
    }
}
