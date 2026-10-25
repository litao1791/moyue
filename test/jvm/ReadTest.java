import com.moyue.reader.text.TextRange;
import com.moyue.reader.tts.VoiceStyle;

import java.util.List;
import java.util.Random;

/**
 * 朗读相关的文本定位验证。
 *
 * 锁的是三条"差一个字符就整段错位、但肉眼根本看不出来"的约束：
 *   - 切句：不能漏字、不能重叠、不能切出空句
 *   - 长按定位：任何字符位置都必须落在 [段首, 段尾) 里，否则点一段会读到别的段
 *   - 追页：念到后面时页码不能倒退，否则声音往前、画面往回跳
 */
public class ReadTest {

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

    public static void main(String[] args) {
        System.out.println("=== 朗读定位验证 ===\n");

        splitTests();
        indexTests();
        paragraphTests();
        pageTests();
        followTests();
        voiceTests();

        System.out.println("\n==================================");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("==================================");
        if (fail > 0) System.exit(1);
    }

    // ---------- 切句 ----------

    private static void splitTests() {
        System.out.println("【切句：不漏字、不重叠、不出空句】");

        String t1 = "第一句。第二句！第三句？";
        List<TextRange.Seg> s1 = TextRange.split(t1, 90);
        check("三个句号切成三句", s1.size() == 3, "实际=" + s1.size());
        check("首句从 0 开始", !s1.isEmpty() && s1.get(0).start == 0, s1.toString());
        check("末句覆盖到文本结尾",
                !s1.isEmpty() && s1.get(s1.size() - 1).end == t1.length(),
                "end=" + (s1.isEmpty() ? -1 : s1.get(s1.size() - 1).end) + " len=" + t1.length());
        check("每句都带句末标点",
                s1.size() == 3
                        && t1.charAt(s1.get(0).end - 1) == '。'
                        && t1.charAt(s1.get(1).end - 1) == '！'
                        && t1.charAt(s1.get(2).end - 1) == '？',
                "");

        check("空文本切出 0 句", TextRange.split("", 90).isEmpty(), "");
        check("null 切出 0 句", TextRange.split(null, 90).isEmpty(), "");
        check("纯空白切出 0 句", TextRange.split("  \n\n \t ", 90).isEmpty(), "");

        // 无标点长文本：必须按 maxLen 硬拆，且拆完照样连续覆盖
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append((char) ('一' + i % 20));
        String longText = sb.toString();
        List<TextRange.Seg> s2 = TextRange.split(longText, 50);
        boolean lenOk = true;
        for (TextRange.Seg g : s2) if (g.length() > 50) lenOk = false;
        check("超长句按上限硬拆", lenOk && s2.size() == 8, "段数=" + s2.size() + " 上限=50");
        check("硬拆后仍覆盖到结尾",
                s2.get(s2.size() - 1).end == longText.length(), "end=" + s2.get(s2.size() - 1).end);

        // 覆盖性：每个非空白字符都必须被某一句包住，且不重复
        String t3 = "　山中有书。\n\n书里有人，人说：\n“你来晚了！”\n他笑了。   \n";
        List<TextRange.Seg> s3 = TextRange.split(t3, 90);
        int[] cover = new int[t3.length()];
        for (TextRange.Seg g : s3) {
            for (int i = g.start; i < g.end; i++) cover[i]++;
        }
        int missed = 0, dup = 0, blankCovered = 0;
        for (int i = 0; i < t3.length(); i++) {
            char c = t3.charAt(i);
            if (cover[i] > 1) dup++;
            if (cover[i] == 1 && TextRange.isBlank(c)) blankCovered++;
            if (cover[i] == 0 && !TextRange.isBlank(c)) missed++;
        }
        check("没有漏掉任何字", missed == 0, "漏=" + missed);
        check("句子之间不重叠", dup == 0, "重叠=" + dup);
        check("句首句尾不含空白", blankCovered == 0, "空白=" + blankCovered);

        boolean ordered = true;
        for (int i = 1; i < s3.size(); i++) {
            if (s3.get(i).start <= s3.get(i - 1).start) ordered = false;
        }
        check("句子起点严格递增", ordered, s3.toString());
    }

    // ---------- 从某处开始念 ----------

