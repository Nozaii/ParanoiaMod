"""Textures du menu principal : logo HD, rideaux de lumière, étincelles à 4 branches.

Usage : python -I tools/gen_title.py
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gen_icons  # noqa: E402

OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "aurora", "textures", "ui")
META = json.dumps({"texture": {"blur": True, "clamp": True}})


def save(img, name):
    path = os.path.join(OUT, name + ".png")
    img.save(path)
    with open(path + ".mcmeta", "w") as f:
        f.write(META)


def white(alpha):
    out = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def logo():
    pen = gen_icons.Pen()
    gen_icons.eye_logo(pen)
    a = pen.img.resize((256, 256), Image.LANCZOS)
    save(white(a), "title_logo")


def ray():
    """Rideau de lumière vertical : cœur fin + halo large, fondu en haut et en bas."""
    w, h = 64, 512
    img = Image.new("L", (w, h), 0)
    px = img.load()
    for y in range(h):
        t = y / (h - 1)
        vert = math.sin(math.pi * t) ** 1.6
        for x in range(w):
            d = (x - (w - 1) / 2) / (w / 2)
            core = math.exp(-(d * 9) ** 2)
            halo = math.exp(-(d * 2.6) ** 2) * 0.45
            px[x, y] = int(255 * min(1.0, (core + halo) * vert))
    save(white(img), "light_ray")


def sparkle():
    """Étincelle à 4 branches fines avec un petit halo."""
    s = 512
    img = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(img)
    c = s / 2
    for ang in (0, 90):
        r = math.radians(ang)
        long, thin = s * 0.48, s * 0.035
        pts = []
        for k in range(4):
            a = r + k * math.pi / 2
            rad = long if k % 2 == 0 else thin
            pts.append((c + math.cos(a) * rad, c + math.sin(a) * rad))
        d.polygon(pts, fill=255)
    glow = Image.new("L", (s, s), 0)
    ImageDraw.Draw(glow).ellipse((c - s * 0.16, c - s * 0.16, c + s * 0.16, c + s * 0.16), fill=150)
    glow = glow.filter(ImageFilter.GaussianBlur(s * 0.06))
    merged = Image.new("L", (s, s), 0)
    mp, ip, gp = merged.load(), img.load(), glow.load()
    for y in range(s):
        for x in range(s):
            mp[x, y] = min(255, ip[x, y] + gp[x, y])
    save(white(merged.resize((64, 64), Image.LANCZOS)), "sparkle")


def curtain(name, seed):
    """Rideau de lumière : brins en S doux, périodiques verticalement (se répète sans couture)."""
    import random
    rnd = random.Random(seed)
    w, h = 128, 512
    strands = []
    for _ in range(3):
        strands.append((rnd.uniform(0.3, 0.7) * w, rnd.uniform(8, 20), rnd.choice((1, 2)), rnd.uniform(0, 6.28),
                        rnd.uniform(1.5, 4.0), rnd.uniform(0.45, 1.0), rnd.uniform(0, 6.28)))
    img = Image.new("L", (w, h), 0)
    px = img.load()
    for y in range(h):
        acc = [0.0] * w
        for cx, amp, k, ph, width, inten, ph2 in strands:
            ang = 2 * math.pi * k * y / h + ph
            x0 = cx + amp * math.sin(ang) + amp * 0.35 * math.sin(2 * ang + ph2)
            wid = width * (0.7 + 0.3 * math.sin(2 * math.pi * y / h + ph2))
            bright = inten * (0.55 + 0.45 * math.sin(2 * math.pi * y / h * k + ph2))
            for x in range(w):
                d = (x - x0) / wid
                acc[x] += bright * (math.exp(-d * d) + 0.25 * math.exp(-(d / 5) ** 2))
        for x in range(w):
            edge = min(1.0, x / 20, (w - 1 - x) / 20)
            px[x, y] = int(255 * min(1.0, acc[x] * edge))
    out = white(img)
    path = os.path.join(OUT, name + ".png")
    out.save(path)
    with open(path + ".mcmeta", "w") as f:
        f.write(json.dumps({"texture": {"blur": True, "clamp": False}}))


def star_outline():
    """Étoile à 4 branches en contour (comme les scintillements du menu de référence)."""
    s = 512
    img = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(img)
    c = s / 2
    pts = []
    for k in range(8):
        a = -math.pi / 2 + k * math.pi / 4
        rad = s * 0.44 if k % 2 == 0 else s * 0.12
        pts.append((c + math.cos(a) * rad, c + math.sin(a) * rad))
    pts.append(pts[0])
    d.line(pts, fill=255, width=int(s * 0.045), joint="curve")
    save(white(img.resize((64, 64), Image.LANCZOS)), "star_outline")


def vignette():
    """Dégradé radial doux (assombrit les bords)."""
    s = 256
    img = Image.new("L", (s, s), 0)
    px = img.load()
    for y in range(s):
        for x in range(s):
            dx, dy = (x - s / 2) / (s / 2), (y - s / 2) / (s / 2)
            r = math.sqrt(dx * dx + dy * dy)
            px[x, y] = int(255 * min(1.0, max(0.0, (r - 0.35) / 0.75)) ** 1.5)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.putalpha(img)
    path = os.path.join(OUT, "vignette.png")
    out.save(path)
    with open(path + ".mcmeta", "w") as f:
        f.write(META)


def main():
    logo()
    ray()
    sparkle()
    star_outline()
    curtain("curtain_a", 3)
    curtain("curtain_b", 11)
    vignette()
    print("ok ->", os.path.normpath(OUT))


if __name__ == "__main__":
    sys.exit(main())
