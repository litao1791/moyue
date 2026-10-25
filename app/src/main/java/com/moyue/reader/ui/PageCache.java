package com.moyue.reader.ui;

import android.graphics.Bitmap;

/**
 * 翻页用的整页位图缓存。
 *
 * 三个槽位。一次翻页要同时用两张（正在卷的那页 + 底下露出来的那页），
 * 第三个是留给"下一帧还要复用的那张"——只给两个槽，本帧刚好装满，
 * 下一帧取新页时必然要淘汰一张，而淘汰哪张都可能淘汰掉马上要用的，
 * 于是每帧都重建两张全屏位图（实测 60 帧建 120 张），翻页会明显卡顿。
 *
 * **核心约束：本帧已经交出去的位图，绝不能在同一个帧里被回收。**
 * 原来这里是"两个槽轮换，永远回收 B 槽"，看起来省事，实际上：
 *   前翻 0→1 用 (P0,P1)，1→2 用 (P2,P0)，到 2→3 时先取底页 P3（占掉 B 槽），
 *   再取顶页 P2 时又把 B 槽回收——回收的正是这一帧要画的底页。
 *   结果 Canvas 拿着已回收的位图去 drawBitmapMesh，抛
 *   "trying to use a recycled bitmap"，进程直接被杀。
 * 所以选槽时必须跳过本帧已经交出去的那张。
 */
public final class PageCache {

    public interface Factory {
        Bitmap create(int pageIndex);
    }

    private static final int SLOTS = 3;

    private final Factory factory;
    private final Bitmap[] slot = new Bitmap[SLOTS];
    private final int[] slotIndex = new int[SLOTS];
    /** 每个槽最后一次被取用的序号，越大越新；淘汰时挑最小的（最久没用） */
    private final long[] stamp = new long[SLOTS];
    /** 本帧已经交出去的位图，这一帧内不许回收 */
    private final Bitmap[] handed = new Bitmap[2];
    private int handedCount = 0;
    private long clock = 0;
    private int generation = -1;

    public PageCache(Factory f) {
        this.factory = f;
        for (int i = 0; i < SLOTS; i++) slotIndex[i] = -1;
    }

    /** 每次开始绘制一帧之前调用 */
    public void beginFrame() {
        handedCount = 0;
    }

    /** 版面变了（重新排版 / 换章 / 换字号 / 换主题），旧位图全部作废 */
    public void setGeneration(int gen) {
        if (generation == gen) return;
        generation = gen;
        releaseAll();
    }

    /**
     * 取某一页的位图。命中就复用，没有就新建。
     * 返回 null 表示分配失败（调用方要能退回不依赖位图的绘制方式）。
     */
    public Bitmap acquire(int pageIndex) {
        for (int i = 0; i < SLOTS; i++) {
            if (slot[i] != null && slotIndex[i] == pageIndex) {
                stamp[i] = ++clock;
                hand(slot[i]);
                return slot[i];
            }
        }

        Bitmap b;
        try {
            b = factory.create(pageIndex);
        } catch (Throwable t) {
            return null;
        }
        if (b == null) return null;

        int target = pickSlot();
        if (target < 0) {
            // 两个槽都在本帧用着，这张不进缓存（下一帧会重建），但绝对不回收它
            return b;
        }
        if (slot[target] != null) slot[target].recycle();
        slot[target] = b;
        slotIndex[target] = pageIndex;
        stamp[target] = ++clock;
        hand(b);
        return b;
    }

    /** 空槽优先；否则挑本帧没交出去里最久没用过的；都不行返回 -1 */
    private int pickSlot() {
        for (int i = 0; i < SLOTS; i++) {
            if (slot[i] == null) return i;
        }
        int best = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (isHanded(slot[i])) continue;
            if (best < 0 || stamp[i] < stamp[best]) best = i;
        }
        return best;
    }

    private void hand(Bitmap b) {
        if (handedCount < handed.length) handed[handedCount++] = b;
    }

    private boolean isHanded(Bitmap b) {
        for (int i = 0; i < handedCount; i++) {
            if (handed[i] == b) return true;
        }
        return false;
    }

    /** 当前缓存的位图张数（测试用） */
    public int cachedCount() {
        int n = 0;
        for (Bitmap b : slot) if (b != null) n++;
        return n;
    }

    /** 槽位上限（测试用） */
    public int capacity() {
        return SLOTS;
    }

    public void releaseAll() {
        for (int i = 0; i < SLOTS; i++) {
            if (slot[i] != null) {
                slot[i].recycle();
                slot[i] = null;
            }
            slotIndex[i] = -1;
            stamp[i] = 0;
        }
        handedCount = 0;
        clock = 0;
    }
}
