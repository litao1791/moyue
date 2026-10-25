#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成纸张纹理贴图（无缝平铺，白色 RGB + alpha 通道）。

为什么要程序生成而不是找图：
  - 版权干净
  - 无缝可控（全部用整数频率的周期函数 / FFT 滤波，边界天然接得上）
  - 白色 RGB + alpha，代码里用 PorterDuff 着色，一张图同时适配纸色和夜间模式

强度原则：这是背景，不能抢文字。叠加后底色变化控制在几个百分点以内，
峰值 alpha 留在 0.3–0.5，全局再由代码乘一个强度系数。

输出：app/src/main/res/drawable-nodpi/tex_*.png
"""

import os

import numpy as np
from PIL import Image

W = H = 512
OUT = "app/src/main/res/drawable-nodpi"

yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)


def periodic_noise(seed, sigma):
    """FFT 低通噪声：天然周期，平铺时边界完全接得上"""
    rng = np.random.default_rng(seed)
    n = rng.random((H, W)).astype(np.float32)
    F = np.fft.fft2(n)
    fy = np.fft.fftfreq(H).reshape(-1, 1)
    fx = np.fft.fftfreq(W).reshape(1, -1)
    r = np.sqrt(fx ** 2 + fy ** 2)
    F *= np.exp(-(r ** 2) / (2 * sigma ** 2))
    out = np.real(np.fft.ifft2(F)).astype(np.float32)
    out -= out.min()
    if out.max() > 1e-6:
        out /= out.max()
    return out


def soft_line(dist, width):
    return np.exp(-(dist ** 2) / (2 * width ** 2))


def save(alpha, name):
    a = np.clip(alpha, 0, 1)
    rgba = np.zeros((H, W, 4), dtype=np.uint8)
    rgba[..., 0:3] = 255
    rgba[..., 3] = (a * 255).astype(np.uint8)
    path = f"{OUT}/{name}.png"
    Image.fromarray(rgba, "RGBA").save(path, optimize=True, compress_level=9)
    print(f"  {name}.png  {os.path.getsize(path) / 1024:5.1f} KB  "
          f"均值={a.mean():.3f} 峰值={a.max():.3f}")


# ---------- 木纹 ----------

def wood():
    # 横向扭曲：整数频率 → x 方向必然无缝
    warp = (4.5 * np.sin(2 * np.pi * 2 * xx / W)
            + 2.2 * np.sin(2 * np.pi * 3 * xx / W + 1.7)
            + 1.0 * np.sin(2 * np.pi * 7 * xx / W + 0.4))

    # 宽色带：低频噪声做明暗不均，木头不是均匀的
    tone = (periodic_noise(11, 0.008) - 0.5) * 0.30

    # 主年轮：间距 128（512/128=4，y 方向接得上），线宽随位置变化
    phase = yy + warp + 45 * (periodic_noise(13, 0.012) - 0.5)
    d = phase % 128
    dist = np.minimum(d, 128 - d)
    width = 1.4 + 1.0 * (0.5 + 0.5 * np.sin(2 * np.pi * 3 * yy / H))
    main = soft_line(dist, width) * 0.34

    # 次级细纹：间距 32
    d2 = (phase * 1.7 + 9) % 32
    sub = soft_line(np.minimum(d2, 32 - d2), 0.7) * 0.12

    # 木射线：断续的细丝，沿着纹理方向
    rays = (0.5 + 0.5 * np.sin(2 * np.pi * 37 * xx / W
                                + 3.0 * np.sin(2 * np.pi * 3 * yy / H))) ** 12
    rays *= 0.20

    save(main + sub + rays + tone, "tex_wood")


# ---------- 亚麻布纹 ----------

def linen():
    # 周期必须整除 W，否则平铺接缝处会出现半条线
    period = 8.0
    gx = soft_line(np.minimum(xx % period, period - xx % period), 1.0)
    gy = soft_line(np.minimum(yy % period, period - yy % period), 1.0)
    # 经纬交替浮沉
    weave = 0.5 + 0.5 * np.sin(2 * np.pi * xx / (period * 2))
    a = gx * (0.15 + 0.11 * weave) + gy * (0.15 + 0.11 * (1 - weave))
    a += gx * gy * 0.06
    a += (periodic_noise(23, 0.030) - 0.5) * 0.12
    save(a, "tex_linen")


# ---------- 皮革：细胞状粒面 ----------

def voronoi_edges(seed, points, width):
    """
    细胞边界场：每个像素找最近和次近的种子点，
    两者距离差接近 0 的地方就是细胞壁——皮革的粒面就是这种网状裂纹。
    距离按周期取模，所以平铺无缝。
    """
    rng = np.random.default_rng(seed)
    pts = rng.random((points, 2)).astype(np.float32) * W
    edge = np.zeros((H, W), dtype=np.float32)
    chunk = 64
    for y0 in range(0, H, chunk):
        yb = yy[y0:y0 + chunk][..., None]
        xb = xx[y0:y0 + chunk][..., None]
        dx = (xb - pts[None, None, :, 0] + W / 2) % W - W / 2
        dy = (yb - pts[None, None, :, 1] + H / 2) % H - H / 2
        d = np.sqrt(dx * dx + dy * dy)
        d.sort(axis=2)
        gap = d[..., 1] - d[..., 0]
        edge[y0:y0 + chunk] = np.exp(-(gap ** 2) / (2 * width ** 2))
    return edge


def leather():
    # 大粒 + 小粒两层细胞壁，像皮革的粗细纹
    big = voronoi_edges(71, 64, 1.3)
    small = voronoi_edges(73, 240, 0.8)
    a = big * 0.30 + small * 0.16
    # 粒面本身有微微凸起的明暗
    tone = (periodic_noise(77, 0.012) - 0.5) * 0.16
    save(a + tone, "tex_leather")


# ---------- 大理石：几道粗纹 + 细支纹 ----------

def marble():
    # 扭曲场：让纹路弯起来，但仍是整数频率，接缝处不会断
    bend = 22 * np.sin(2 * np.pi * 2 * yy / H) + 10 * np.sin(2 * np.pi * 3 * yy / H + 1.1)
    shift = 70 * (periodic_noise(43, 0.006) - 0.5)

    phase = xx + bend + shift
    d = phase % 256
    dist = np.minimum(d, 256 - d)
    width = 3.0 + 2.2 * (0.5 + 0.5 * np.sin(2 * np.pi * 2 * yy / H))
    main = soft_line(dist, width) * 0.38

    # 细支纹：周期更短，只保留一部分（用低频噪声当遮罩，免得满屏都是线）
    mask = np.clip((periodic_noise(47, 0.012) - 0.45) * 2.2, 0, 1)
    d2 = (phase * 2.3 + 31) % 128
    thin = soft_line(np.minimum(d2, 128 - d2), 0.6) * 0.14 * mask

    tone = (periodic_noise(53, 0.009) - 0.5) * 0.22
    save(main + thin + tone, "tex_marble")


# ---------- 旧纸：大块泛黄 + 纤维 + 霉点 ----------

def antique():
    cloud = (periodic_noise(61, 0.005) - 0.5) * 0.34

    # 纤维丝：很细的短线
    fib = (0.5 + 0.5 * np.sin(2 * np.pi * 113 * xx / W
                                + 5 * np.sin(2 * np.pi * 2 * yy / H))) ** 18
    fib2 = (0.5 + 0.5 * np.sin(2 * np.pi * 71 * yy / H
                                 + 4 * np.sin(2 * np.pi * 2 * xx / W))) ** 18
    a = cloud + (fib + fib2) * 0.10

    # 霉点：低频噪声的高峰处压暗，边缘柔和
    spots = periodic_noise(67, 0.030)
    a += np.clip((spots - 0.80) * 5.0, 0, 1) * 0.50
    save(a, "tex_antique")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    print("生成纸张纹理：")
    wood()
    linen()
    leather()
    marble()
    antique()
