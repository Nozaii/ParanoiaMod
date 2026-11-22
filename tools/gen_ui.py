"""Génère les textures d'interface lisses : 9-slices (fond, contour, halo), aurore, montagnes, étoile.

Usage : python -I tools/gen_ui.py
"""
import json
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                   "assets", "aurora", "textures", "ui")
SS = 8  # supersampling pour l'anticrénelage


def save(img, name, clamp=True):
    path = os.path.join(OUT, name + ".png")
    img.save(path)
    with open(path + ".mcmeta", "w") as f:
        json.dump({"texture": {"blur": True, "clamp": clamp}}, f)


def white_alpha(alpha):
    out = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def rounded_mask(size, inset, radius, stroke=None):
    big = size * SS
    m = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(m)
    box = (inset * SS, inset * SS, big - inset * SS - 1, big - inset * SS - 1)
    if stroke is None:
        d.rounded_rectangle(box, radius * SS, fill=255)
    else:
        d.rounded_rectangle(box, radius * SS, outline=255, width=int(stroke * SS))
    return m.resize((size, size), Image.LANCZOS)


def nine_slices():
    # 64x64, coin de 24 texels = rayon 24
    save(white_alpha(rounded_mask(64, 0, 24)), "fill")
    save(white_alpha(rounded_mask(64, 0, 24, stroke=2.2)), "ring")
    # halo : forme de rayon 12 avec 24 de marge, flou gaussien -> coin de 36 texels
    m = Image.new("L", (96, 96), 0)
    ImageDraw.Draw(m).rounded_rectangle((24, 24, 71, 71), 12, fill=255)
    m = m.filter(ImageFilter.GaussianBlur(9))
    save(white_alpha(m), "glow")


def star():
    m = Image.new("L", (32, 32), 0)
    ImageDraw.Draw(m).ellipse((12, 12, 19, 19), fill=255)
    m = m.filter(ImageFilter.GaussianBlur(2.2))
    core = Image.new("L", (32, 32), 0)
    ImageDraw.Draw(core).ellipse((14, 14, 17, 17), fill=255)
    m = Image.composite(core, m, core)
    save(white_alpha(m), "star")


def aurora():
    """Rideaux verticaux doux, raccordables horizontalement."""
    w, h = 1024, 512
    rnd = random.Random(7)
    waves = [(rnd.randint(1, 4), rnd.random() * math.tau, rnd.uniform(0.4, 1.0)) for _ in range(5)]
    fine = [(rnd.randint(12, 40), rnd.random() * math.tau, rnd.uniform(0.15, 0.35)) for _ in range(6)]
    img = Image.new("L", (w, h), 0)
    px = img.load()
    for x in range(w):
        t = x / w * math.tau
        base = sum(a * (0.5 + 0.5 * math.sin(k * t + p)) for k, p, a in waves) / sum(a for _, _, a in waves)
        streak = sum(a * (0.5 + 0.5 * math.sin(k * t + p)) for k, p, a in fine) / sum(a for _, _, a in fine)
        inten = max(0.0, base * 1.25 - 0.25) * (0.55 + 0.45 * streak)
        # hauteur du rideau qui ondule
        top = h * (0.10 + 0.10 * math.sin(2 * t + 1.0) + 0.04 * math.sin(7 * t))
        peak = top + h * 0.22
        bottom = h * 0.92
        for y in range(h):
            if y < top:
                v = 0.0
            elif y < peak:
                v = ((y - top) / (peak - top)) ** 1.6
            else:
                v = max(0.0, 1 - (y - peak) / (bottom - peak)) ** 2.2
            px[x, y] = int(255 * min(1.0, inten * v))
    img = img.filter(ImageFilter.GaussianBlur(3))
    save(white_alpha(img), "aurora", clamp=False)


def mountains(name, w, h, layers, seed):
    rnd = random.Random(seed)
    terms = [(k, rnd.random() * math.tau, a) for k, a in layers]
    big = Image.new("L", (w * 2, h * 2), 0)
    d = ImageDraw.Draw(big)
    pts = []
    for x in range(w * 2 + 1):
        t = x / (w * 2) * math.tau
        y = 0.5 + sum(a * math.sin(k * t + p) for k, p, a in terms)
        pts.append((x, max(0.02, min(0.98, y)) * h * 2))
    d.polygon(pts + [(w * 2, h * 2), (0, h * 2)], fill=255)
    img = big.resize((w, h), Image.LANCZOS)
    save(white_alpha(img), name, clamp=False)


def main():
    os.makedirs(OUT, exist_ok=True)
    nine_slices()
    star()
    aurora()
    mountains("mountains_back", 1024, 160, [(2, 0.18), (5, 0.10), (11, 0.05), (23, 0.02)], 3)
    mountains("mountains_mid", 1024, 144, [(3, 0.15), (6, 0.09), (13, 0.04), (29, 0.018)], 5)
    mountains("mountains_front", 1024, 128, [(3, 0.16), (7, 0.08), (17, 0.035), (37, 0.015)], 11)
    print("textures ui ->", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
