import com.moyue.reader.book.BookSource;
import com.moyue.reader.book.TocEntry;
import com.moyue.reader.book.TxtBook;

import java.io.File;

/**
 * TXT 书源验证：编码识别、章节切分、段落排版
 */
public class TxtTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testUtf8WithChapters();
        testGbk();
        testPlainChunking();
        testEscape();
        testTypeDetect();

        System.out.println();
        System.out.println("==================================");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("==================================");
        if (fail > 0) System.exit(1);
    }

    private static void testUtf8WithChapters() throws Exception {
        System.out.println("【UTF-8 + 章节标记】");
        File f = new File("test/sample.txt");
        TxtBook b = TxtBook.open(f);

        System.out.println("  书名: " + b.getTitle());
        System.out.println("  作者: " + b.getAuthor());
        System.out.println("  章节数: " + b.getChapterCount());
        for (TocEntry t : b.getToc()) {
            System.out.println("    [" + t.spineIndex + "] " + t.label);
        }

        check("书名取文件名", "sample".equals(b.getTitle()));
        check("识别出作者", "老周".equals(b.getAuthor()));
        check("切出 3 章", b.getChapterCount() == 3);
        check("第一章标题正确", b.getChapterTitle(0).contains("渡口"));
        check("第二章标题正确", b.getChapterTitle(1).contains("河心"));
        check("目录与章节数一致", b.getToc().size() == 3);

        String h = b.getChapterHtml(0);
        System.out.println("  第一章 HTML: " + h.substring(0, Math.min(90, h.length())) + "…");
        check("正文有段落标签", h.contains("<p>"));
        check("中文内容正确", h.contains("渡口的木板上结了一层薄霜"));
        check("引号内容保留", h.contains("「这么早。」"));

        String last = b.getChapterHtml(2);
        check("末章内容正确", last.contains("一排排站在岸边"));

        check("权重有下限保护", b.getChapterWeight(0) >= 200);
        b.close();
        System.out.println();
    }

    private static void testGbk() throws Exception {
        System.out.println("【GBK 编码】");
        TxtBook b = TxtBook.open(new File("test/sample_gbk.txt"));
        System.out.println("  章节数: " + b.getChapterCount());

        check("GBK 切出 2 章", b.getChapterCount() == 2);
        String h = b.getChapterHtml(0);
        System.out.println("  片段: " + h.replaceAll("\\s+", " ").trim());
        check("GBK 中文未乱码", h.contains("抽屉最底下压着一封信"));
        check("GBK 标题正确", b.getChapterTitle(1).contains("回音"));
        b.close();
        System.out.println();
    }

    private static void testPlainChunking() throws Exception {
        System.out.println("【无章节标记 → 按篇幅切分】");
        TxtBook b = TxtBook.open(new File("test/sample_plain.txt"));
        System.out.println("  章节数: " + b.getChapterCount());
        System.out.println("  第一章标题: " + b.getChapterTitle(0));

        check("自动切出多节", b.getChapterCount() >= 2);
        check("节标题为『第 N 节』", b.getChapterTitle(0).startsWith("第"));
        String h = b.getChapterHtml(0);
        check("切分后内容非空", h.length() > 100);
        b.close();
        System.out.println();
    }

    private static void testEscape() {
        System.out.println("【HTML 转义】");
        String h = TxtBook.toHtml("a < b && c > d\n\n第二段");
        System.out.println("  " + h);
        check("小于号被转义", h.contains("&lt;"));
        check("大于号被转义", h.contains("&gt;"));
        check("和号被转义", h.contains("&amp;"));
        check("两段都保留", h.contains("第二段"));
        check("段落数正确", h.split("<p>").length - 1 == 2);
        System.out.println();
    }

    private static void testTypeDetect() throws Exception {
        System.out.println("【格式自动识别】");
        check("epub 识别为 zip", BookSource.isZip(new File("test/sample.epub")));
        check("txt 不识别为 zip", !BookSource.isZip(new File("test/sample.txt")));

        BookSource a = BookSource.open(new File("test/sample.epub"));
        check("EPUB 走 EpubBook", a instanceof com.moyue.reader.epub.EpubBook);
        a.close();

        BookSource t = BookSource.open(new File("test/sample.txt"));
        check("TXT 走 TxtBook", t instanceof TxtBook);
        t.close();
        System.out.println();
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name + "   <-- 失败");
        }
    }
}
