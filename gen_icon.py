#!/usr/bin/env python3
"""生成 app 图标（纯 python 写 PNG，不依赖 Pillow）。

画的是拾音的波形：深色圆角底 + 五根琥珀色竖条。
输出到 android/res/mipmap-*/ic_launcher.png。
"""
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.abspath(__file__))
BG = (0x0B, 0x0E, 0x14, 255)
AMBER = (0xF5, 0xA5, 0x24, 255)

# 五根竖条的相对高度（0~1），跟 icon.svg 一致
BARS = [(0.42, 0.42), (0.66, 0.66), (0.78, 0.78), (0.55, 0.55), (0.32, 0.32)]


def render(size):
    px = [[BG for _ in range(size)] for _ in range(size)]
    r = int(size * 0.22)          # 圆角半径
    # 圆角遮罩
    for y in range(size):
        for x in range(size):
            dx = dy = 0
            if x < r: dx = r - x
            elif x >= size - r: dx = x - (size - r - 1)
            if y < r: dy = r - y
            elif y >= size - r: dy = y - (size - r - 1)
            if dx * dx + dy * dy > r * r:
                px[y][x] = (0, 0, 0, 0)

    # 竖条
    bw = max(2, int(size * 0.072))     # 条宽
    gap = int(size * 0.043)
    total = len(BARS) * bw + (len(BARS) - 1) * gap
    x0 = (size - total) // 2
    for i, (h, _) in enumerate(BARS):
        bh = int(size * h * 0.62)
        bx = x0 + i * (bw + gap)
        by = (size - bh) // 2
        rr = bw // 2
        for y in range(by, by + bh):
            for x in range(bx, bx + bw):
                # 端点圆角
                if y < by + rr and (x - (bx + rr)) ** 2 + (y - (by + rr)) ** 2 > rr * rr:
                    continue
                if y > by + bh - rr - 1 and (x - (bx + rr)) ** 2 + (y - (by + bh - rr - 1)) ** 2 > rr * rr:
                    continue
                px[y][x] = AMBER
    return px


def write_png(path, px):
    h = len(px)
    w = len(px[0])
    raw = bytearray()
    for row in px:
        raw.append(0)
        for p in row:
            raw += bytes(p)
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(png)
    print(f"{path}  {w}x{h}  {len(png)}B")


for folder, size in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                     ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
    write_png(os.path.join(ROOT, "res", folder, "ic_launcher.png"), render(size))
