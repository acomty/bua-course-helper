# -*- coding: utf-8 -*-
"""生成安卓启动图标（不依赖任何在线素材，直接画出来）。

用法： python tools/make_icon.py
输出： app/src/main/res/mipmap-*/ic_launcher.png
"""

import os
from PIL import Image, ImageDraw

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

PRIMARY = (148, 7, 10, 255)      # #94070A
WHITE = (255, 255, 255, 255)
BLOCK_COLORS = [(233, 196, 106, 255), (138, 191, 226, 255), (168, 213, 186, 255)]


def make_icon(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 底：圆角方形
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.22), fill=PRIMARY)

    # 白色课表卡片
    left, top = int(size * 0.17), int(size * 0.20)
    right, bottom = size - left, size - top
    d.rounded_rectangle([left, top, right, bottom], radius=int(size * 0.07), fill=WHITE)

    # 表头（红色横条）
    header_h = int(size * 0.09)
    d.rounded_rectangle([left, top, right, top + header_h * 2], radius=int(size * 0.07), fill=PRIMARY)
    d.rectangle([left, top + header_h, right, top + header_h * 2], fill=PRIMARY)

    # 网格线
    grid_top = top + header_h * 2 + int(size * 0.03)
    rows, cols = 4, 3
    line_w = max(1, int(size * 0.012))
    grid_h = bottom - grid_top - int(size * 0.04)
    grid_w = right - left - int(size * 0.08)
    gx, gy = left + int(size * 0.04), grid_top
    for c in range(1, cols):
        x = gx + grid_w * c // cols
        d.rectangle([x, gy, x + line_w, gy + grid_h], fill=(225, 225, 232, 255))
    for r in range(1, rows):
        y = gy + grid_h * r // rows
        d.rectangle([gx, y, gx + grid_w, y + line_w], fill=(225, 225, 232, 255))

    # 几个彩色课程块
    cell_w, cell_h = grid_w // cols, grid_h // rows
    blocks = [(0, 0, 1, 2), (1, 0, 2, 1), (2, 1, 3, 3), (0, 2, 1, 4)]
    for i, (c0, r0, c1, r1) in enumerate(blocks):
        color = BLOCK_COLORS[i % len(BLOCK_COLORS)]
        pad = max(1, int(size * 0.012))
        box = [gx + cell_w * c0 + pad, gy + cell_h * r0 + pad,
               gx + cell_w * c1 - pad, gy + cell_h * r1 - pad]
        d.rounded_rectangle(box, radius=int(size * 0.025), fill=color)

    return img


def main() -> int:
    for folder, size in DENSITIES.items():
        target_dir = os.path.normpath(os.path.join(BASE, folder))
        os.makedirs(target_dir, exist_ok=True)
        path = os.path.join(target_dir, "ic_launcher.png")
        make_icon(size).save(path)
        print("wrote", path, f"{size}x{size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
