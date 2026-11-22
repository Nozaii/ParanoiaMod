"""Génère les capes HD (512x256, soit 8x la résolution vanilla) pour la wardrobe.

Disposition vanilla (64x32) : face extérieure (1,1) 10x16, face intérieure (12,1) 10x16,
bords (0,1)/(11,1) 1x16, dessus (1,0) / dessous (11,0) 10x1. Usage : python -I tools/gen_capes.py
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

K = 8
W, H = 64 * K, 32 * K
FW, FH = 10 * K, 16 * K
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                   "assets", "aurora", "textures", "cape")


def grad(w, h, top, bottom):
    img = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)) + (255,))
    return img


def glow_layer(size, draw_fn, blur):
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer))
    return layer.filter(ImageFilter.GaussianBlur(blur))


def border(face, color):
    d = ImageDraw.Draw(face)
    d.rectangle((0, 0, FW - 1, FH - 1), outline=color, width=3)


def paranoia():
    f = grad(FW, FH, (22, 12, 44), (92, 44, 190))
    cx, cy = FW / 2, FH * 0.42

    def eye(d, col, wid):
        pts = [(cx - 30 + 60 * i / 40, cy - 16 * math.sin(math.pi * i / 40)) for i in range(41)]
        pts += [(cx + 30 - 60 * i / 40, cy + 16 * math.sin(math.pi * i / 40)) for i in range(41)]
        d.line(pts, fill=col, width=wid)
        d.ellipse((cx - 9, cy - 9, cx + 9, cy + 9), outline=col, width=wid)
        d.ellipse((cx - 3.5, cy - 3.5, cx + 3.5, cy + 3.5), fill=col)
    f.alpha_composite(glow_layer(f.size, lambda d: eye(d, (190, 150, 255, 255), 6), 6))
    eye(ImageDraw.Draw(f), (245, 238, 255, 255), 3)
    d = ImageDraw.Draw(f)
    d.line((cx - 18, FH * 0.75, cx + 18, FH * 0.75), fill=(230, 220, 255, 200), width=2)
    border(f, (170, 130, 255, 255))
    return f, (60, 30, 120)


def nocturne():
    f = grad(FW, FH, (8, 8, 26), (40, 24, 80))
    rnd = random.Random(4)
    d = ImageDraw.Draw(f)
    for _ in range(46):
        x, y, r = rnd.uniform(4, FW - 4), rnd.uniform(4, FH - 4), rnd.choice((0.8, 1, 1.2, 1.8))
        d.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, rnd.randint(140, 255)))
    moon = Image.new("RGBA", f.size, (0, 0, 0, 0))
    md = ImageDraw.Draw(moon)
    md.ellipse((FW * 0.5 - 16, 18, FW * 0.5 + 16, 50), fill=(245, 240, 255, 255))
    md.ellipse((FW * 0.5 - 6, 12, FW * 0.5 + 24, 44), fill=(0, 0, 0, 0))
    f.alpha_composite(moon.filter(ImageFilter.GaussianBlur(5)))
    f.alpha_composite(moon)
    border(f, (120, 100, 200, 255))
    return f, (20, 16, 50)


def aurore():
    f = grad(FW, FH, (6, 6, 20), (14, 10, 34))
    layer = Image.new("RGBA", f.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for x in range(FW):
        t = x / FW * math.tau
        top = FH * (0.18 + 0.08 * math.sin(t * 1.5 + 1))
        inten = 0.5 + 0.5 * math.sin(t * 2.3)
        col = (int(120 + 60 * inten), int(90 + 120 * (1 - inten)), 255, int(120 + 120 * inten))
        d.line((x, top, x, top + FH * 0.45), fill=col)
    f.alpha_composite(layer.filter(ImageFilter.GaussianBlur(4)))
    hills = Image.new("RGBA", f.size, (0, 0, 0, 0))
    hd = ImageDraw.Draw(hills)
    hd.polygon([(0, FH * 0.8)] + [(x, FH * (0.78 + 0.05 * math.sin(x / 9))) for x in range(0, FW + 1, 4)] + [(FW, FH), (0, FH)],
               fill=(4, 4, 12, 255))
    f.alpha_composite(hills)
    border(f, (140, 120, 255, 255))
    return f, (14, 10, 34)


def toile():
    f = grad(FW, FH, (14, 8, 22), (34, 14, 50))
    d = ImageDraw.Draw(f)
    ox, oy = 0, 0
    col = (205, 195, 230, 210)
    for i in range(7):
        a = math.radians(4 + i * 13.5)
        d.line((ox, oy, ox + math.cos(a) * 140, oy + math.sin(a) * 140), fill=col, width=1)
    for r in range(14, 140, 14):
        pts = [(ox + math.cos(math.radians(4 + i * 13.5)) * r, oy + math.sin(math.radians(4 + i * 13.5)) * r) for i in range(7)]
        for a, b in zip(pts, pts[1:]):
            mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
            d.line([a, (mx * 0.94, my * 0.94), b], fill=col, width=1)
    sx, sy = FW * 0.65, FH * 0.62
    d.line((sx, 0, sx, sy - 6), fill=(200, 190, 220, 160), width=1)
    d.ellipse((sx - 6, sy - 7, sx + 6, sy + 7), fill=(10, 6, 14, 255))
    for k in (-1, 1):
        for j in range(4):
            d.line((sx, sy, sx + k * 12, sy - 6 + j * 4), fill=(10, 6, 14, 255), width=2)
    d.ellipse((sx - 3, sy - 3, sx - 1, sy - 1), fill=(200, 60, 255, 255))
    d.ellipse((sx + 1, sy - 3, sx + 3, sy - 1), fill=(200, 60, 255, 255))
    border(f, (120, 70, 170, 255))
    return f, (24, 10, 34)


def braise():
    f = grad(FW, FH, (20, 4, 8), (150, 40, 10))
    rnd = random.Random(9)
    layer = Image.new("RGBA", f.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for _ in range(60):
        x, y = rnd.uniform(0, FW), rnd.uniform(FH * 0.3, FH)
        r = rnd.uniform(0.8, 2.4)
        d.ellipse((x - r, y - r, x + r, y + r), fill=(255, rnd.randint(140, 220), 60, rnd.randint(120, 255)))
    f.alpha_composite(layer.filter(ImageFilter.GaussianBlur(1.5)))
    f.alpha_composite(layer)
    border(f, (255, 120, 50, 255))
    return f, (60, 10, 10)


def glitch():
    f = grad(FW, FH, (6, 6, 10), (12, 8, 22))
    rnd = random.Random(21)
    d = ImageDraw.Draw(f)
    for y in range(0, FH, 3):
        d.line((0, y, FW, y), fill=(255, 255, 255, 10))
    for _ in range(22):
        y = rnd.uniform(0, FH)
        h = rnd.uniform(2, 7)
        x0 = rnd.uniform(-10, FW * 0.6)
        col = rnd.choice(((160, 90, 255, 230), (60, 220, 255, 200), (240, 240, 255, 180)))
        d.rectangle((x0, y, x0 + rnd.uniform(14, 60), y + h), fill=col)
    border(f, (160, 90, 255, 255))
    return f, (10, 8, 18)


CAPES = {"paranoia": paranoia, "nocturne": nocturne, "aurore": aurore, "toile": toile, "braise": braise, "glitch": glitch}


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, fn in CAPES.items():
        face, edge = fn()
        img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        # bords / dessus / dessous
        d.rectangle((0, K, K - 1, 17 * K - 1), fill=edge + (255,))
        d.rectangle((11 * K, K, 12 * K - 1, 17 * K - 1), fill=edge + (255,))
        d.rectangle((K, 0, 11 * K - 1, K - 1), fill=edge + (255,))
        d.rectangle((11 * K, 0, 21 * K - 1, K - 1), fill=edge + (255,))
        img.paste(face, (K, K))
        img.paste(face.transpose(Image.FLIP_LEFT_RIGHT), (12 * K, K))
        img.save(os.path.join(OUT, name + ".png"))
    print(len(CAPES), "capes ->", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
