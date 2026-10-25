package com.moyue.reader.ui;

/**
 * 仿真翻页的圆柱卷曲几何。纯数学，不碰 Canvas，可以在桌面 JVM 上直接验证。
 *
 * 模型跟 iBooks 一致：iBooks 用的是 Core Image 的 kCAFilterPageCurl，
 * 输入只有 inputAngle（卷曲轴角度）和 inputTime（卷曲量）——
 * 页面绕一根"卷曲轴"弯成圆柱，就是这么回事。
 *
 *   n            卷曲方向（从平摊区指向卷起区）。轴 ⟂ n，所以拖动方向一变，
 *                卷曲的朝向就跟着变，这是"从中间翻"和"从下边翻"不一样的原因。
 *   d            纸面上某点到折痕的垂直距离
 *   φ = d / R    卷过的角度（R 是圆柱半径）
 *   u = R·sin φ  正投影到屏幕上的位置——纸看起来被"压"向折痕
 *
 * d 超过 πR 之后纸又平铺回来（叠在自己上面），这一段的投影是 -(d - πR)。
 * 卷过的纸背、自由边、投影全都由这个函数推出来。
 */
public final class CurlGeometry {

    public static final float HALF_PI = (float) (Math.PI / 2);
    public static final float PI = (float) Math.PI;

    public final float width;
    public final float height;
    /** 圆柱半径（像素） */
    public final float radius;

    /** 卷曲方向单位向量 */
    public final float nx, ny;
    /** 折痕上一点 */
    public final float qx, qy;

    /** 纸面最远处到折痕的距离 */
    public final float dMax;
    /** 最远处的卷曲角，上限 π */
    public final float phiMax;

    private CurlGeometry(float w, float h, float r, float nx, float ny,
                         float qx, float qy, float dMax) {
        this.width = w;
        this.height = h;
        this.radius = r;
        this.nx = nx;
        this.ny = ny;
        this.qx = qx;
        this.qy = qy;
        this.dMax = dMax;
        this.phiMax = dMax <= 0 ? 0f : Math.min(PI, dMax / r);
    }

    /**
     * @param progress 0 = 一页没卷，1 = 整页卷完
     * @param forward  true 前翻（当前页卷走），false 后翻（上一页卷回来）
     * @param tilt     卷曲方向相对水平线的倾角（弧度），由拖动方向决定
     * @param radius   圆柱半径
     */
    public static CurlGeometry of(float w, float h, float progress,
                                  boolean forward, float tilt, float radius) {
        float p = clamp01(progress);
        float r = Math.max(1f, radius);

        float nx = (float) Math.cos(tilt);
        float ny = (float) Math.sin(tilt);

        float hw = w * 0.5f;
        float hh = h * 0.5f;
        // 矩形在 n 方向上的半跨度
        float support = Math.abs(nx) * hw + Math.abs(ny) * hh;

        // t: 0 = 折痕在右边界外（没卷），1 = 折痕在左边界外（卷完）
        // 后翻就是前翻倒着播
        float t = forward ? p : 1f - p;

        // 折痕从中心沿 n 偏移多少；多算一个 R，让卷筒最后能整根移出屏幕
        float k = (support + r) * (1f - 2f * t);

        float qx = hw + nx * k;
        float qy = hh + ny * k;
        float dMax = support - k;

        return new CurlGeometry(w, h, r, nx, ny, qx, qy, dMax);
    }

    /** 点到折痕的有符号距离 */
    public float distance(float x, float y) {
        return (x - qx) * nx + (y - qy) * ny;
    }

    public float phiAt(float d) {
        return d <= 0f ? 0f : Math.min(phiMax, d / radius);
    }

    /**
     * 纸面点到屏幕的映射。
     * @return 该点的卷曲角 φ
     */
    public float map(float x, float y, float[] out) {
        float d = distance(x, y);
        if (d <= 0f) {
            out[0] = x;
            out[1] = y;
            return 0f;
        }
        float phi = Math.min(phiMax, d / radius);
        // 过了最高点之后看到的是纸背，正面这一层收到轮廓线上（面积归零）
        float u = phi <= HALF_PI ? radius * (float) Math.sin(phi) : radius;
        float shift = u - d;
        out[0] = x + nx * shift;
        out[1] = y + ny * shift;
        return phi;
    }

