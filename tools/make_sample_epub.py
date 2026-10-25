#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成一本符合 EPUB 3 规范的测试书，用于验证解析器"""
import os
import zipfile

OUT = "/Users/kala/WorkBuddy AI/2026-10-24-12-49-46/MoYue/test/sample.epub"

CHAPTERS = [
    ("第一章 山中来客", [
        "清晨的雾还没散，山路上一片寂静。",
        "他背着一只旧帆布包，走了整整一夜。脚下的石子被露水浸透，每一步都发出细碎的声响。",
        "远处传来钟声，一共九下。他停下脚步，抬头望向山谷深处，那里有一片灰瓦的屋顶，被雾气吞没了大半。",
        "「就是这里了。」他对自己说。",
    ]),
    ("第二章 旧书铺", [
        "书铺的门是深褐色的，木纹里有几十年的灰。",
        "推门进去，铜铃响了一声。屋里比外面更暗，书架从地板一直顶到房梁，空气里有一股纸页受潮之后特有的甜味。",
        "柜台后面坐着一个人，正在灯下翻一本线装书。听见铃声，他抬起头，眼睛很亮。",
        "「找书？」他问。",
        "「找人。」来客说。",
        "那人合上书，慢慢站起来。灯光在他脸上投下一道很深的阴影。「那你来对地方了。」",
    ]),
    ("第三章 灯下", [
        "夜里的灯很暗，只够照亮桌面那一小块。",
        "两个人对坐着，中间摊着一张泛黄的地图。地图上有一条用红笔描出来的线，从山脚一直延伸到谷底，末端画了一个圈。",
        "「这条线是什么意思？」",
        "「三十年前有人走过。」老人用手指点了点那个圈，「走到这里，就没再出来。」",
        "屋子里静了很久。窗外的风把树影摇得哗哗响。",
        "「所以你现在还要去？」",
        "「要。」",
    ]),
]


def chapter_html(title, paras, idx):
    body = "\n".join("    <p>%s</p>" % p for p in paras)
    return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN" lang="zh-CN">
<head>
  <meta charset="utf-8"/>
  <title>%s</title>
  <link rel="stylesheet" type="text/css" href="../styles/main.css"/>
</head>
<body>
  <section>
    <h2>%s</h2>
%s
  </section>
</body>
</html>
""" % (title, title, body)


CSS = """body { font-family: serif; line-height: 1.8; }
h2 { font-size: 1.4em; margin: 1.2em 0 1em 0; }
p { text-indent: 2em; margin: 0.8em 0; }
"""


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)

    container = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    manifest_items = []
    spine_items = []
    for i, (title, paras) in enumerate(CHAPTERS, start=1):
        manifest_items.append(
            '    <item id="ch%d" href="text/ch%d.xhtml" media-type="application/xhtml+xml"/>' % (i, i))
        spine_items.append('    <itemref idref="ch%d"/>' % i)

    opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:moyue-test-0001</dc:identifier>
    <dc:title>山中书</dc:title>
    <dc:creator>佚名</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:publisher>墨阅测试</dc:publisher>
    <dc:description>一本用于测试的短篇。</dc:description>
    <meta property="dcterms:modified">2026-10-25T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="css" href="styles/main.css" media-type="text/css"/>
%s
  </manifest>
  <spine>
%s
  </spine>
</package>
""" % ("\n".join(manifest_items), "\n".join(spine_items))

    nav_lis = "\n".join(
        '      <li><a href="text/ch%d.xhtml">%s</a></li>' % (i, t)
        for i, (t, _) in enumerate(CHAPTERS, start=1))

    nav = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="zh-CN">
<head><meta charset="utf-8"/><title>目录</title></head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>目录</h1>
    <ol>
%s
    </ol>
  </nav>
</body>
</html>
""" % nav_lis

    with zipfile.ZipFile(OUT, "w") as z:
        # mimetype 必须是第一个且不压缩
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip",
                   compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", container, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/content.opf", opf, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/nav.xhtml", nav, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/styles/main.css", CSS, compress_type=zipfile.ZIP_DEFLATED)
        for i, (title, paras) in enumerate(CHAPTERS, start=1):
            z.writestr("OEBPS/text/ch%d.xhtml" % i, chapter_html(title, paras, i),
                       compress_type=zipfile.ZIP_DEFLATED)

    print("已生成", OUT, os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
