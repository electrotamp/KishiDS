#!/usr/bin/env python3
"""Converts the two Kishi v1 SVG halves (../SVG) into a compact text asset (app/src/main/assets/kishi_art.txt) that the Android app draws
with android.graphics.Canvas.  Reuses the SVG reading of KishiDS/tools/svg2xaml.py (CSS classes, userSpaceOnUse gradients with
translate+scale transforms), so the phone and the desktop app show exactly the same artwork.

Asset format, one record per line:
    group <name>                          start of a named list of shapes
    shape <fill> | <stroke> | <geometry>
    geo <name> <path data>                a named outline (used for the shoulder-press highlight)
  fill     -  |  #RRGGBB  |  L x1 y1 x2 y2 off:#RRGGBB ...  |  R cx cy rx ry off:#RRGGBB ...
  stroke   -  |  #RRGGBB width round|miter
  geometry O cx cy r   |   absolute path data (M, L, C, Z)

Run:  python tools/make_art.py        (from the KishiDS-Android folder)
"""
import importlib.util
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
ROOT = HERE.parent
spec = importlib.util.spec_from_file_location("svg2xaml", ROOT / "KishiDS" / "tools" / "svg2xaml.py")
svg = importlib.util.module_from_spec(spec)
spec.loader.exec_module(svg)

OUT = HERE / "app" / "src" / "main" / "assets" / "kishi_art.txt"


def fmt(v):
    return svg.fmt(v)


def stops(g):
    return " ".join(f"{fmt(o)}:{c}" for o, c in g.stops_list)


def paint(ref, grads):
    gid = re.match(r"url\(#([^)]+)\)", ref).group(1)
    g = grads.by_id[gid]
    tag = g.tag.split("}")[1]
    sx, sy, tx, ty = svg.parse_transform(grads.attr(g, "gradientTransform"))
    assert (grads.attr(g, "gradientUnits") or "objectBoundingBox") == "userSpaceOnUse"
    st = " ".join(f"{fmt(o)}:{c}" for o, c in grads.stops(g))
    if tag == "linearGradient":
        x1, y1, x2, y2 = (float(grads.attr(g, k)) for k in ("x1", "y1", "x2", "y2"))
        return f"L {fmt(x1*sx+tx)} {fmt(y1*sy+ty)} {fmt(x2*sx+tx)} {fmt(y2*sy+ty)} {st}"
    cx, cy = float(grads.attr(g, "cx")), float(grads.attr(g, "cy"))
    r = float(grads.attr(g, "r"))
    return f"R {fmt(cx*sx+tx)} {fmt(cy*sy+ty)} {fmt(r*abs(sx))} {fmt(r*abs(sy))} {st}"


def shapes(path):
    root = ET.parse(path).getroot()
    rules = svg.parse_css("".join(s.text or "" for s in root.iter("{%s}style" % svg.NS["s"])))
    grads = svg.Gradients(root)
    out = []
    for e in root.iter():
        tag = e.tag.split("}")[1]
        if tag not in ("path", "circle") or e.get("class") is None:
            continue
        st = svg.style_of(e, rules)
        geo = svg.absolute_path(e.get("d")) if tag == "path" else f"O {fmt(float(e.get('cx')))} {fmt(float(e.get('cy')))} {fmt(float(e.get('r')))}"
        fill = st.get("fill", "black")
        f = "-" if fill == "none" else paint(fill, grads) if fill.startswith("url(") else fill
        s = "-"
        if st.get("stroke"):
            w = st.get("stroke-width", "1").replace("px", "")
            s = f"{st['stroke']} {w} {'round' if st.get('stroke-linejoin') == 'round' else 'miter'}"
        out.append((f, s, geo))
    return out


def main():
    left = shapes(ROOT / "SVG" / "Kishi v1 Left.svg")
    right = shapes(ROOT / "SVG" / "Kishi v1 Right.svg")
    assert len(left) == 24 and len(right) == 30, (len(left), len(right))
    lines = ["# Output of tools/make_art.py from SVG/Kishi v1 Left.svg and Right.svg. Do not edit by hand.",
             "# Both halves are 630 x 890; Left's open (phone) side is x=630, Right's is x=0."]

    def group(name, items):
        lines.append(f"group {name}")
        for f, s, g in items:
            lines.append(f"shape {f} | {s} | {g}")

    cap_l, cap_r, led_r = 8, 9, 27
    group("KishiLeftStatic", [s for i, s in enumerate(left) if i != cap_l])
    group("KishiLeftCap", [left[cap_l]])
    group("KishiRightStatic", [s for i, s in enumerate(right) if i not in (cap_r, led_r)])
    group("KishiRightCap", [right[cap_r]])
    lines.append(f"geo KishiLeftShoulderGeo {left[0][2]}")
    lines.append(f"geo KishiRightShoulderGeo {right[0][2]}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    sys.exit(main())
