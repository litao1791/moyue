package com.moyue.reader.text;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯文本的切句、段落边界与行→页映射。
 *
 * 朗读的三件事——从哪一句开始念、念到哪句翻哪页、长按落在哪一段——
 * 边界定义全在这里。做成纯 Java（不碰 android.*）是为了能在桌面跑断言：
 * 这类"差一个字符就串行"的逻辑，肉眼根本看不出来。
 */
public final class TextRange {

    private TextRange() {
    }

    /** 一句最长字数，超过就硬拆，免得一口气念不完 */
    public static final int DEFAULT_MAX_SEGMENT = 90;

    /** 句子区间 [start, end)，首尾空白已剥掉 */
    public static final class Seg {
        public final int start;
        public final int end;

        public Seg(int s, int e) {
            start = s;
            end = e;
        }

        public int length() {
            return end - start;
        }

        @Override
        public String toString() {
            return "[" + start + "," + end + ")";
        }
    }

    public static boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；'
                || c == '!' || c == '?' || c == ';' || c == '\n';
    }

    public static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n'
                || c == '\u00A0' || c == '\u3000';
    }

    /**
     * 按句末标点切句。连续空白不会单独成句；
     * 超长句子在 maxLen 处硬拆，拆出来的片段照样连续覆盖原文。
     */
    public static List<Seg> split(String text, int maxLen) {
        List<Seg> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        final int max = Math.max(8, maxLen);
        final int len = text.length();
        int start = 0;
        int i = 0;
        while (i < len) {
            char c = text.charAt(i);
            if (isSentenceEnd(c) || i - start + 1 >= max) {
                addSeg(out, text, start, i + 1);
                start = i + 1;
            }
            i++;
        }
        addSeg(out, text, start, len);
        return out;
    }

    private static void addSeg(List<Seg> out, String text, int from, int to) {
        int s = from;
        int e = to;
        while (s < e && isBlank(text.charAt(s))) s++;
        while (e > s && isBlank(text.charAt(e - 1))) e--;
        if (e > s) out.add(new Seg(s, e));
    }

    /**
     * 包含 offset 的那一句的序号。
     * offset 正好落在两句之间的空白里时，取后面那一句（从下一句开头念，不会漏字）。
     * offset 超出最后一句则退到最后一句，越界不会崩。
     */
    public static int indexAt(List<Seg> segs, int offset) {
        if (segs == null || segs.isEmpty()) return -1;
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i).end > offset) return i;
        }
        return segs.size() - 1;
    }

    /** 段落起点：往前找到最近一个换行之后 */
    public static int paragraphStart(String text, int offset) {
        if (text == null || text.isEmpty()) return 0;
        int i = clamp(offset, 0, text.length());
        while (i > 0 && text.charAt(i - 1) != '\n') i--;
        return i;
    }

    /** 段落终点（不含换行） */
    public static int paragraphEnd(String text, int offset) {
        if (text == null || text.isEmpty()) return 0;
        int i = clamp(offset, 0, text.length());
        while (i < text.length() && text.charAt(i) != '\n') i++;
        return i;
    }

    /**
     * 某一行属于第几页。pageStartLines 是每页首行的行号，严格递增。
     * 二分找最后一个 ≤ line 的页首行——朗读追页全靠它，所以单独拎出来测。
     */
    public static int pageOfLine(int[] pageStartLines, int line) {
        if (pageStartLines == null || pageStartLines.length == 0) return 0;
        int lo = 0;
        int hi = pageStartLines.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (pageStartLines[mid] <= line) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    public static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
