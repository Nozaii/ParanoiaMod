"""Aile de papillon monarque (côté droit) : textures/cosmetic/butterfly_wing.png (+ aperçu wings_papillon.png).

L'aile part du corps (bord gauche, milieu) : aile antérieure vers le haut-droite, postérieure vers le bas.
Usage : python -I tools/gen_butterfly.py
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "aurora", "textures", "cosmetic")
S = 1024
BLACK = (18, 12, 10, 255)


def poly(cx, cy, pts, sc):
    return [(cx + x * sc, cy + y * sc) for x, y in pts]


def wing():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    root = (40, 470)
    # silhouettes (coordonnées 0..1000 dans un carré de 1000)
    fore = [(0, 470), (120, 330), (300, 170), (520, 70), (760, 40), (930, 70), (990, 140), (960, 260), (860, 360),
            (700, 430), (520, 470), (300, 500), (0, 500)]
    hind = [(0, 500), (220, 500), (420, 520), (600, 600), (700, 700), (690, 820), (600, 900), (470, 950), (330, 960),
            (200, 920), (90, 820), (20, 680)]
    k = S / 1000
    for shape in (hind, fore):
        d.polygon([(x * k, y * k) for x, y in shape], fill=BLACK)
    # intérieur orange (contour noir épais conservé)
    inner = Image.new("L", (S, S), 0)
    di = ImageDraw.Draw(inner)
    for shape, grow in ((fore, 0.88), (hind, 0.84)):
        cx = sum(x for x, _ in shape) / len(shape)
        cy = sum(y for _, y in shape) / len(shape)
        di.polygon([((cx + (x - cx) * grow) * k, (cy + (y - cy) * grow) * k) for x, y in shape], fill=255)
    inner = inner.filter(ImageFilter.GaussianBlur(3))
    orange = Image.new("RGBA", (S, S))
    po = orange.load()
    for y in range(S):
        for x in range(S):
            t = min(1.0, math.hypot(x - root[0] * k, y - root[1] * k) / (900 * k))
            po[x, y] = (int(255 - 20 * t), int(120 + 50 * t), int(20 + 10 * t), 255)
    img.paste(orange, (0, 0), inner)
    # nervures noires qui rayonnent depuis la base (calque découpé à la silhouette de l'aile)
    sil = img.split()[3]
    base_img = img
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    rx, ry = root[0] * k, root[1] * k
    for ang, ln in ((-62, 560), (-48, 720), (-35, 820), (-22, 880), (-10, 820), (2, 700)):
        a = math.radians(ang)
        mx, my = rx + math.cos(a) * ln * 0.45 * k, ry + math.sin(a) * ln * 0.45 * k
        ex, ey = rx + math.cos(a) * ln * k, ry + math.sin(a) * ln * k
        d.line([(rx, ry), (mx, my - 25 * k), (ex, ey)], fill=BLACK, width=int(11 * k))
    for ang, ln in ((18, 520), (35, 620), (52, 640), (68, 560), (82, 440)):
        a = math.radians(ang)
        ex, ey = rx + math.cos(a) * ln * k, ry + math.sin(a) * ln * k
        d.line([(rx, ry), (ex, ey)], fill=BLACK, width=int(10 * k))
    # cellule fermée de l'aile antérieure
    d.line([(300 * k, 420 * k), (470 * k, 320 * k), (600 * k, 300 * k)], fill=BLACK, width=int(10 * k))
    veins = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    veins.paste(img, (0, 0), Image.composite(img.split()[3], Image.new("L", (S, S), 0), sil))
    img = Image.alpha_composite(base_img, veins)
    d = ImageDraw.Draw(img)
    # points blancs et orange pâle sur la bordure noire
    rnd = random.Random(7)
    for shape, n in ((fore, 34), (hind, 30)):
        cx = sum(x for x, _ in shape) / len(shape)
        cy = sum(y for _, y in shape) / len(shape)
        for i in range(n):
            j = i * (len(shape) - 1) / n
            a, b = shape[int(j)], shape[int(j) + 1]
            f = j - int(j)
            x, y = a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f
            if x < 60:
                continue
            x, y = cx + (x - cx) * 0.95, cy + (y - cy) * 0.95
            r = rnd.uniform(5, 9)
            d.ellipse(((x - r) * k, (y - r) * k, (x + r) * k, (y + r) * k), fill=(250, 245, 235, 255))
    for x, y in ((820, 120), (870, 170), (780, 170), (860, 230), (740, 230)):
        d.ellipse(((x - 16) * k, (y - 13) * k, (x + 16) * k, (y + 13) * k), fill=(255, 205, 120, 255))
    # léger dégradé sombre à la base (écailles plus denses)
    shade = Image.new("L", (S, S), 0)
    ImageDraw.Draw(shade).ellipse((rx - 120 * k, ry - 120 * k, rx + 120 * k, ry + 120 * k), fill=150)
    shade = shade.filter(ImageFilter.GaussianBlur(40))
    dark = Image.new("RGBA", (S, S), BLACK)
    alpha = img.split()[3]
    img = Image.composite(dark, img, shade)
    img.putalpha(alpha)
    return img


w = wing()
w.resize((256, 256), Image.LANCZOS).save(os.path.join(OUT, "butterfly_wing.png"))
w.resize((256, 256), Image.LANCZOS).save(os.path.join(OUT, "wings_papillon.png"))
print("ok")
