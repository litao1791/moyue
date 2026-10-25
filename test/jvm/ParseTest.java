import com.moyue.reader.epub.EpubBook;
import com.moyue.reader.book.TocEntry;

import java.io.File;

/**
 * EPUB 解析引擎的桌面端验证
 * 用真实的 sample.epub 跑一遍：元数据 / 目录 / spine / 章节 HTML
 */
public class ParseTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        File f = new File(args.length > 0 ? args[0] : "test/sample.epub");
        System.out.println("=== 测试文件: " + f.getAbsolutePath() + " (" + f.length() + " bytes) ===\n");

        EpubBook book = EpubBook.open(f);

        System.out.println("【元数据】");
        System.out.println("  书名: " + book.title);
        System.out.println("  作者: " + book.author);
        System.out.println("  语言: " + book.language);
        System.out.println("  出版: " + book.publisher);
        System.out.println("  OPF : " + book.opfPath);
        System.out.println("  章节数: " + book.getSpineCount());
        System.out.println("  目录条数: " + book.toc.size());
        System.out.println();

        check("书名解析正确", "山中书".equals(book.title));
        check("作者解析正确", "佚名".equals(book.author));
        check("OPF 路径正确", "OEBPS/content.opf".equals(book.opfPath));
        check("章节数为 3", book.getSpineCount() == 3);
        check("目录解析出 3 条", book.toc.size() == 3);

        System.out.println("【目录】");
        for (int i = 0; i < book.toc.size(); i++) {
            TocEntry t = book.toc.get(i);
            System.out.println("  [" + i + "] depth=" + t.depth
                    + " spine=" + t.spineIndex + "  " + t.label);
        }
        System.out.println();

        check("目录第一项标题正确",
                book.toc.size() > 0 && book.toc.get(0).label.contains("山中来客"));
        check("目录项已关联到 spine",
                book.toc.size() > 0 && book.toc.get(0).spineIndex == 0);
        check("章节标题能取到",
                book.chapterTitle(1).contains("旧书铺"));

        System.out.println("【章节内容】");
        for (int i = 0; i < book.getSpineCount(); i++) {
            String html = book.chapterHtml(i);
            boolean ok = html != null && !html.trim().isEmpty();
            System.out.println("  第" + (i + 1) + "章: " + (html == null ? 0 : html.length())
                    + " 字符  " + (ok ? "OK" : "空!"));
            check("第" + (i + 1) + "章内容非空", ok);
            check("第" + (i + 1) + "章已剥离 head/style/script",
                    html != null && !html.contains("<head") && !html.contains("<style"));
        }
        System.out.println();

        System.out.println("【第一章 HTML 片段】");
        String h0 = book.chapterHtml(0);
        System.out.println("  " + (h0.length() > 260 ? h0.substring(0, 260) + "…" : h0));
        System.out.println();
        check("正文中文正常", h0.contains("清晨的雾还没散"));
        check("章节标题保留为 h2", h0.contains("<h2>"));

        // 路径解析
        System.out.println("【路径解析】");
        check("相对路径解析 ../", EpubBook.resolve("OEBPS/text", "../styles/main.css")
                .equals("OEBPS/styles/main.css"));
        check("同级路径解析", EpubBook.resolve("OEBPS/text", "ch2.xhtml")
                .equals("OEBPS/text/ch2.xhtml"));
        check("绝对 URL 不处理", EpubBook.resolve("OEBPS", "https://x.com/a.png")
                .equals("https://x.com/a.png"));
        check("fragment 剥离", "OEBPS/text/ch1.xhtml".equals(
                EpubBook.stripFragment("OEBPS/text/ch1.xhtml#sec2")));

        book.close();

        System.out.println();
        System.out.println("==================================");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("==================================");
        if (fail > 0) System.exit(1);
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
