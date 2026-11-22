"""Élytres et boucliers exclusifs : textures de modèle (même disposition UV que vanilla) + aperçus.

Entrées : tools/vanilla/elytra.png (64x32), tools/vanilla/shield_base_nopattern.png (64x64), extraites du jar.
Usage : python -I tools/gen_gear.py
"""
import math
import os
import random

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "aurora", "textures", "cosmetic")


def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def elytra(name, paint):
    base = Image.open(os.path.join(HERE, "vanilla", "elytra.png")).convert("RGBA")
    out = Image.new("RGBA", base.size, (0, 0, 0, 0))
    src, dst = base.load(), out.load()
    rnd = random.Random(name)
    for y in range(base.size[1]):
        for x in range(base.size[0]):
            a = src[x, y][3]
            if a == 0:
                continue
            # coordonnées normalisées dans la zone de l'aile (x 22..46, y 0..22)
            u, v = (x - 22) / 24.0, y / 22.0
            lum = sum(src[x, y][:3]) / 765.0
            dst[x, y] = paint(u, v, lum, rnd) + (a,)
    out.save(os.path.join(OUT, "elytra_model_" + name + ".png"))
    # aperçu : les deux ailes côte à côte
    wing = out.crop((22, 0, 46, 22))
    prev = Image.new("RGBA", (52, 26), (0, 0, 0, 0))
    prev.paste(wing.transpose(Image.FLIP_LEFT_RIGHT), (1, 2), wing.transpose(Image.FLIP_LEFT_RIGHT))
    prev.paste(wing, (27, 2), wing)
    sq = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    big = prev.resize((256, 128), Image.NEAREST)
    sq.paste(big, (0, 64), big)
    sq.save(os.path.join(OUT, "elytra_" + name + ".png"))


def shield(name, paint):
    base = Image.open(os.path.join(HERE, "vanilla", "shield_base_nopattern.png")).convert("RGBA")
    out = base.copy()
    src, dst = base.load(), out.load()
    rnd = random.Random(name)
    # face avant de la plaque : x 1..12, y 1..22 (12 x 22) ; arrière : x 14..25
    for y in range(1, 23):
        for x in range(1, 13):
            u, v = (x - 1) / 11.0, (y - 1) / 21.0
            edge = x in (1, 12) or y in (1, 22)
            dst[x, y] = paint(u, v, edge, rnd) + (255,)
        for x in range(14, 26):
            c = src[x, y]
            dst[x, y] = (int(c[0] * 0.55), int(c[1] * 0.5), int(c[2] * 0.6), 255)
    out.save(os.path.join(OUT, "shield_model_" + name + ".png"))
    face = out.crop((1, 1, 13, 23)).resize((12 * 9, 22 * 9), Image.NEAREST)
    prev = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    prev.paste(face, ((256 - face.width) // 2, (256 - face.height) // 2))
    prev.save(os.path.join(OUT, "shield_" + name + ".png"))


# ------------------------------------------------------------------ élytres

def e_neant(u, v, lum, rnd):
    c = lerp((70, 25, 140), (8, 4, 18), v)
    if rnd.random() < 0.06:
        c = (220, 200, 255)
    return lerp(c, (150, 90, 255), max(0, 0.25 - u) * 2)


def e_aurore(u, v, lum, rnd):
    band = 0.5 + 0.5 * math.sin(u * 9 + v * 4)
    return lerp(lerp((30, 200, 170), (130, 70, 230), v), (220, 255, 245), band * 0.25)


def e_braise(u, v, lum, rnd):
    flame = 0.5 + 0.5 * math.sin(u * 14 + rnd.random() * 1.5)
    return lerp((40, 8, 6), (255, 120 + int(80 * flame), 30), v ** 1.4)


def e_givre(u, v, lum, rnd):
    c = lerp((250, 252, 255), (120, 190, 255), v)
    if rnd.random() < 0.05:
        c = (255, 255, 255)
    return lerp(c, (80, 140, 220), lum * 0.15)


def e_maudite(u, v, lum, rnd):
    vline = abs((u - 0.5) - (v - 0.5) * 0.6) < 0.06
    c = lerp((248, 246, 255), (200, 184, 255), v)
    return (255, 255, 255) if vline else c


# ------------------------------------------------------------------ boucliers

def s_obsidienne(u, v, edge, rnd):
    if edge:
        return (150, 90, 255)
    c = lerp((28, 14, 46), (12, 6, 20), v)
    return (160, 80, 255) if rnd.random() < 0.08 else c


def s_paranoia(u, v, edge, rnd):
    if edge:
        return (40, 30, 60)
    cx, cy = u - 0.5, (v - 0.45) * 1.6
    eye = abs(cx) < 0.42 and abs(cy) < 0.32 * math.cos(cx * 3.4)
    pupil = cx * cx + cy * cy < 0.022
    if pupil:
        return (20, 12, 30)
    if eye:
        return (190, 160, 255)
    return lerp((24, 18, 38), (14, 10, 22), v)


def s_royal(u, v, edge, rnd):
    if edge:
        return (232, 185, 49)
    if abs(u - 0.5) < 0.1 or abs(v - 0.4) < 0.06:
        return (240, 200, 70)
    return lerp((30, 70, 170), (18, 40, 110), v)


def s_givre(u, v, edge, rnd):
    if edge:
        return (255, 255, 255)
    c = lerp((210, 240, 255), (110, 180, 240), v)
    return (255, 255, 255) if rnd.random() < 0.05 else c


def s_sang(u, v, edge, rnd):
    if edge:
        return (20, 6, 8)
    drip = v < 0.25 + 0.15 * math.sin(u * 20)
    return (120, 10, 18) if drip else lerp((90, 8, 14), (40, 4, 8), v)


for n, f in (("obsidienne", s_obsidienne), ("paranoia", s_paranoia), ("royal", s_royal), ("givre", s_givre), ("sang", s_sang)):
    shield(n, f)
print("ok")
