#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 TXT 测试样本：UTF-8 带章节 / GBK 编码 / 无章节标记"""
import os

D = "/Users/kala/WorkBuddy AI/2026-10-24-12-49-46/MoYue/test"

CH1 = """第一章　渡口

　　天还没亮，渡口的木板上结了一层薄霜。
　　老周蹲在船头抽烟，烟头的红光在雾里一明一暗。他听见脚步声，没有回头。
　　「这么早。」他说。
　　「睡不着。」来人在他旁边坐下，把包袱放在脚边。

第二章　河心

　　船到河心的时候，雾更浓了。
　　桨声一下一下，像有人在很远的地方敲钟。
　　「你带了什么？」老周问。
　　「一本书。」
　　「什么书？」
　　「不知道。封皮掉了。」

第三章　对岸

　　对岸的柳树只剩下枝干，一排排站在岸边，像等着谁回来。
　　船靠岸，木板搭上去，发出很轻的一声响。
　　老周没有下船。他把烟掐灭，看着来人的背影慢慢走进雾里。
"""

GBK_TEXT = """第一章 旧信

　　抽屉最底下压着一封信，纸已经发脆了。
　　信封上没有地址，只有一个名字。

第二章 回音

　　他念了一遍那个名字，屋子里没有人应。
　　窗外的风把窗帘吹起来，又放下。
"""

PLAIN = "".join(
    "　　这是第%d段没有章节标记的正文，用来测试按篇幅自动切分的效果。" % i + "\n"
    for i in range(1, 260)
)


def write(name, content, encoding):
    p = os.path.join(D, name)
    with open(p, "w", encoding=encoding, newline="\n") as f:
        f.write(content)
    print("已生成", name, os.path.getsize(p), "bytes", encoding)


def main():
    os.makedirs(D, exist_ok=True)
    write("sample.txt", "作者：老周\n\n" + CH1, "utf-8")
    write("sample_gbk.txt", GBK_TEXT, "gb18030")
    write("sample_plain.txt", PLAIN, "utf-8")


if __name__ == "__main__":
    main()
