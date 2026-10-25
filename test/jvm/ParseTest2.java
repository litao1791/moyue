import com.moyue.reader.epub.EpubBook;
import com.moyue.reader.book.TocEntry;

import java.io.File;

/**
 * EPUB2 场景验证：NCX 目录、封面、内嵌图片、GBK 编码
 */
public class ParseTest2 {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        File f = new File(args.length > 0 ? args[0] : "test/sample2.epub");
        System.out.println("=== 测试文件: " + f.getAbsolutePath() + " (" + f.length() + " bytes) ===\n");

        EpubBook book = EpubBook.open(f);

        System.out.println("【元数据】");
        System.out.println("  书名: " + book.title);
        System.out.println("  作者: " + book.author);
        System.out.println("  章节数: " + book.getSpineCount());
        System.out.println("  目录条数: " + book.toc.size());
        System.out.println();

        check("书名正确", "旧编码之书".equals(book.title));
        check("作者正确", "某作者".equals(book.author));
        check("spine 为 2 章", book.getSpineCount() == 2);

        System.out.println("【NCX 目录（含二级）】");
        for (TocEntry t : book.toc) {
            System.out.println("  depth=" + t.depth + " spine=" + t.spineIndex + "  " + t.label);
        }
        System.out.println();

        check("NCX 解析出 3 条（含嵌套）", book.toc.size() == 3);
        check("一级标题正确", book.toc.size() > 0 && book.toc.get(0).label.contains("有图的一章"));
        check("二级目录 depth=1", book.toc.size() > 1 && book.toc.get(1).depth == 1);
        check("带 fragment 的目录项仍能定位章节",
                book.toc.size() > 1 && book.toc.get(1).spineIndex == 0);

        System.out.println("【封面】");
        check("通过 meta[name=cover] 找到封面", book.getCoverPath() != null
                && book.getCoverPath().contains("cover.png"));
        System.out.println("  cover = " + book.getCoverPath());

        System.out.println("【图片章节】");
        String h1 = book.chapterHtml(0);
        check("图片被替换为占位符", h1.contains("moyue-img:0"));
        check("原始相对路径已消除", !h1.contains("../images/fig1.png"));
        System.out.println("  片段: " + h1.replaceAll("\\s+", " ").trim());

        android.graphics.Bitmap bmp = book.getChapterImage(0, 600);
        check("图片能解码出位图", bmp != null);
        System.out.println("  图片尺寸: " + (bmp == null ? "null"
                : bmp.getWidth() + "x" + bmp.getHeight()));

        System.out.println("【GBK 编码章节】");
        String h2 = book.chapterHtml(1);
        System.out.println("  片段: " + h2.replaceAll("\\s+", " ").trim());
        check("GBK 中文未乱码", h2.contains("这一章故意用 GBK 编码保存"));
        check("GBK 第二段正常", h2.contains("说明编码嗅探没生效"));

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
