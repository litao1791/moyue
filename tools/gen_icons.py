#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成墨阅 App 图标（纯 Python 光栅化 + PNG 编码，无第三方依赖）"""
import os, struct, zlib

OUT = "/Users/kala/WorkBuddy AI/2026-10-24-12-49-46/MoYue/app/src/main/res"

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def write_png(path, w, h, rgba):
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]
    comp = zlib.compress(bytes(raw), 9)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) +
           chunk(b"IDAT", comp) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def in_rrect(x, y, x0, y0, x1, y1, r):
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def lerp(a, b, t):
    return a + (b - a) * t


def mix(c1, c2, t):
    return tuple(round(lerp(c1[i], c2[i], t)) for i in range(3))


def render(size, round_icon=False):
    SS = 4                      # 超采样倍率
    S = size * SS
    f = float(S)

    BG_TOP = (0x96, 0x6E, 0x46)
    BG_BOT = (0x4A, 0x31, 0x1E)
    CREAM = (0xF7, 0xEF, 0xE2)
    BROWN = (0x8A, 0x5C, 0x30)
    LINE = (0xC0, 0x94, 0x63)

    # 书本几何（相对 0..1）
    bx0, by0, bx1, by1 = 0.255, 0.245, 0.745, 0.755
    br = 0.030
    spine_x1 = 0.335

    buf = bytearray(S * S * 4)
    inv = 1.0 / SS

    for py in range(S):
        v = (py + 0.5) / f
        for px in range(S):
            u = (px + 0.5) / f

            # 背景形状
            if round_icon:
                cx, cy, r = 0.5, 0.5, 0.5
                inside_bg = (u - cx) ** 2 + (v - cy) ** 2 <= r * r
            else:
                inside_bg = in_rrect(u, v, 0.0, 0.0, 1.0, 1.0, 0.225)

            if not inside_bg:
                continue

            col = mix(BG_TOP, BG_BOT, v * 0.95)

            # 书本主体
            if in_rrect(u, v, bx0, by0, bx1, by1, br):
                col = CREAM

                # 书脊
                if u < spine_x1:
                    col = BROWN
                    # 书脊高光
                    if abs(u - spine_x1) < 0.006:
                        col = (0x5E, 0x3C, 0x1F)
                else:
                    # 文字线条
                    for i, ty in enumerate((0.395, 0.475, 0.555)):
                        tx1 = 0.665 if i < 2 else 0.575
                        if in_rrect(u, v, 0.375, ty, tx1, ty + 0.026, 0.012):
                            col = LINE
                            break

            idx = (py * S + px) * 4
            buf[idx] = col[0]
            buf[idx + 1] = col[1]
            buf[idx + 2] = col[2]
            buf[idx + 3] = 255

    # 下采样（盒式滤波，得到抗锯齿边缘与透明通道）
    out = bytearray(size * size * 4)
    for y in range(size):
        for x in range(size):
            r = g = b = a = 0
            for dy in range(SS):
                base = ((y * SS + dy) * S + x * SS) * 4
                for dx in range(SS):
                    i = base + dx * 4
                    r += buf[i]
                    g += buf[i + 1]
                    b += buf[i + 2]
                    a += buf[i + 3]
            n = SS * SS
            o = (y * size + x) * 4
            if a == 0:
                out[o] = out[o + 1] = out[o + 2] = out[o + 3] = 0
            else:
                cnt = a // 255
                out[o] = r // max(1, cnt)
                out[o + 1] = g // max(1, cnt)
                out[o + 2] = b // max(1, cnt)
                out[o + 3] = a // n
    return bytes(out)


def main():
    for dens, px in DENSITIES.items():
        d = os.path.join(OUT, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        write_png(os.path.join(d, "ic_launcher.png"), px, px, render(px, False))
        write_png(os.path.join(d, "ic_launcher_round.png"), px, px, render(px, True))
        print("生成", dens, px)


if __name__ == "__main__":
    main()
