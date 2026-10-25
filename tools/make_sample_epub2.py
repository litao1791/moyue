#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 EPUB 2 测试书：NCX 目录 + 内嵌图片 + GBK 编码章节"""
import os
import struct
import zipfile
import zlib

OUT = "/Users/kala/WorkBuddy AI/2026-10-24-12-49-46/MoYue/test/sample2.epub"


def tiny_png(w=8, h=8, rgb=(180, 120, 60)):
    """生成一张纯色 PNG"""
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw += bytes(rgb)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) +
            chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


CH1 = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>第一章</title><link rel="stylesheet" href="../styles/main.css" type="text/css"/></head>
<body>
<h1>第一章 有图的一章</h1>
<p>这一段后面会插一张图。</p>
<div><img src="../images/fig1.png" alt="示意图"/></div>
<p>图看完了，继续读下去。</p>
</body>
</html>
"""

CH2_TEXT = """<?xml version="1.0" encoding="gb2312"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>第二章</title></head>
<body>
<h1>第二章 老编码</h1>
<p>这一章故意用 GBK 编码保存，检验程序能不能正确识别中文。</p>
<p>如果乱码了，说明编码嗅探没生效。</p>
</body>
</html>
"""

OPF = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>旧编码之书</dc:title>
    <dc:creator opf:role="aut">某作者</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:identifier id="BookId">urn:uuid:moyue-test-0002</dc:identifier>
    <meta name="cover" content="cover-img"/>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="css" href="styles/main.css" media-type="text/css"/>
    <item id="cover-img" href="images/cover.png" media-type="image/png"/>
    <item id="fig1" href="images/fig1.png" media-type="image/png"/>
    <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="c1"/>
    <itemref idref="c2"/>
  </spine>
</package>
"""

NCX = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="urn:uuid:moyue-test-0002"/>
    <meta name="dtb:depth" content="2"/>
  </head>
  <docTitle><text>旧编码之书</text></docTitle>
  <navMap>
    <navPoint id="np1" playOrder="1">
      <navLabel><text>第一章 有图的一章</text></navLabel>
      <content src="text/ch1.xhtml"/>
      <navPoint id="np1a" playOrder="2">
        <navLabel><text>第一节 插图</text></navLabel>
        <content src="text/ch1.xhtml#fig"/>
      </navPoint>
    </navPoint>
    <navPoint id="np2" playOrder="3">
      <navLabel><text>第二章 老编码</text></navLabel>
      <content src="text/ch2.xhtml"/>
    </navPoint>
  </navMap>
</ncx>
"""

CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

CSS = "body { font-family: serif; } p { text-indent: 2em; }"


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with zipfile.ZipFile(OUT, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip",
                   compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", CONTAINER, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/content.opf", OPF, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/toc.ncx", NCX, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/styles/main.css", CSS, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/images/cover.png", tiny_png(60, 90, (120, 80, 45)),
                   compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/images/fig1.png", tiny_png(40, 30, (200, 150, 90)),
                   compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/text/ch1.xhtml", CH1, compress_type=zipfile.ZIP_DEFLATED)
        # 关键：GBK 编码写入
        z.writestr("OEBPS/text/ch2.xhtml", CH2_TEXT.encode("gb18030"),
                   compress_type=zipfile.ZIP_DEFLATED)

    print("已生成", OUT, os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
