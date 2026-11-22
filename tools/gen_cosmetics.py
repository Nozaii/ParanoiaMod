"""Génère les textures des cosmétiques : ailes, halos, cercles d'aura.

Ailes : une seule aile (l'autre est le miroir), racine au bord gauche, 256x256.
Halos et auras : anneaux lumineux vus de dessus, 256x256. Usage : python -I tools/gen_cosmetics.py
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                   "assets", "aurora", "textures", "cosmetic")
S = 256


def blank():
    return Image.new("RGBA", (S, S), (0, 0, 0, 0))


def feathers(top, bottom, edge, seed, soft=False):
    """Aile à plumes : rangées de plumes en éventail depuis la racine (bord gauche)."""
    img = blank()
    d = ImageDraw.Draw(img)
    rnd = random.Random(seed)
    root = (6, S * 0.42)
    rows = [(0.95, 13, 0), (0.72, 11, 0.15), (0.5, 9, 0.3)]
    for length, count, tint in rows:
        for i in range(count):
            t = i / (count - 1)
            ang = math.radians(-58 + t * 98)  # du haut vers le bas
            L = S * length * (0.75 + 0.25 * math.sin(t * math.pi)) * rnd.uniform(0.93, 1.0)
            ex, ey = root[0] + math.cos(ang) * L, root[1] + math.sin(ang) * L
            w = 10 + 8 * (1 - t)
            nx, ny = -math.sin(ang), math.cos(ang)
            col = tuple(int(top[k] + (bottom[k] - top[k]) * (t * 0.7 + tint)) for k in range(3))
            pts = [(root[0] + nx * 3, root[1] + ny * 3), (ex + nx * w * 0.5, ey + ny * w * 0.5),
                   (ex + math.cos(ang) * 10, ey + math.sin(ang) * 10), (ex - nx * w * 0.5, ey - ny * w * 0.5),
                   (root[0] - nx * 3, root[1] - ny * 3)]
            d.polygon(pts, fill=col + (255,), outline=edge + (255,))
    if soft:
        glow = img.filter(ImageFilter.GaussianBlur(4))
        out = blank()
        out.alpha_composite(glow)
        out.alpha_composite(img)
        return out
    return img


def demon():
    img = blank()
    d = ImageDraw.Draw(img)
    root = (8, S * 0.4)
    tips = [(S * 0.98, S * 0.08), (S * 0.92, S * 0.42), (S * 0.75, S * 0.7), (S * 0.5, S * 0.88)]
    # membrane : festons entre les doigts
    poly = [root, (S * 0.55, S * 0.02), tips[0]]
    for a, b in zip(tips, tips[1:]):
        mx, my = (a[0] + b[0]) / 2 - 14, (a[1] + b[1]) / 2 - 10
        poly += [(mx, my), b]
    poly += [(S * 0.2, S * 0.62)]
    d.polygon(poly, fill=(70, 8, 16, 235))
    for tp in tips:
        d.line([root, tp], fill=(20, 4, 8, 255), width=6)
    d.line([root, (S * 0.55, S * 0.02), tips[0]], fill=(20, 4, 8, 255), width=7)
    return img


def butterfly():
    img = blank()
    d = ImageDraw.Draw(img)
    up = [(6, S * 0.45), (S * 0.35, S * 0.02), (S * 0.92, S * 0.06), (S * 0.86, S * 0.42), (6, S * 0.52)]
    low = [(6, S * 0.52), (S * 0.72, S * 0.55), (S * 0.62, S * 0.95), (S * 0.25, S * 0.88)]
    d.polygon(up, fill=(157, 124, 255, 240), outline=(30, 10, 60, 255))
    d.polygon(low, fill=(242, 123, 209, 240), outline=(30, 10, 60, 255))
    for cx, cy, r, col in ((S * 0.6, S * 0.2, 18, (95, 211, 243)), (S * 0.45, S * 0.7, 14, (255, 255, 255)),
                           (S * 0.78, S * 0.3, 9, (255, 255, 255))):
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=col + (255,), outline=(30, 10, 60, 255), width=3)
    d.line([(6, S * 0.48), (S * 0.86, S * 0.25)], fill=(30, 10, 60, 255), width=3)
    return img


def ring(color, inner, outer, runes=False, spikes=0, blur=6):
    big = Image.new("L", (S * 2, S * 2), 0)
    d = ImageDraw.Draw(big)
    c = S
    d.ellipse((c - outer * 2, c - outer * 2, c + outer * 2, c + outer * 2), fill=255)
    d.ellipse((c - inner * 2, c - inner * 2, c + inner * 2, c + inner * 2), fill=0)
    if runes:
        for i in range(12):
            a = math.tau * i / 12
            x, y = c + math.cos(a) * (inner - 16) * 2, c + math.sin(a) * (inner - 16) * 2
            d.regular_polygon((x, y, 12), 3, rotation=math.degrees(a), fill=255)
        d.ellipse((c - (inner - 32) * 2, c - (inner - 32) * 2, c + (inner - 32) * 2, c + (inner - 32) * 2), outline=255, width=6)
    for i in range(spikes):
        a = math.tau * i / spikes
        pts = [(c + math.cos(a - 0.08) * outer * 2, c + math.sin(a - 0.08) * outer * 2),
               (c + math.cos(a) * (outer + 18) * 2, c + math.sin(a) * (outer + 18) * 2),
               (c + math.cos(a + 0.08) * outer * 2, c + math.sin(a + 0.08) * outer * 2)]
        d.polygon(pts, fill=255)
    mask = big.resize((S, S), Image.LANCZOS)
    glow = mask.filter(ImageFilter.GaussianBlur(blur))
    alpha = Image.eval(Image.merge("L", [glow]), lambda v: v)
    out = Image.new("RGBA", (S, S), color + (0,))
    a = Image.blend(glow, mask, 0.6)
    out.putalpha(a)
    core = Image.new("RGBA", (S, S), (255, 255, 255, 0))
    core.putalpha(mask.point(lambda v: int(v * 0.55)))
    out.alpha_composite(core)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    feathers((255, 255, 255), (205, 215, 235), (170, 180, 205), 1).save(os.path.join(OUT, "wings_angel.png"))
    feathers((190, 160, 255), (110, 60, 230), (70, 30, 160), 2, soft=True).save(os.path.join(OUT, "wings_paranoia.png"))
    feathers((40, 40, 48), (10, 10, 14), (90, 90, 110), 3).save(os.path.join(OUT, "wings_corbeau.png"))
    demon().save(os.path.join(OUT, "wings_demon.png"))
    butterfly().save(os.path.join(OUT, "wings_papillon.png"))
    ring((255, 214, 94), 86, 104).save(os.path.join(OUT, "halo_or.png"))
    ring((176, 140, 255), 86, 104, spikes=10).save(os.path.join(OUT, "halo_paranoia.png"))
    ring((140, 220, 255), 86, 102, spikes=16).save(os.path.join(OUT, "halo_givre.png"))
    for name, col in (("neant", (176, 120, 255)), ("braise", (255, 140, 50)), ("givre", (130, 220, 255)),
                      ("etoiles", (255, 255, 255)), ("coeurs", (255, 110, 170))):
        ring(col, 92, 116, runes=True, blur=8).save(os.path.join(OUT, f"aura_{name}.png"))
    print("cosmétiques ->", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
