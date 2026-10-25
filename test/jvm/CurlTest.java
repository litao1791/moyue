import com.moyue.reader.ui.CurlGeometry;

/**
 * 仿真翻页（圆柱卷曲）几何的桌面端验证。
 *
 * 这里锁的是几条不能错的硬约束，肉眼很难发现：
 *   - 进度 0 时必须是一张平摊的整页（不能一开始就卷）
 *   - 进度 1 时纸必须整张卷出屏幕（否则收尾会闪一下）
 *   - 卷曲角永远不超过 π，屏幕投影永远不超出圆柱半径
 *   - 前翻 p 和后翻 (1-p) 必须是同一个形态（互为倒放）
 *   - 卷曲方向随拖动角度变化，这才是"从中间翻"和"从下边翻"不一样的根本
 */
public class CurlTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name + (detail.isEmpty() ? "" : "  (" + detail + ")"));
        } else {
            fail++;
            System.out.println("  ✗ " + name + "  (" + detail + ")");
        }
    }

    private static void near(String name, float actual, float expect, float tol) {
        check(name, Math.abs(actual - expect) <= tol, "实际=" + actual + " 期望=" + expect);
    }

    private static final float W = 1080f, H = 1920f, R = 114f;

    public static void main(String[] args) {
        System.out.println("=== 卷曲几何验证 (" + (int) W + "x" + (int) H + ", R=" + (int) R + ") ===\n");
        float[] out = new float[2];

        System.out.println("【起点：必须是一张平摊的整页】");
        CurlGeometry a = CurlGeometry.of(W, H, 0f, true, 0f, R);
        check("dMax ≤ 0，没有任何地方被卷起", a.dMax <= 0f, "dMax=" + a.dMax);
        check("看不到纸背", !a.showsBack(), "");
        a.map(10, 10, out);
        check("左上角原地不动", out[0] == 10 && out[1] == 10, out[0] + "," + out[1]);
        a.map(W - 1, H - 1, out);
        check("右下角原地不动", Math.abs(out[0] - (W - 1)) < 0.01f && Math.abs(out[1] - (H - 1)) < 0.01f,
                out[0] + "," + out[1]);

        System.out.println("\n【终点：必须整张卷出屏幕】");
        CurlGeometry b = CurlGeometry.of(W, H, 1f, true, 0f, R);
        check("整页都被卷起", b.dMax > W, "dMax=" + b.dMax);
        check("卷曲角封顶在 π", Math.abs(b.phiMax - CurlGeometry.PI) < 1e-4, "phiMax=" + b.phiMax);
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        for (int i = 0; i <= 40; i++) {
            for (int j = 0; j <= 40; j++) {
                b.map(W * i / 40f, H * j / 40f, out);
                minX = Math.min(minX, out[0]);
                maxX = Math.max(maxX, out[0]);
            }
        }
        check("纸已经全部落在屏幕左边界之外", maxX <= 0.5f, "最大 x=" + maxX);

        System.out.println("\n【中途：纸被压向折痕】");
        CurlGeometry m = CurlGeometry.of(W, H, 0.5f, true, 0f, R);
        check("看得到纸背", m.showsBack(), "dMax=" + m.dMax);
        near("卷筒外扩不超过半径", m.uMax(), R, 0.01f);
        check("轮廓线就是卷顶", Math.abs(m.dSilhouette() - R * CurlGeometry.HALF_PI) < 0.01f,
                "dSil=" + m.dSilhouette());

        System.out.println("\n【投影函数】");
        near("d=0 时投影为 0", m.screenOffset(0f), 0f, 1e-4f);
        near("φ=π/2 时投影 = R", m.screenOffset(R * CurlGeometry.HALF_PI), R, 0.01f);
        near("φ=π 时投影回到 0", m.screenOffset(R * CurlGeometry.PI), 0f, 0.01f);
        check("翻过去之后投影为负（铺回纸面）",
                m.screenOffset(R * CurlGeometry.PI + 50f) < 0f,
                "" + m.screenOffset(R * CurlGeometry.PI + 50f));

        boolean mono = true;
        float peak = R * CurlGeometry.HALF_PI;
        float prev = m.screenOffset(peak);
        for (int i = 1; i <= 300; i++) {
            float d = peak + i * 3f;
            float o = m.screenOffset(d);
            if (o > prev + 0.01f) mono = false;
            prev = o;
        }
        check("过了卷顶之后投影单调回落", mono, "300 个采样点");

        System.out.println("\n【前后翻互为倒放】");
        boolean mirror = true;
        for (int i = 0; i <= 10; i++) {
            float p = i / 10f;
            CurlGeometry f = CurlGeometry.of(W, H, p, true, 0f, R);
            CurlGeometry r = CurlGeometry.of(W, H, 1f - p, false, 0f, R);
            if (Math.abs(f.qx - r.qx) > 0.01f) mirror = false;
            if (Math.abs(f.dMax - r.dMax) > 0.01f) mirror = false;
        }
        check("同一进度下前翻与后翻形态一致", mirror, "11 个采样点");

        System.out.println("\n【卷曲轴随拖动方向变化】");
        CurlGeometry flat = CurlGeometry.of(W, H, 0.5f, true, 0f, R);
        CurlGeometry tilt = CurlGeometry.of(W, H, 0.5f, true, 0.35f, R);
        check("倾角 0 时卷曲方向水平", Math.abs(flat.ny) < 1e-4, "ny=" + flat.ny);
        check("倾角 0.35 时卷曲方向向下偏", tilt.ny > 0.3f, "ny=" + tilt.ny);

        // p=0.5 时折痕正好过中心，两种倾角的位置相同，但形变方向不同
        float[] fa = new float[2], ta = new float[2];
        flat.map(W, H, fa);
        tilt.map(W, H, ta);
        check("右下角在两种倾角下被卷到不同位置",
                Math.abs(fa[0] - ta[0]) + Math.abs(fa[1] - ta[1]) > 20f,
                "(" + (int) fa[0] + "," + (int) fa[1] + ") vs ("
                        + (int) ta[0] + "," + (int) ta[1] + ")");

        CurlGeometry mid = CurlGeometry.of(W, H, 0.3f, true, 0f, R);
        CurlGeometry midTilt = CurlGeometry.of(W, H, 0.3f, true, 0.35f, R);
        check("非中点时折痕也跟着倾斜", Math.abs(mid.qy - midTilt.qy) > 1f,
                "qy " + (int) mid.qy + " vs " + (int) midTilt.qy);

        System.out.println("\n【倾斜角度：纸背不能画到纸以外】");
        // 这是"某些角度翻页很怪"的根因：按屏幕矩形裁剪会把纸以外的部分也算进来。
        // 必须先在纸面裁剪，端点就永远落在纸张矩形内。
        float[] paper = new float[4], screen = new float[4];
        boolean inside = true;
        int sampled = 0;
        for (float tl = -0.62f; tl <= 0.621f; tl += 0.08f) {
            for (int i = 1; i <= 9; i++) {
                CurlGeometry g = CurlGeometry.of(W, H, i / 10f, true, tl, R);
                if (!g.showsBack()) continue;
                if (!g.paperSegment(g.dSilhouette(), paper, screen)) continue;
                sampled++;
                for (int k = 0; k < 4; k += 2) {
                    if (paper[k] < -0.01f || paper[k] > W + 0.01f) inside = false;
                    if (paper[k + 1] < -0.01f || paper[k + 1] > H + 0.01f) inside = false;
                }
            }
        }
        check("纸面端点始终落在纸张矩形内", inside, sampled + " 组倾角×进度");

        float[] p0 = new float[4];
        CurlGeometry g0 = CurlGeometry.of(W, H, 0.5f, true, 0f, R);
        g0.paperSegment(g0.dSilhouette(), p0, null);
        near("折痕竖直时轮廓线贯穿整页高度",
                (float) Math.hypot(p0[2] - p0[0], p0[3] - p0[1]), H, 1f);

        CurlGeometry gt = CurlGeometry.of(W, H, 0.5f, true, 0.5f, R);
        gt.paperSegment(gt.dSilhouette(), p0, null);
        float lenT = (float) Math.hypot(p0[2] - p0[0], p0[3] - p0[1]);
        check("折痕倾斜时轮廓线被纸张边界截短", lenT < H - 1f && lenT > 0f,
                "长度=" + (int) lenT + " < 高度=" + (int) H);

        boolean noStretch = true;
        for (float tl = -0.62f; tl <= 0.621f; tl += 0.1f) {
            for (int i = 2; i <= 9; i++) {
                CurlGeometry g = CurlGeometry.of(W, H, i / 10f, true, tl, R);
                if (!g.showsBack()) continue;
                if (!g.paperSegment(g.dSilhouette(), paper, screen)) continue;
                float pl = (float) Math.hypot(paper[2] - paper[0], paper[3] - paper[1]);
                float sl = (float) Math.hypot(screen[2] - screen[0], screen[3] - screen[1]);
                if (sl > pl + 1f) noStretch = false;   // 投影只会压短，不会拉长
            }
        }
        check("投影不会把轮廓线拉长", noStretch, "");

        CurlGeometry far = CurlGeometry.of(W, H, 0f, true, 0f, R);
        check("纸张之外的直线取不到交集",
                !far.paperSegment(far.dMax + 200f, null, null), "dMax=" + far.dMax);

        System.out.println("\n【纸背：正面那一层必须收成一条线】");
        // 这里踩过一个大坑：shift 写成 R - max(d,0)，d≤0 时退化成整体平移，
        // 把大片顶点拉成扇形铺满屏幕（实机上就是糊了一屏灰）。
        boolean collapsed = true, within = true;
        float worstErr = 0f;
        int pts = 0;
        for (float tl = -0.62f; tl <= 0.621f; tl += 0.15f) {
            for (int i = 1; i <= 9; i++) {
                CurlGeometry g = CurlGeometry.of(W, H, i / 10f, true, tl, R);
                if (!g.showsBack()) continue;
                for (int ix = 0; ix <= 20; ix++) {
                    for (int iy = 0; iy <= 20; iy++) {
                        float x = W * ix / 20f, y = H * iy / 20f;
                        float phi = g.mapBack(x, y, out);
                        float off = (out[0] - g.qx) * g.nx + (out[1] - g.qy) * g.ny;
                        pts++;
                        if (off > g.radius + 0.5f) within = false;
                        if (phi <= CurlGeometry.HALF_PI) {
                            float err = Math.abs(off - g.radius);
                            if (err > worstErr) worstErr = err;
                            if (err > 0.5f) collapsed = false;
                        }
                    }
                }
            }
        }
        check("正面可见的点全部收到轮廓线上", collapsed,
                pts + " 点，最大偏差=" + String.format("%.2f", worstErr));
        check("纸背不会越过轮廓线", within, "R=" + (int) R);

        System.out.println("\n【全程扫描】");
        boolean bounded = true, finite = true;
        for (int i = 0; i <= 100; i++) {
            CurlGeometry g = CurlGeometry.of(W, H, i / 100f, true, 0.2f, R);
            for (int j = 0; j <= 20; j++) {
                for (int k = 0; k <= 20; k++) {
                    float phi = g.map(W * j / 20f, H * k / 20f, out);
                    if (Float.isNaN(out[0]) || Float.isNaN(out[1])) finite = false;
                    if (phi < -1e-4f || phi > CurlGeometry.PI + 1e-4f) bounded = false;
                }
            }
        }
        check("卷曲角始终落在 [0, π]", bounded, "101 帧 × 441 点");
        check("映射结果没有 NaN", finite, "101 帧 × 441 点");

        System.out.println("\n【边界容错】");
        near("进度 -1 夹到 0", CurlGeometry.of(W, H, -1f, true, 0f, R).dMax, 
                CurlGeometry.of(W, H, 0f, true, 0f, R).dMax, 0.01f);
        check("半径给 0 也不炸",
                !Float.isNaN(CurlGeometry.of(W, H, 0.5f, true, 0f, 0f).uMax()), "");

        System.out.println("\n==================================");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("==================================");
        if (fail > 0) System.exit(1);
    }
}
