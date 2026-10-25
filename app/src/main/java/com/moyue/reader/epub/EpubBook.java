package com.moyue.reader.epub;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.moyue.reader.book.BookSource;
import com.moyue.reader.book.TocEntry;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * EPUB 2 / 3 解析器（纯原生，无第三方依赖）
 */
public class EpubBook implements BookSource {

    public static class ManifestItem {
        public String id;
        public String href;      // 已解析为 zip 内绝对路径
        public String mediaType;
        public String properties;
    }

    public static class SpineItem {
        public String href;
        public long byteLength;
        public int charCount;
    }

    private final File file;
    private ZipFile zip;

    public String title = "未命名";
    public String author = "";
    public String language = "zh";
    public String publisher = "";
    public String description = "";
    public String identifier = "";

    public final List<ManifestItem> manifest = new ArrayList<>();
    public final List<SpineItem> spine = new ArrayList<>();
    public final List<TocEntry> toc = new ArrayList<>();

    public String opfPath = "";
    private String opfDir = "";
    private String coverPath = "";

    private final Map<String, ManifestItem> byId = new HashMap<>();
    private final Map<String, Integer> pathToSpine = new HashMap<>();
    private final List<String> pendingImages = new ArrayList<>();
    private final Map<String, Bitmap> imageCache = new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 12;
        }
    };

    private EpubBook(File f) {
        this.file = f;
    }

    public static EpubBook open(File f) throws Exception {
        EpubBook b = new EpubBook(f);
        b.zip = new ZipFile(f);
        b.parse();
        return b;
    }

    public void close() {
        imageCache.clear();
        try {
            if (zip != null) zip.close();
        } catch (IOException ignored) {
        }
    }

    public File getFile() {
        return file;
    }

    /** 封面在 zip 内的路径，未找到时为空串 */
    public String getCoverPath() {
        return coverPath == null ? "" : coverPath;
    }

    // ==================== 解析 ====================

    private void parse() throws Exception {
        String container = readText("META-INF/container.xml");
        if (container == null) throw new IOException("缺少 container.xml，不是标准 EPUB");

        Document cdoc = parseXml(container.getBytes("UTF-8"));
        NodeList roots = cdoc.getElementsByTagName("rootfile");
        if (roots.getLength() == 0) throw new IOException("container.xml 无 rootfile");
        opfPath = ((Element) roots.item(0)).getAttribute("full-path");
        if (opfPath == null || opfPath.isEmpty()) throw new IOException("OPF 路径为空");
        opfDir = dirName(opfPath);

        String opfXml = readText(opfPath);
        if (opfXml == null) throw new IOException("找不到 OPF: " + opfPath);
        Document opf = parseXml(opfXml.getBytes("UTF-8"));

        parseMetadata(opf);
        parseManifest(opf);
        parseSpine(opf);
        parseToc(opf);

        // 建立 href -> spine 索引
        for (int i = 0; i < spine.size(); i++) {
            String key = stripFragment(spine.get(i).href);
            if (!pathToSpine.containsKey(key)) pathToSpine.put(key, i);
        }
        // 给目录项挂 spine 索引
        for (TocEntry t : toc) {
            if (t.href == null || t.href.isEmpty()) continue;
            Integer idx = pathToSpine.get(stripFragment(t.href));
            if (idx != null) t.spineIndex = idx;
        }
    }

    private void parseMetadata(Document opf) {
        title = firstText(opf, "title", "未命名");
        author = firstText(opf, "creator", "");
        language = firstText(opf, "language", "zh");
        publisher = firstText(opf, "publisher", "");
        description = stripTags(firstText(opf, "description", ""));
        identifier = firstText(opf, "identifier", "");
    }

    private String firstText(Document doc, String localName, String def) {
        NodeList nl = doc.getElementsByTagNameNS("*", localName);
        if (nl.getLength() == 0) nl = doc.getElementsByTagName(localName);
        if (nl.getLength() == 0) return def;
        String t = nl.item(0).getTextContent();
        return t == null || t.trim().isEmpty() ? def : t.trim();
    }

    private void parseManifest(Document opf) {
        NodeList items = opf.getElementsByTagNameNS("*", "item");
        if (items.getLength() == 0) items = opf.getElementsByTagName("item");

        for (int i = 0; i < items.getLength(); i++) {
            Element el = (Element) items.item(i);
            String id = el.getAttribute("id");
            String href = el.getAttribute("href");
            if (href == null || href.isEmpty()) continue;

            ManifestItem mi = new ManifestItem();
            mi.id = id;
            mi.href = resolve(opfDir, href);
            mi.mediaType = el.getAttribute("media-type");
            mi.properties = el.getAttribute("properties");

            manifest.add(mi);
            byId.put(id, mi);

            if (mi.properties != null && mi.properties.contains("cover-image")) {
                coverPath = mi.href;
            }
        }

        // 封面兜底：<meta name="cover" content="id">
        if (coverPath.isEmpty()) {
            NodeList metas = opf.getElementsByTagNameNS("*", "meta");
            for (int i = 0; i < metas.getLength(); i++) {
                Element m = (Element) metas.item(i);
                if ("cover".equalsIgnoreCase(m.getAttribute("name"))) {
                    ManifestItem mi = byId.get(m.getAttribute("content"));
                    if (mi != null) coverPath = mi.href;
                }
            }
        }
        // 再兜底：文件名含 cover 的图片
        if (coverPath.isEmpty()) {
            for (ManifestItem mi : manifest) {
                if (mi.mediaType != null && mi.mediaType.startsWith("image/")
                        && mi.href.toLowerCase().contains("cover")) {
                    coverPath = mi.href;
                    break;
                }
            }
        }
        // 最后兜底：第一张图片
        if (coverPath.isEmpty()) {
            for (ManifestItem mi : manifest) {
                if (mi.mediaType != null && mi.mediaType.startsWith("image/")) {
                    coverPath = mi.href;
                    break;
                }
            }
        }
    }

    private void parseSpine(Document opf) {
        NodeList refs = opf.getElementsByTagNameNS("*", "itemref");
        if (refs.getLength() == 0) refs = opf.getElementsByTagName("itemref");

        for (int i = 0; i < refs.getLength(); i++) {
            Element el = (Element) refs.item(i);
            String idref = el.getAttribute("idref");
            ManifestItem mi = byId.get(idref);
            if (mi == null) continue;
            if ("no".equals(el.getAttribute("linear"))) continue;

            SpineItem si = new SpineItem();
            si.href = mi.href;
            ZipEntry ze = zip.getEntry(si.href);
            si.byteLength = ze != null ? ze.getSize() : 0;
            si.charCount = (int) Math.max(200, si.byteLength);
            spine.add(si);
        }
        if (spine.isEmpty()) {
            // 退化：把所有 xhtml 塞进 spine
            for (ManifestItem mi : manifest) {
                if (mi.mediaType != null && (mi.mediaType.contains("xhtml") || mi.mediaType.contains("html"))) {
                    SpineItem si = new SpineItem();
                    si.href = mi.href;
                    ZipEntry ze = zip.getEntry(mi.href);
                    si.byteLength = ze != null ? ze.getSize() : 0;
                    si.charCount = (int) Math.max(200, si.byteLength);
                    spine.add(si);
                }
            }
        }
    }

    private void parseToc(Document opf) {
        // EPUB3 nav
        for (ManifestItem mi : manifest) {
            if (mi.properties != null && mi.properties.contains("nav")) {
                try {
                    parseNavDoc(mi.href);
                } catch (Exception ignored) {
                }
                if (!toc.isEmpty()) return;
            }
        }
        // EPUB2 NCX
        for (ManifestItem mi : manifest) {
            if (mi.mediaType != null && mi.mediaType.contains("dtbncx")) {
                try {
                    parseNcx(mi.href);
                } catch (Exception ignored) {
                }
                if (!toc.isEmpty()) return;
            }
        }
    }

    private void parseNavDoc(String navPath) throws Exception {
        String xml = readText(navPath);
        if (xml == null) return;
        Document doc = parseXml(xml.getBytes("UTF-8"));
        String base = dirName(navPath);

        NodeList navs = doc.getElementsByTagNameNS("*", "nav");
        Element tocNav = null;
        for (int i = 0; i < navs.getLength(); i++) {
            Element n = (Element) navs.item(i);
            String type = n.getAttribute("epub:type");
            if (type == null || type.isEmpty()) type = n.getAttribute("type");
            if (type != null && type.contains("toc")) {
                tocNav = n;
                break;
            }
        }
        if (tocNav == null && navs.getLength() > 0) tocNav = (Element) navs.item(0);
        if (tocNav == null) return;

        NodeList ols = tocNav.getElementsByTagName("ol");
        if (ols.getLength() == 0) return;
        walkNavList((Element) ols.item(0), base, 0);
    }

    private void walkNavList(Element ol, String base, int depth) {
        if (depth > 5) return;
        NodeList kids = ol.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!(n instanceof Element)) continue;
            Element li = (Element) n;
            if (!"li".equals(li.getTagName())) continue;

            Element a = null;
            Element subOl = null;
            NodeList liKids = li.getChildNodes();
            for (int j = 0; j < liKids.getLength(); j++) {
                Node c = liKids.item(j);
                if (!(c instanceof Element)) continue;
                String tag = ((Element) c).getTagName();
                if ("a".equals(tag) && a == null) a = (Element) c;
                else if ("ol".equals(tag) && subOl == null) subOl = (Element) c;
            }
            if (a != null) {
                String label = a.getTextContent();
                if (label != null && !label.trim().isEmpty()) {
                    TocEntry t = new TocEntry();
                    t.label = label.trim().replaceAll("\\s+", " ");
                    t.href = resolve(base, a.getAttribute("href"));
                    t.depth = depth;
                    toc.add(t);
                }
            }
            if (subOl != null) walkNavList(subOl, base, depth + 1);
        }
    }

    private void parseNcx(String ncxPath) throws Exception {
        String xml = readText(ncxPath);
        if (xml == null) return;
        Document doc = parseXml(xml.getBytes("UTF-8"));
        String base = dirName(ncxPath);
        NodeList maps = doc.getElementsByTagName("navMap");
        if (maps.getLength() == 0) return;
        walkNavPoints((Element) maps.item(0), base, 0);
    }

    private void walkNavPoints(Element parent, String base, int depth) {
        if (depth > 5) return;
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!(n instanceof Element)) continue;
            Element np = (Element) n;
            if (!"navPoint".equals(np.getTagName())) continue;

            String label = null;
            String src = null;
            NodeList inner = np.getChildNodes();
            for (int j = 0; j < inner.getLength(); j++) {
                Node c = inner.item(j);
                if (!(c instanceof Element)) continue;
                Element ce = (Element) c;
                if ("navLabel".equals(ce.getTagName())) {
                    NodeList texts = ce.getElementsByTagName("text");
                    if (texts.getLength() > 0) label = texts.item(0).getTextContent();
                } else if ("content".equals(ce.getTagName())) {
                    src = ce.getAttribute("src");
                }
            }
            if (label != null && !label.trim().isEmpty()) {
                TocEntry t = new TocEntry();
                t.label = label.trim().replaceAll("\\s+", " ");
                t.href = src != null ? resolve(base, src) : "";
                t.depth = depth;
                toc.add(t);
            }
            walkNavPoints(np, base, depth + 1);
        }
    }

    // ==================== 内容读取 ====================

    private static final Pattern IMG_TAG = Pattern.compile(
            "<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);

    /**
     * 读取章节，转成适合 Html.fromHtml 的片段。
     * 图片 src 被替换为 moyue-img:N 占位，由 ImageGetter 解析。
     */
    public String chapterHtml(int index) {
        if (index < 0 || index >= spine.size()) return "";
        String path = stripFragment(spine.get(index).href);
        String raw = readText(path);
        if (raw == null) return "";

        pendingImages.clear();

        String html = raw;
        // 去掉 head / script / style
        html = html.replaceAll("(?is)<head\\b[^>]*>.*?</head>", " ");
        html = html.replaceAll("(?is)<script\\b[^>]*>.*?</script>", " ");
        html = html.replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ");
        html = html.replaceAll("(?is)<!DOCTYPE[^>]*>", " ");
        html = html.replaceAll("(?is)<\\?xml[^>]*\\?>", " ");

        // 只取 body
        Matcher bodyM = Pattern.compile("(?is)<body\\b[^>]*>(.*?)</body>").matcher(html);
        if (bodyM.find()) html = bodyM.group(1);

        // 处理图片
        String base = dirName(path);
        StringBuffer sb = new StringBuffer();
        Matcher m = IMG_TAG.matcher(html);
        int idx = 0;
        while (m.find()) {
            String src = m.group(1);
            String abs = resolve(base, src);
            pendingImages.add(abs);
            String replacement = "<img src=\"moyue-img:" + idx + "\">";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            idx++;
        }
        m.appendTail(sb);
        html = sb.toString();

        // SVG image 也当图片处理
        html = html.replaceAll("(?is)<svg\\b[^>]*>.*?</svg>", " ");

        // 一些排版标签转成 Html 能识别的
        html = html.replaceAll("(?is)<br\\s*/?>", "<br>");
        html = html.replaceAll("(?is)</p>", "</p>\n\n");
        html = html.replaceAll("(?is)<div\\b[^>]*>", "<p>");
        html = html.replaceAll("(?is)</div>", "</p>");
        html = html.replaceAll("(?is)<hr\\b[^>]*>", "<p>— — —</p>");

        return html;
    }

    /** 取 chapterHtml 中第 idx 张图片 */
    public Bitmap getChapterImage(int idx, int targetWidth) {
        if (idx < 0 || idx >= pendingImages.size()) return null;
        String path = pendingImages.get(idx);
        String key = path + "@" + targetWidth;
        Bitmap cached = imageCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        try {
            byte[] data = readBytes(path);
            if (data == null) return null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetWidth && sample < 8) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (bmp != null) imageCache.put(key, bmp);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    public String readText(String path) {
        byte[] data = readBytes(path);
        if (data == null) return null;

        // 去 BOM
        int off = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            off = 3;
        }
        // 嗅探 XML 声明编码
        String head = new String(data, 0, Math.min(300, data.length), Charset.forName("ISO-8859-1"));
        Matcher m = Pattern.compile("encoding\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(head);
        Charset cs = Charset.forName("UTF-8");
        if (m.find()) {
            String enc = m.group(1).toLowerCase();
            try {
                if (enc.equals("gb2312") || enc.equals("gbk") || enc.equals("gb18030")) cs = Charset.forName("GB18030");
                else cs = Charset.forName(enc);
            } catch (Exception ignored) {
            }
        }
        try {
            return new String(data, off, data.length - off, cs);
        } catch (Exception e) {
            return new String(data, off, data.length - off, Charset.forName("UTF-8"));
        }
    }

    public byte[] readBytes(String path) {
        ZipEntry e = zip.getEntry(path);
        if (e == null) {
            // 有些书路径大小写不一致
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry z = en.nextElement();
                if (z.getName().equalsIgnoreCase(path)) {
                    e = z;
                    break;
                }
            }
        }
        if (e == null) return null;
        try (InputStream is = zip.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(1024, e.getSize()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException ex) {
            return null;
        }
    }

    public Bitmap getCoverBitmap(int maxWidth) {
        if (coverPath == null || coverPath.isEmpty()) return null;
        byte[] data = readBytes(coverPath);
        if (data == null) return null;
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, b);
            int sample = 1;
            if (b.outWidth > 0) {
                while (b.outWidth / (sample * 2) >= maxWidth) sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    public int getSpineCount() {
        return spine.size();
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
        return spine.size();
    }

    @Override
    public String getChapterTitle(int index) {
        return chapterTitle(index);
    }

    @Override
    public String getChapterHtml(int index) {
        return chapterHtml(index);
    }

    @Override
    public List<TocEntry> getToc() {
        return toc;
    }

    @Override
    public long getChapterWeight(int index) {
        if (index < 0 || index >= spine.size()) return 1;
        return Math.max(300, spine.get(index).byteLength);
    }


    /** 第 index 章的标题 */
    public String chapterTitle(int index) {
        for (TocEntry t : toc) {
            if (t.spineIndex == index && t.label != null && !t.label.isEmpty()) return t.label;
        }
        return "第 " + (index + 1) + " 章";
    }

    // ==================== 工具 ====================

    private static Document parseXml(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setExpandEntityReferences(false);
        try {
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignored) {
        }
        try {
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (Exception ignored) {
        }
        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) ->
                new org.xml.sax.InputSource(new ByteArrayInputStream(new byte[0])));
        return b.parse(new ByteArrayInputStream(data));
    }

    public static String stripFragment(String href) {
        if (href == null) return "";
        int i = href.indexOf('#');
        return i < 0 ? href : href.substring(0, i);
    }

    static String dirName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    public static String resolve(String baseDir, String rel) {
        if (rel == null) return "";
        rel = rel.trim();
        if (rel.isEmpty()) return baseDir;
        if (rel.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) return rel; // http:, data: 等

        String frag = "";
        int h = rel.indexOf('#');
        if (h >= 0) {
            frag = rel.substring(h);
            rel = rel.substring(0, h);
        }
        try {
            rel = java.net.URLDecoder.decode(rel, "UTF-8");
        } catch (Exception ignored) {
        }

        String full = rel.startsWith("/") ? rel.substring(1) : (baseDir.isEmpty() ? rel : baseDir + "/" + rel);
        String[] parts = full.split("/");
        ArrayList<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else {
                out.add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(out.get(i));
        }
        return sb + frag;
    }

    private static String stripTags(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").trim();
    }
}