    private static void indexTests() {
        System.out.println("\n【长按选起点：落在哪句就从哪句起】");

        String t = "第一句。第二句。第三句。";
        List<TextRange.Seg> segs = TextRange.split(t, 90);

        check("按在第 1 句开头 → 第 1 句", TextRange.indexAt(segs, 0) == 0, "");
        check("按在第 1 句中间 → 第 1 句（不会半句起头）",
                TextRange.indexAt(segs, 2) == 0, "");
        check("按在第 2 句开头 → 第 2 句", TextRange.indexAt(segs, 4) == 1, "");
        check("按在第 3 句中间 → 第 3 句", TextRange.indexAt(segs, 9) == 2, "");

        // 两句之间的空白：取后一句，不能漏字
        String t2 = "甲。  乙。";
        List<TextRange.Seg> s2 = TextRange.split(t2, 90);
        int gap = t2.indexOf(' ') + 1;
        int idx = TextRange.indexAt(s2, gap);
        check("按在两句之间的空白 → 后一句",
                idx == 1 && s2.get(idx).start == t2.indexOf('乙'), "idx=" + idx);

        check("越界位置退到最后一句",
                TextRange.indexAt(segs, 9999) == segs.size() - 1, "");
        check("空列表返回 -1", TextRange.indexAt(TextRange.split("", 90), 0) == -1, "");

        // 全文本扫描：任何位置都必须能定位到一句，且那句确实包住它（或紧随其后）
        int bad = 0;
        for (int off = 0; off < t.length(); off++) {
            int i = TextRange.indexAt(segs, off);
            if (i < 0) {
                bad++;
                continue;
            }
            TextRange.Seg g = segs.get(i);
            boolean contains = off >= g.start && off < g.end;
            boolean next = off < g.start;   // 落在空白里，指向后一句也算对
            if (!contains && !next) bad++;
        }
        check("每个字符位置都能定位到有效句子", bad == 0, "异常=" + bad + " / " + t.length());
    }

    // ---------- 段落 ----------

    private static void paragraphTests() {
        System.out.println("\n【长按点亮：段落边界必须夹住按下的位置】");

        String t = "第一段\n第二段内容\n\n第三段\n第四段";
        int p1 = t.indexOf("第二段内容") + 2;
        check("段首 = 上一个换行之后",
                TextRange.paragraphStart(t, p1) == t.indexOf("第二段内容"),
                "得到 " + TextRange.paragraphStart(t, p1) + " 期望 " + t.indexOf("第二段内容"));
        check("段尾 = 下一个换行之前",
                TextRange.paragraphEnd(t, p1) == t.indexOf("第二段内容") + 5,
                "得到 " + TextRange.paragraphEnd(t, p1));

        int p2 = t.indexOf("第三段") + 1;
        int ps = TextRange.paragraphStart(t, p2);
        int pe = TextRange.paragraphEnd(t, p2);
        check("第三段单独成段", ps == t.indexOf("第三段"), "ps=" + ps);
        check("第三段到行尾结束", pe == t.indexOf("第三段") + 3, "pe=" + pe);

        check("空文本段首为 0", TextRange.paragraphStart("", 5) == 0, "");
        check("越界位置夹回文本末尾",
                TextRange.paragraphStart(t, 9999) == TextRange.paragraphStart(t, t.length()),
                "");
        check("段尾不会超出文本", TextRange.paragraphEnd(t, 9999) <= t.length(), "");

        // 不变量：任何位置都必须满足 段首 ≤ 位置 ≤ 段尾
        int bad = 0;
        for (int off = 0; off <= t.length(); off++) {
            int a = TextRange.paragraphStart(t, off);
            int b = TextRange.paragraphEnd(t, off);
            if (!(a <= off && off <= b && a <= b)) bad++;
        }
        check("任何位置都被段落夹住", bad == 0, "异常=" + bad);
    }

    // ---------- 行 → 页 ----------

    private static void pageTests() {
        System.out.println("\n【念到哪句翻哪页：行→页映射】");

        int[] pages = {0, 12, 24, 36, 48, 60};
        check("第 0 行在第 0 页", TextRange.pageOfLine(pages, 0) == 0, "");
        check("第 11 行还在第 0 页", TextRange.pageOfLine(pages, 11) == 0, "");
        check("第 12 行正好翻到第 1 页", TextRange.pageOfLine(pages, 12) == 1, "");
        check("第 59 行在第 4 页", TextRange.pageOfLine(pages, 59) == 4, "");
        check("第 60 行在第 5 页", TextRange.pageOfLine(pages, 60) == 5, "");
        check("超出末页夹在最后一页",
                TextRange.pageOfLine(pages, 9999) == pages.length - 1, "");
        check("空页码表返回 0", TextRange.pageOfLine(new int[0], 5) == 0, "");
        check("null 页码表返回 0", TextRange.pageOfLine(null, 5) == 0, "");

        // 和线性扫描对拍：随机分页表 × 随机行号，二分不能有边界错
        Random rnd = new Random(20261025L);
        int mismatch = 0, cases = 0;
        for (int round = 0; round < 200; round++) {
            int n = 1 + rnd.nextInt(40);
            int[] starts = new int[n];
            int acc = 0;
            for (int i = 0; i < n; i++) {
                starts[i] = acc;
                acc += 1 + rnd.nextInt(9);
            }
            for (int line = 0; line < acc + 5; line++) {
                int expect = 0;
                for (int i = 0; i < n; i++) if (starts[i] <= line) expect = i;
                int got = TextRange.pageOfLine(starts, line);
                cases++;
                if (got != expect) mismatch++;
            }
        }
        check("二分结果与线性扫描一致", mismatch == 0, cases + " 组随机用例");

        boolean mono = true;
        for (int line = 1; line < 200; line++) {
            if (TextRange.pageOfLine(pages, line) < TextRange.pageOfLine(pages, line - 1)) mono = false;
        }
        check("行号变大页码不会倒退", mono, "");
    }