    /**
     * 距离折痕 d 处的那条线，投影到屏幕上是离折痕多远。
     * d ≤ 0 时就是 d 本身；卷曲段是 R·sin(d/R)；翻过去平铺段是 -(d - πR)。
     */
    public float screenOffset(float d) {
        if (d <= 0f) return d;
        if (d <= radius * PI) return radius * (float) Math.sin(d / radius);
        return -(d - radius * PI);
    }

    /** 卷筒向外鼓出的最大距离（轮廓线） */
    public float uMax() {
        if (dMax <= 0f) return 0f;
        return dMax >= radius * HALF_PI ? radius : radius * (float) Math.sin(dMax / radius);
    }

    /** 轮廓线对应的 d：卷过最高点就是最高点那条线，否则就是纸的自由边 */
    public float dSilhouette() {
        return Math.min(dMax, radius * HALF_PI);
    }

    /** 纸背可见的起始 d（卷过最高点才看得到纸背） */
    public float dBackStart() {
        return radius * HALF_PI;
    }

    public boolean showsBack() {
        return dMax > radius * HALF_PI;
    }

    /**
     * 纸背那一层。
     * φ ≤ π/2 的点（正面还看得见的地方）**全部收到轮廓线 u=R 上**，面积归零；
     * φ > π/2 的点落在圆柱/平铺的真实位置。
     *
     * 注意 shift 必须是 R - d，不能写成 R - max(d,0)：
     * d ≤ 0 是平摊区，那样写会变成整体平移，把大片顶点拉成扇形铺满屏幕。
     */
    public float mapBack(float x, float y, float[] out) {
        float d = distance(x, y);
        float phi = phiAt(d);
        float shift = phi <= HALF_PI ? radius - d : screenOffset(d) - d;
        out[0] = x + nx * shift;
        out[1] = y + ny * shift;
        return phi;
    }

    /**
     * 纸面上距离折痕 d 的那条直线，**先裁到纸张矩形内**，再映射到屏幕。
     *
     * 必须先裁纸面再映射。直接裁屏幕矩形是错的：折痕一倾斜，dMax 就只在某一个角取到，
     * 按屏幕矩形裁出来的"带子"会延伸到纸并不存在的地方——这就是倾斜翻页时看到怪东西的原因。
     *
     * @param paperXY  可选，回填纸面坐标端点（4 个 float），传 null 不要
     * @param screenXY 回填屏幕坐标端点（4 个 float）
     * @return 这条线与纸张有交集才返回 true
     */
    public boolean paperSegment(float d, float[] paperXY, float[] screenXY) {
        float px = qx + nx * d;
        float py = qy + ny * d;
        float dx = -ny, dy = nx;   // 沿卷曲轴方向

        float tmin = -Float.MAX_VALUE, tmax = Float.MAX_VALUE;

        if (Math.abs(dx) < 1e-6f) {
            if (px < 0f || px > width) return false;
        } else {
            float t1 = (0f - px) / dx, t2 = (width - px) / dx;
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
        }
        if (Math.abs(dy) < 1e-6f) {
            if (py < 0f || py > height) return false;
        } else {
            float t1 = (0f - py) / dy, t2 = (height - py) / dy;
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
        }
        if (tmax <= tmin) return false;

        if (paperXY != null) {
            paperXY[0] = px + dx * tmin;
            paperXY[1] = py + dy * tmin;
            paperXY[2] = px + dx * tmax;
            paperXY[3] = py + dy * tmax;
        }
        if (screenXY != null) {
            float shift = screenOffset(d) - d;
            screenXY[0] = px + dx * tmin + nx * shift;
            screenXY[1] = py + dy * tmin + ny * shift;
            screenXY[2] = px + dx * tmax + nx * shift;
            screenXY[3] = py + dy * tmax + ny * shift;
        }
        return true;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
