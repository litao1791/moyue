package com.moyue.reader.book;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯文本（TXT）书源
 *
 * 负责：
 *  - 编码自动识别（BOM / UTF-8 / GB18030）
 *  - 按"第X章"等标记切分章节，切不出来就按篇幅均分
 *  - 段落转 HTML 供阅读器排版
 */
public class TxtBook implements BookSource {

    /** 常见章节标题行 */
    private static final Pattern CHAPTER_RE = Pattern.compile(
            "^(?:第[0-9一二三四五六七八九十百千万零〇两]{1,12}[章节回卷篇部集]"
                    + "|序章|序言|楔子|引子|前言|后记|尾声|番外|终章"
                    + "|Chapter\\s*\\d+|CHAPTER\\s*\\d+).{0,30}$");

    private static final Pattern AUTHOR_RE = Pattern.compile(
            "(?:作者|作\\s*者)\\s*[:：]\\s*([^\\s\\n\\r]{1,24})");

    /** 无章节标记时，每节约多少字符 */
    private static final int CHUNK_SIZE = 6000;

    private final File file;
    private String text = "";
    private String title = "";
    private String author = "";

    private final List<int[]> ranges = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private final List<TocEntry> toc = new ArrayList<>();

    private TxtBook(File f) {
        this.file = f;
    }

    public static TxtBook open(File f) throws Exception {
        TxtBook b = new TxtBook(f);
        b.load();
        return b;
    }

    // ==================== 载入 ====================

