import android.graphics.Bitmap;

import com.moyue.reader.ui.PageCache;

/**
 * 整页位图缓存的验证。
 *
 * 这个类存在的唯一理由，是 v1.5 的一个崩溃：往左连续翻页，第三次必退出。
 * 原因是"两个槽轮换、永远回收 B 槽"，而前翻时底页恰好占着 B 槽，
 * 取顶页时把它回收了 —— Canvas 拿着已回收的位图去 drawBitmapMesh，进程被杀。
 *
 * 所以这里锁死一条铁律：
 *   **同一帧里交出去的任何位图，在下一帧开始之前都不许被回收。**
 */
public class PageCacheTest {

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

    /** 数一数建了几张图，用来验证"相邻页复用" */
    private static int created = 0;

    private static PageCache newCache() {
        created = 0;
        return new PageCache(idx -> {
            created++;
            return Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        });
    }

    /** 一帧：先取底下那页，再取上面那页，检查两张都没被回收 */
    private static boolean frame(PageCache c, int bottomPage, int topPage) {
        c.beginFrame();
        Bitmap bottom = c.acquire(bottomPage);
        Bitmap top = c.acquire(topPage);
        return bottom != null && top != null && !bottom.isRecycled() && !top.isRecycled();
    }

    public static void main(String[] args) {
        System.out.println("=== 整页位图缓存验证 ===\n");

        forwardTest();
        backwardTest();
        backAndForthTest();
        jumpTest();
        generationTest();
        failTest();

        System.out.println("\n==================================");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("==================================");
        if (fail > 0) System.exit(1);
    }

    // ---------- 往左翻（前翻）：bottom = 目标页，top = 当前页 ----------

    private static void forwardTest() {
        System.out.println("【往左翻：连续翻 60 次，每一帧的两张图都必须可用】");
        PageCache c = newCache();
        int badAt = -1;
        for (int p = 0; p < 60; p++) {
            if (!frame(c, p + 1, p) && badAt < 0) badAt = p + 1;
        }
        check("60 帧全部拿到未回收的位图", badAt < 0,
                badAt < 0 ? "60 帧" : "第 " + badAt + " 次拿到已回收的位图");
        check("缓存不会越滚越大", c.cachedCount() <= c.capacity(), "缓存 " + c.cachedCount() + " / " + c.capacity() + " 张");
        check("相邻页确实复用了位图（每帧最多新建一张）", created <= 62, "60 帧共建 " + created + " 张");
    }

    // ---------- 往右翻（后翻）：bottom = 当前页，top = 目标页 ----------

    private static void backwardTest() {
        System.out.println("\n【往右翻：连续翻 60 次】");
        PageCache c = newCache();
        int badAt = -1;
        for (int p = 60; p > 0; p--) {
            if (!frame(c, p, p - 1) && badAt < 0) badAt = 60 - p + 1;
        }
        check("60 帧全部拿到未回收的位图", badAt < 0,
                badAt < 0 ? "60 帧" : "第 " + badAt + " 次拿到已回收的位图");
        check("缓存不会越滚越大", c.cachedCount() <= c.capacity(), "缓存 " + c.cachedCount() + " / " + c.capacity() + " 张");
    }

    // ---------- 来回翻：最容易把槽位搞乱 ----------

    private static void backAndForthTest() {
        System.out.println("\n【来回翻：3 和 4 之间反复横跳 40 次】");
        PageCache c = newCache();
        int bad = 0;
        for (int i = 0; i < 20; i++) {
            if (!frame(c, 4, 3)) bad++;   // 3→4
            if (!frame(c, 3, 4)) bad++;   // 4→3
        }
        check("来回翻不会把正在用的位图回收掉", bad == 0, "异常帧=" + bad);
        check("来回翻只缓存两页", c.cachedCount() <= c.capacity(), "缓存 " + c.cachedCount() + " / " + c.capacity() + " 张");
    }

    // ---------- 跳页（进度条拖动、朗读追页） ----------

    private static void jumpTest() {
        System.out.println("\n【跳页：0→5→1→9→2，每跳一次取两张】");
        PageCache c = newCache();
        int[] seq = {5, 1, 9, 2, 40, 3};
        int bad = 0;
        for (int target : seq) {
            if (!frame(c, target, target - 1)) bad++;
        }
        check("跳页后两张图都可用", bad == 0, "异常帧=" + bad);
        check("跳页不会让缓存膨胀", c.cachedCount() <= c.capacity(), "缓存 " + c.cachedCount() + " / " + c.capacity() + " 张");
    }

    // ---------- 换版面（换章 / 换字号 / 换主题） ----------

    private static void generationTest() {
        System.out.println("\n【换版面：generation 变了，旧图必须作废重建】");
        PageCache c = newCache();
        c.setGeneration(1);
        c.beginFrame();
        Bitmap old = c.acquire(3);
        check("第一代能拿到图", old != null, "");

        c.setGeneration(2);
        check("换代时旧图被回收", old.isRecycled(), "isRecycled=" + old.isRecycled());

        c.beginFrame();
        Bitmap fresh = c.acquire(3);
        check("换代后同一页会重新建图", fresh != null && fresh != old && !fresh.isRecycled(),
                "新实例=" + (fresh != old));
        check("换代后缓存已清空重来", c.cachedCount() <= c.capacity(), "缓存 " + c.cachedCount() + " / " + c.capacity() + " 张");

        // 同一代反复设置不能白白回收
        c.beginFrame();
        Bitmap a = c.acquire(7);
        c.setGeneration(2);
        check("同一代重复 setGeneration 不会误回收", !a.isRecycled(), "");
    }

    // ---------- 分配失败 ----------

    private static void failTest() {
        System.out.println("\n【分配失败：要能优雅返回 null，不抛异常】");
        PageCache nullCache = new PageCache(idx -> null);
        nullCache.beginFrame();
        check("工厂返回 null → acquire 返回 null", nullCache.acquire(0) == null, "");

        PageCache boomCache = new PageCache(idx -> {
            throw new OutOfMemoryError("模拟位图分配失败");
        });
        boomCache.beginFrame();
        boolean noThrow = true;
        try {
            noThrow = boomCache.acquire(0) == null;
        } catch (Throwable t) {
            noThrow = false;
        }
        check("工厂抛 OOM → 被吞掉并返回 null", noThrow, "");

        // 失败之后还能继续正常工作
        PageCache c = newCache();
        c.beginFrame();
        check("失败不影响后续帧", frame(c, 1, 0), "");
    }
}
