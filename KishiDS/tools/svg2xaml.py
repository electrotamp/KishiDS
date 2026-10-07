#!/usr/bin/env python3
"""Converts the two Kishi v1 SVG halves (../SVG) into a WPF ResourceDictionary (Theme/KishiArt.xaml).

The SVGs are illustrator exports: CSS classes for fill/stroke, linearGradient/radialGradient in userSpaceOnUse with
translate+scale gradientTransforms, a 630x890 clipPath, and paths/circles only.  This script handles exactly that subset:
every shape becomes a GeometryDrawing, gradients are pre-transformed into absolute WPF brushes, and shapes are bundled into
named DrawingGroups so the app can draw the static art and animate the parts that move (stick caps, LED).

Run:  python tools/svg2xaml.py        (from the KishiDS folder)
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
SVG_DIR = HERE.parent / "SVG"
OUT = HERE / "Theme" / "KishiArt.xaml"
NS = {"s": "http://www.w3.org/2000/svg", "x": "http://www.w3.org/1999/xlink"}
XLINK_HREF = "{http://www.w3.org/1999/xlink}href"


def fmt(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# ---------------------------------------------------------------------------------------------------------------- CSS
def parse_css(style_text):
    rules = []
    for sel, body in re.findall(r"([^{}]+)\{([^}]*)\}", style_text):
        decls = {}
        for d in body.split(";"):
            if ":" in d:
                k, v = d.split(":", 1)
                decls[k.strip()] = v.strip()
        for one in sel.split(","):
            one = one.strip()
            if one.startswith("."):
                rules.append((one[1:], decls))
    return rules


def style_of(elem, rules):
    out = {}
    for cls in (elem.get("class") or "").split():
        for name, decls in rules:
            if name == cls:
                out.update(decls)
    return out


# ------------------------------------------------------------------------------------------------------------ gradients
def parse_transform(t):
    """Returns (sx, sy, tx, ty) for 'translate(a b) scale(c d)' style lists (translate/scale only)."""
    sx = sy = 1.0
    tx = ty = 0.0
    for name, args in re.findall(r"(\w+)\(([^)]*)\)", t or ""):
        nums = [float(n) for n in re.split(r"[ ,]+", args.strip()) if n]
        if name == "translate":
            tx += sx * nums[0]
            ty += sy * (nums[1] if len(nums) > 1 else 0)
        elif name == "scale":
            sx *= nums[0]
            sy *= nums[1] if len(nums) > 1 else nums[0]
        else:
            raise ValueError("unsupported transform " + name)
    return sx, sy, tx, ty


class Gradients:
    def __init__(self, root):
        self.by_id = {}
        for g in root.iter():
            tag = g.tag.split("}")[1]
            if tag in ("linearGradient", "radialGradient") and g.get("id"):
                self.by_id[g.get("id")] = g

    def attr(self, g, name):
        while g is not None:
            if g.get(name) is not None:
                return g.get(name)
            href = g.get(XLINK_HREF)
            g = self.by_id.get(href[1:]) if href else None
        return None

    def stops(self, g):
        while g is not None:
            st = [c for c in g if c.tag.endswith("stop")]
            if st:
                return [(float(s.get("offset")), s.get("stop-color")) for s in st]
            href = g.get(XLINK_HREF)
            g = self.by_id.get(href[1:]) if href else None
        return []

    def brush(self, ref):
        gid = re.match(r"url\(#([^)]+)\)", ref).group(1)
        g = self.by_id[gid]
        tag = g.tag.split("}")[1]
        sx, sy, tx, ty = parse_transform(self.attr(g, "gradientTransform"))
        assert (self.attr(g, "gradientUnits") or "objectBoundingBox") == "userSpaceOnUse"
        stops = "".join(f'<GradientStop Color="{c}" Offset="{fmt(o)}"/>' for o, c in self.stops(g))
        if tag == "linearGradient":
            x1, y1, x2, y2 = (float(self.attr(g, k)) for k in ("x1", "y1", "x2", "y2"))
            return (f'<LinearGradientBrush MappingMode="Absolute" StartPoint="{fmt(x1*sx+tx)},{fmt(y1*sy+ty)}" '
                    f'EndPoint="{fmt(x2*sx+tx)},{fmt(y2*sy+ty)}">{stops}</LinearGradientBrush>')
        cx, cy = float(self.attr(g, "cx")), float(self.attr(g, "cy"))
        fx, fy = float(self.attr(g, "fx") or cx), float(self.attr(g, "fy") or cy)
        r = float(self.attr(g, "r"))
        return (f'<RadialGradientBrush MappingMode="Absolute" Center="{fmt(cx*sx+tx)},{fmt(cy*sy+ty)}" '
                f'GradientOrigin="{fmt(fx*sx+tx)},{fmt(fy*sy+ty)}" RadiusX="{fmt(r*abs(sx))}" RadiusY="{fmt(r*abs(sy))}">{stops}</RadialGradientBrush>')


# ----------------------------------------------------------------------------------------------------------- path data
def absolute_path(d):
    """SVG path -> absolute WPF path data (M/L/C/Z only, so there is no parser-compat risk)."""
    tokens = re.findall(r"[MmLlHhVvCcSsZz]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", d)
    out, i, cmd = [], 0, None
    x = y = sx0 = sy0 = 0.0
    last_c2 = None

    def num():
        nonlocal i
        v = float(tokens[i])
        i += 1
        return v

    while i < len(tokens):
        if re.match(r"[A-Za-z]", tokens[i]):
            cmd = tokens[i]
            i += 1
            if cmd in "Zz":
                out.append("Z")
                x, y = sx0, sy0
                last_c2 = None
                continue
        if cmd in "Mm":
            nx, ny = num(), num()
            x, y = (x + nx, y + ny) if cmd == "m" else (nx, ny)
            sx0, sy0 = x, y
            out.append(f"M{fmt(x)},{fmt(y)}")
            cmd = "l" if cmd == "m" else "L"
            last_c2 = None
        elif cmd in "Ll":
            nx, ny = num(), num()
            x, y = (x + nx, y + ny) if cmd == "l" else (nx, ny)
            out.append(f"L{fmt(x)},{fmt(y)}")
            last_c2 = None
        elif cmd in "Hh":
            nx = num()
            x = x + nx if cmd == "h" else nx
            out.append(f"L{fmt(x)},{fmt(y)}")
            last_c2 = None
        elif cmd in "Vv":
            ny = num()
            y = y + ny if cmd == "v" else ny
            out.append(f"L{fmt(x)},{fmt(y)}")
            last_c2 = None
        elif cmd in "Cc":
            v = [num() for _ in range(6)]
            if cmd == "c":
                v = [v[0]+x, v[1]+y, v[2]+x, v[3]+y, v[4]+x, v[5]+y]
            out.append("C" + " ".join(f"{fmt(v[k])},{fmt(v[k+1])}" for k in (0, 2, 4)))
            last_c2 = (v[2], v[3])
            x, y = v[4], v[5]
        elif cmd in "Ss":
            v = [num() for _ in range(4)]
            if cmd == "s":
                v = [v[0]+x, v[1]+y, v[2]+x, v[3]+y]
            c1 = (2*x - last_c2[0], 2*y - last_c2[1]) if last_c2 else (x, y)
            out.append(f"C{fmt(c1[0])},{fmt(c1[1])} {fmt(v[0])},{fmt(v[1])} {fmt(v[2])},{fmt(v[3])}")
            last_c2 = (v[0], v[1])
            x, y = v[2], v[3]
        else:
            raise ValueError("unsupported path command " + str(cmd))
    return " ".join(out)


def circle_path(cx, cy, r):
    return f"M{fmt(cx-r)},{fmt(cy)} A{fmt(r)},{fmt(r)} 0 1 1 {fmt(cx+r)},{fmt(cy)} A{fmt(r)},{fmt(r)} 0 1 1 {fmt(cx-r)},{fmt(cy)} Z"


# ------------------------------------------------------------------------------------------------------------- convert
def shapes(svg_path):
    root = ET.parse(svg_path).getroot()
    rules = parse_css("".join(s.text or "" for s in root.iter("{%s}style" % NS["s"])))
    grads = Gradients(root)
    result = []
    for e in root.iter():
        tag = e.tag.split("}")[1]
        if tag not in ("path", "circle", "rect") or e.get("class") is None:
            continue
        st = style_of(e, rules)
        if tag == "rect":
            continue   # the clipPath rect
        geo = absolute_path(e.get("d")) if tag == "path" else circle_path(float(e.get("cx")), float(e.get("cy")), float(e.get("r")))
        fill = st.get("fill", "black")
        brush = ""
        if fill.startswith("url("):
            brush = grads.brush(fill)
        elif fill != "none":
            brush = fill
        pen = ""
        if st.get("stroke"):
            w = st.get("stroke-width", "1").replace("px", "")
            join = ' LineJoin="Round"' if st.get("stroke-linejoin") == "round" else ""
            pen = f'<Pen Brush="{st["stroke"]}" Thickness="{w}"{join}/>'
        result.append({"geo": geo, "brush": brush, "pen": pen, "tag": tag, "cx": e.get("cx"), "cy": e.get("cy"), "r": e.get("r")})
    return result


def drawing(sh):
    attrs = f'Geometry="{sh["geo"]}"'
    inner = ""
    if sh["brush"]:
        if sh["brush"].startswith("<"):
            inner += f"<GeometryDrawing.Brush>{sh['brush']}</GeometryDrawing.Brush>"
        else:
            attrs += f' Brush="{sh["brush"]}"'
    if sh["pen"]:
        inner += f"<GeometryDrawing.Pen>{sh['pen']}</GeometryDrawing.Pen>"
    return f"<GeometryDrawing {attrs}>{inner}</GeometryDrawing>" if inner else f"<GeometryDrawing {attrs}/>"


def group(name, items, indent="    "):
    lines = [f'{indent}<DrawingGroup x:Key="{name}" ClipGeometry="M0,0 L630,0 630,890 0,890Z">']
    for sh in items:
        lines.append(f"{indent}    {drawing(sh)}")
    lines.append(f"{indent}</DrawingGroup>")
    return "\n".join(lines)


def main():
    left = shapes(SVG_DIR / "Kishi v1 Left.svg")
    right = shapes(SVG_DIR / "Kishi v1 Right.svg")
    assert len(left) == 24 and len(right) == 30, (len(left), len(right))

    out = ['<ResourceDictionary xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"',
           '                    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">',
           "    <!-- Output of tools/svg2xaml.py from SVG/Kishi v1 Left.svg and Right.svg. Do not edit by hand. -->",
           "    <!-- Both halves are 630 x 890; Left's open (phone) side is x=630, Right's is x=0. -->"]

    cap_l = 8
    out.append(group("KishiLeftStatic", [s for i, s in enumerate(left) if i != cap_l]))
    out.append(group("KishiLeftCap", [left[cap_l]]))
    cap_r, led_r = 9, 27
    out.append(group("KishiRightStatic", [s for i, s in enumerate(right) if i not in (cap_r, led_r)]))
    out.append(group("KishiRightCap", [right[cap_r]]))
    # Named geometries used for press highlights.
    out.append(f'    <Geometry x:Key="KishiLeftShoulderGeo">{left[0]["geo"]}</Geometry>')
    out.append(f'    <Geometry x:Key="KishiRightShoulderGeo">{right[0]["geo"]}</Geometry>')
    out.append("</ResourceDictionary>")
    OUT.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    sys.exit(main())
