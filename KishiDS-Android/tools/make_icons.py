#!/usr/bin/env python3
"""Builds the Android launcher icons and the in-app logo from the desktop app's logo (../KishiDS/Assets/logo.png).

Writes res/drawable-nodpi/logo.png, res/drawable-nodpi/ic_launcher_foreground.png (adaptive icon layer) and the legacy
mipmap-*/ic_launcher.png set.  Needs Pillow.   Run:  python tools/make_icons.py   (from the KishiDS-Android folder)
"""
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent.parent
SRC = HERE.parent / "KishiDS" / "Assets" / "logo.png"
RES = HERE / "app" / "src" / "main" / "res"
BG = (0x0B, 0x0E, 0x17, 255)

logo = Image.open(SRC).convert("RGBA")
bbox = logo.getbbox()
logo = logo.crop(bbox)          # trim the empty margin so the mark can be sized exactly


def fit(img, box):
    s = box / max(img.size)
    return img.resize((max(1, round(img.width * s)), max(1, round(img.height * s))), Image.LANCZOS)


def centred(canvas_px, mark_px, bg=None):
    c = Image.new("RGBA", (canvas_px, canvas_px), bg or (0, 0, 0, 0))
    m = fit(logo, mark_px)
    c.alpha_composite(m, ((canvas_px - m.width) // 2, (canvas_px - m.height) // 2))
    return c


(RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
centred(256, 232).save(RES / "drawable-nodpi" / "logo.png", optimize=True)
centred(432, 220).save(RES / "drawable-nodpi" / "ic_launcher_foreground.png", optimize=True)   # adaptive: mark inside the central 66%

for name, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    big = 4
    s = px * big
    base = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(base).rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.22), fill=BG)
    base.alpha_composite(fit(logo, int(s * 0.68)), ((s - fit(logo, int(s * 0.68)).width) // 2, (s - fit(logo, int(s * 0.68)).height) // 2))
    out = RES / f"mipmap-{name}"
    out.mkdir(parents=True, exist_ok=True)
    base.resize((px, px), Image.LANCZOS).save(out / "ic_launcher.png", optimize=True)
print("icons written to", RES)