    // ---------- 朗读角色 ----------

    private static void voiceTests() {
        System.out.println("\n【朗读角色：音高要拉开差距，又不能失真】");

        check("角色数量够选", VoiceStyle.ALL.length >= 5, VoiceStyle.ALL.length + " 个");

        boolean pitchInRange = true;
        boolean audibleGap = true;
        float prev = -1f;
        for (VoiceStyle v : VoiceStyle.ALL) {
            if (v.pitch < 0.7f || v.pitch > 1.4f) pitchInRange = false;
            if (prev > 0 && Math.abs(v.pitch - prev) < 0.05f) audibleGap = false;
            prev = v.pitch;
        }
        check("音调都在不失真的区间 [0.7, 1.4]", pitchInRange, "");
        check("相邻角色至少差 0.05，能听出区别", audibleGap, "");

        check("原声就是原声（pitch = 1.0）", Math.abs(VoiceStyle.ALL[0].pitch - 1f) < 1e-6,
                "pitch=" + VoiceStyle.ALL[0].pitch);

        java.util.Set<Integer> ids = new java.util.HashSet<>();
        java.util.Set<String> labels = new java.util.HashSet<>();
        boolean clean = true;
        for (VoiceStyle v : VoiceStyle.ALL) {
            if (!ids.add(v.id)) clean = false;
            if (!labels.add(v.label)) clean = false;
            if (v.label == null || v.label.isEmpty()) clean = false;
        }
        check("id 与名字都不重复", clean, ids.size() + " 个 id");

        check("找不到的 id 退回原声", VoiceStyle.byId(999) == VoiceStyle.ALL[0], "");
        check("负数 id 也退回原声", VoiceStyle.byId(-3) == VoiceStyle.ALL[0], "");
        check("按 id 能取回自己", VoiceStyle.byId(VoiceStyle.ALL[3].id) == VoiceStyle.ALL[3], "");

        check("NaN 音调夹成原声", Math.abs(VoiceStyle.clampPitch(Float.NaN) - 1f) < 1e-6, "");
        check("过高音调被夹到上限",
                Math.abs(VoiceStyle.clampPitch(9f) - VoiceStyle.MAX_PITCH) < 1e-6, "");
        check("过低音调被夹到下限",
                Math.abs(VoiceStyle.clampPitch(0.01f) - VoiceStyle.MIN_PITCH) < 1e-6, "");
    }

    // ---------- 朗读追页的端到端性质 ----------

    private static void followTests() {
        System.out.println("\n【朗读追页：整章扫一遍，页码只前进不后退】");

        StringBuilder sb = new StringBuilder();
        String[] sentences = {
                "山里的雨下了一夜。", "早上起来，屋檐还在滴水。", "他把书摊在窗台上晾着。",
                "纸页吸了潮气，软得像布。", "他翻一页，停一下，又翻一页。",
        };
        for (int i = 0; i < 60; i++) {
            sb.append(sentences[i % sentences.length]);
            if (i % 4 == 3) sb.append('\n');
        }
        String chapter = sb.toString();

        List<TextRange.Seg> segs = TextRange.split(chapter, TextRange.DEFAULT_MAX_SEGMENT);
        check("整章切出多句", segs.size() > 20, "句数=" + segs.size());

        // 假设一页装 9 行、每句占 1 行（近似真实分页），行号 = 句序号
        int perPage = 9;
        int pageCount = (segs.size() + perPage - 1) / perPage;
        int[] pageStartLines = new int[pageCount];
        for (int i = 0; i < pageCount; i++) pageStartLines[i] = i * perPage;

        // 逐句走一遍：每句都算出"该翻到哪页"，页码必须单调不减
        int lastPage = 0;
        boolean mono = true;
        boolean everySentencePlaced = true;
        for (int i = 0; i < segs.size(); i++) {
            int page = TextRange.pageOfLine(pageStartLines, i);
            if (page < lastPage) mono = false;
            if (page < 0 || page >= pageCount) everySentencePlaced = false;
            lastPage = page;
        }
        check("念到后面页码不会往回跳", mono, segs.size() + " 句");
        check("每句都落在有效页内", everySentencePlaced, "共 " + pageCount + " 页");
        check("最后一句落在最后一页", lastPage == pageCount - 1, "lastPage=" + lastPage);

        // 反过来：每一页都必须有句子能落到它上面，不能出现"永远翻不到"的页
        boolean[] hit = new boolean[pageCount];
        for (int i = 0; i < segs.size(); i++) hit[TextRange.pageOfLine(pageStartLines, i)] = true;
        int unhit = 0;
        for (boolean b : hit) if (!b) unhit++;
        check("没有翻不到的页", unhit == 0, "未命中=" + unhit);

        // 长按任意一句的开头，起念的句子必须就是那一句
        int wrong = 0;
        for (int i = 0; i < segs.size(); i++) {
            int got = TextRange.indexAt(segs, segs.get(i).start);
            if (got != i) wrong++;
        }
        check("按句首起念不会串到别的句", wrong == 0, segs.size() + " 句");
    }
}