    private void load() throws Exception {
        byte[] data = readAll(file);
        if (data.length == 0) throw new Exception("文件为空");

        text = decode(data);
        // 去掉残留 BOM
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');

        if (text.trim().isEmpty()) throw new Exception("文件没有可读文本");

        title = guessTitle();
        author = guessAuthor();

        splitChapters();
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** 编码嗅探：BOM → 严格 UTF-8 → GB18030 */
    static String decode(byte[] d) {
        int len = d.length;

        // UTF-8 BOM
        if (len >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF) {
            return new String(d, 3, len - 3, Charset.forName("UTF-8"));
        }
        // UTF-16 LE / BE
        if (len >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) {
            return new String(d, 2, len - 2, Charset.forName("UTF-16LE"));
        }
        if (len >= 2 && (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF) {
            return new String(d, 2, len - 2, Charset.forName("UTF-16BE"));
        }

        // 严格 UTF-8
        try {
            CharsetDecoder dec = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return dec.decode(ByteBuffer.wrap(d)).toString();
        } catch (CharacterCodingException ignored) {
        }

        // 退回 GB18030（兼容 GBK / GB2312）
        try {
            return new String(d, Charset.forName("GB18030"));
        } catch (Throwable t) {
            return new String(d, Charset.forName("UTF-8"));
        }
    }

    private String guessTitle() {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.trim();
        return name.isEmpty() ? "未命名" : name;
    }

    private String guessAuthor() {
        int limit = Math.min(text.length(), 5000);
        Matcher m = AUTHOR_RE.matcher(text.substring(0, limit));
        if (m.find()) {
            String a = m.group(1).trim();
            // 过滤掉明显不是人名的（比如"作者：请收藏本站"）
            if (a.length() <= 20 && !a.contains("：") && !a.contains(":")) return a;
        }
        return "";
    }

    // ==================== 章节切分 ====================

    private void splitChapters() {
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();

        int idx = 0;
        int len = text.length();
        while (idx < len) {
            int nl = text.indexOf('\n', idx);
            int lineEnd = nl < 0 ? len : nl;
            String line = text.substring(idx, lineEnd).replace('\u3000', ' ').trim();

            if (!line.isEmpty() && line.length() <= 42 && CHAPTER_RE.matcher(line).matches()) {
                starts.add(idx);
                names.add(line);
            }
            if (nl < 0) break;
            idx = nl + 1;
        }

        if (starts.size() < 2) {
            // 切不出来就按篇幅均分
            starts.clear();
            names.clear();
            int pos = 0;
            int part = 1;
            while (pos < len) {
                starts.add(pos);
                names.add("第 " + part + " 节");
                int next = Math.min(len, pos + CHUNK_SIZE);
                if (next < len) {
                    int nl = text.indexOf('\n', next);
                    if (nl > 0 && nl - next < 2000) next = nl + 1;
                }
                pos = next;
                part++;
            }
        } else if (starts.get(0) > 0) {
            // 正文开头有前言性质的内容
            String head = text.substring(0, starts.get(0)).trim();
            if (head.length() > 30) {
                starts.add(0, 0);
                names.add(0, "开篇");
            }
        }

        ranges.clear();
        titles.clear();
        toc.clear();

        for (int i = 0; i < starts.size(); i++) {
            int s = starts.get(i);
            int e = (i + 1 < starts.size()) ? starts.get(i + 1) : len;
            if (e <= s) continue;
            int chapterIndex = titles.size();
            ranges.add(new int[]{s, e});
            titles.add(names.get(i));
            toc.add(new TocEntry(names.get(i), chapterIndex));
        }

        if (ranges.isEmpty()) {
            ranges.add(new int[]{0, len});
            titles.add("正文");
            toc.add(new TocEntry("正文", 0));
        }
    }

    // ==================== BookSource 实现 ====================

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getAuthor() {
        return author;
    }

    @Override
    public int getChapterCount() {
        return ranges.size();
    }

    @Override
    public String getChapterTitle(int index) {
        if (index < 0 || index >= titles.size()) return "";
        return titles.get(index);
    }

    @Override
    public String getChapterHtml(int index) {
        if (index < 0 || index >= ranges.size()) return "";
        int[] r = ranges.get(index);
        return toHtml(text.substring(r[0], r[1]));
    }

    @Override
    public Bitmap getChapterImage(int index, int targetWidth) {
        return null;
    }

    @Override
    public Bitmap getCoverBitmap(int maxWidth) {
        return null;
    }

    @Override
    public List<TocEntry> getToc() {
        return toc;
    }

    @Override
    public long getChapterWeight(int index) {
        if (index < 0 || index >= ranges.size()) return 1;
        int[] r = ranges.get(index);
        return Math.max(200, r[1] - r[0]);
    }

    @Override
    public void close() {
        text = "";
        ranges.clear();
        titles.clear();
        toc.clear();
    }

    // ==================== 排版 ====================

    /**
     * 纯文本转 HTML 片段。
     *
     * 分段策略：先看行的平均长度。
     * 中文小说通常「一行一段」且行较短，此时单换行即段落；
     * 英文等文本常见硬折行，此时只有空行才算分段。
     */
    public static String toHtml(String raw) {
        String t = raw.trim();
        if (t.isEmpty()) return "";

        String[] allLines = t.split("\n");
        int nonEmpty = 0;
        long totalLen = 0;
        for (String l : allLines) {
            String s = l.trim();
            if (!s.isEmpty()) {
                nonEmpty++;
                totalLen += s.length();
            }
        }
        float avg = nonEmpty == 0 ? 0f : (float) totalLen / nonEmpty;

        boolean lineIsParagraph = avg < 60f || !t.contains("\n\n");
        String[] paras = lineIsParagraph ? allLines : t.split("\n[ \\t\u3000]*\n");

        StringBuilder sb = new StringBuilder(t.length() + paras.length * 8);
        for (String p : paras) {
            String s = p.trim();
            if (s.isEmpty()) continue;
            if (!lineIsParagraph) s = s.replace('\n', ' ');
            sb.append("<p>").append(escape(s)).append("</p>");
        }
        return sb.toString();
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                default:
                    // 过滤控制字符，保留换行/制表
                    if (c < 0x20 && c != '\n' && c != '\t') sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
