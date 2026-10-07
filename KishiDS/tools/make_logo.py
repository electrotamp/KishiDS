#!/usr/bin/env python3
"""Cuts the KishiDS logo out of its flat dark backdrop and writes Assets/logo.png and Assets/kishi.ico.

The source art is the mark on a solid grey background with anti-aliased edges.  Plain colour-keying leaves a grey halo, so
the true foreground colour of each edge pixel is estimated from the nearby solid interior and the alpha is recovered from it
(c = alpha * fg + (1 - alpha) * bg).

Usage:  python tools/make_logo.py <source image> [Assets dir]      (needs Pillow + NumPy)
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

src = Path(sys.argv[1])
out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).resolve().parent.parent / "Assets"
out.mkdir(parents=True, exist_ok=True)

im = Image.open(src).convert("RGB")
a = np.asarray(im).astype(np.float64)
h, w, _ = a.shape
bg = np.median(np.concatenate([a[:6, :6].reshape(-1, 3), a[:6, -6:].reshape(-1, 3), a[-6:, :6].reshape(-1, 3), a[-6:, -6:].reshape(-1, 3)]), axis=0)
print("size", (w, h), "background", bg.round(1))

diff = np.sqrt(((a - bg) ** 2).sum(axis=2))
core = (diff > 45).astype(np.float64)


def gblur(plane, r):
    """Separable gaussian blur in NumPy (Pillow's own filter can't take float images)."""
    k = int(r * 3)
    x = np.arange(-k, k + 1)
    ker = np.exp(-(x ** 2) / (2.0 * r * r))
    ker /= ker.sum()
    out = plane.astype(np.float64)
    for axis in (0, 1):
        pad = [(0, 0), (0, 0)]
        pad[axis] = (k, k)
        padded = np.pad(out, pad, mode="edge")
        acc = np.zeros_like(out)
        for i, wgt in enumerate(ker):
            sl = [slice(None), slice(None)]
            sl[axis] = slice(i, i + out.shape[axis])
            acc += wgt * padded[tuple(sl)]
        out = acc
    return out


wsum = gblur(core, 5)
fg = np.zeros_like(a)
for i in range(3):
    fg[..., i] = gblur(a[..., i] * core, 5) / np.maximum(wsum, 1e-6)
# where no interior is nearby, the pixel is its own foreground estimate
fg = np.where((wsum > 1e-3)[..., None], fg, a)

d = fg - bg
den = (d ** 2).sum(axis=2)
alpha = np.where(den > 1, ((a - bg) * d).sum(axis=2) / np.maximum(den, 1), 0.0)
alpha = np.clip(alpha, 0, 1)
alpha[diff < 3] = 0
alpha[core > 0] = 1.0

safe = np.maximum(alpha, 1e-3)[..., None]
col = np.clip(bg + (a - bg) / safe, 0, 255)
col = np.where((alpha > 0.995)[..., None], a, col)
rgba = np.dstack([col, alpha * 255]).round().astype(np.uint8)
img = Image.fromarray(rgba, "RGBA")

# Trim to the mark, then centre it on a square canvas with a little breathing room.
box = img.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()
print("mark bbox", box)
mark = img.crop(box)
side = int(max(mark.size) * 1.14)
sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
sq.paste(mark, ((side - mark.width) // 2, (side - mark.height) // 2), mark)
logo = sq.resize((1024, 1024), Image.LANCZOS)
logo.save(out / "logo.png")

sizes = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256]
frames = [logo.resize((s, s), Image.LANCZOS) for s in sizes]
frames[-1].save(out / "kishi.ico", format="ICO", sizes=[(s, s) for s in sizes], append_images=frames[:-1])
print("wrote", out / "logo.png", "and", out / "kishi.ico")

# Quick visual check on dark and light backdrops.
for name, colr in (("dark", (14, 17, 26, 255)), ("light", (236, 241, 252, 255))):
    board = Image.new("RGBA", (1100, 300), colr)
    x = 20
    for s in (256, 128, 64, 32, 16):
        f = logo.resize((s, s), Image.LANCZOS)
        board.alpha_composite(f, (x, 20))
        x += s + 30
    board.convert("RGB").save(out.parent / f"logo-check-{name}.png")
