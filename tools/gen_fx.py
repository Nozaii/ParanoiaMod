"""Textures des ambiances de ciel (panoramas équirectangulaires) et des cartes d'aura (anneaux tourbillonnants).

Usage : python -I tools/gen_fx.py
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
TEX = os.path.join(HERE, "..", "src", "main", "resources", "assets", "aurora", "textures")


def noise(w, h, seed, octaves=6, base=4):
    """Bruit fractal (somme d'octaves de bruit de valeur agrandi en bicubique), répétable horizontalement."""
    rnd = random.Random(seed)
    acc = Image.new("F", (w, h), 0.0)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cw, ch = base * 2 ** o, max(2, base * 2 ** o // 2)
        small = Image.new("L", (cw, ch))
        small.putdata([rnd.randint(0, 255) for _ in range(cw * ch)])
        # bord gauche = bord droit pour un raccord parfait du panorama
        wide = Image.new("L", (cw * 3, ch))
        for k in range(3):
            wide.paste(small, (k * cw, 0))
        big = wide.resize((w * 3, h), Image.BICUBIC).crop((w, 0, w * 2, h)).convert("F")
        pa, pb = acc.load(), big.load()
        for y in range(h):
            for x in range(w):
                pa[x, y] += pb[x, y] * amp
        total += amp
        amp *= 0.55
    pa = acc.load()
    for y in range(h):
        for x in range(w):
            pa[x, y] /= total * 255.0
    return acc


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def ramp(stops, t):
    t = max(0.0, min(1.0, t))
    for i in range(len(stops) - 1):
        t0, c0 = stops[i]
        t1, c1 = stops[i + 1]
        if t <= t1:
            return lerp(c0, c1, (t - t0) / max(1e-6, t1 - t0))
    return stops[-1][1]


def sky(name, seed, stops, horizon, contrast, streaks=None):
    W, H = 512, 256
    n = noise(W, H, seed, 6, 4)
    n2 = noise(W, H, seed + 7, 4, 8)
    img = Image.new("RGB", (W, H))
    p, a, b = img.load(), n.load(), n2.load()
    for y in range(H):
        v = y / (H - 1)  # 0 = zénith, 1 = nadir
        hz = math.exp(-((v - 0.5) / 0.12) ** 2)
        for x in range(W):
            c = (a[x, y] - 0.5) * contrast + 0.5
            c = c * (0.75 + 0.5 * b[x, y]) + hz * horizon
            c *= 1.0 - 0.55 * max(0.0, v - 0.55) / 0.45
            p[x, y] = ramp(stops, c)
    img = img.filter(ImageFilter.GaussianBlur(1.2))
    px = img.load()
    for y in range(H):
        v = y / (H - 1)
        k = max(0.0, 1 - min(v, 1 - v) / 0.2)
        k = k * k * (3 - 2 * k)
        if k <= 0:
            continue
        avg = [0, 0, 0]
        for x in range(W):
            c = px[x, y]
            for i in range(3):
                avg[i] += c[i]
        avg = tuple(a // W for a in avg)
        for x in range(W):
            px[x, y] = lerp(px[x, y], avg, k)
    if streaks:
        rnd = random.Random(seed)
        over = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(over)
        for _ in range(streaks):
            x, y = rnd.uniform(0, W), rnd.uniform(H * 0.25, H * 0.45)
            ln, ang = rnd.uniform(25, 70), rnd.uniform(-0.6, -0.2)
            d.line([(x, y), (x + math.cos(ang) * ln, y - math.sin(ang) * ln)], fill=(200, 220, 255, 150), width=1)
        over = over.filter(ImageFilter.GaussianBlur(0.6))
        img = Image.alpha_composite(img.convert("RGBA"), over).convert("RGB")
    out = os.path.join(TEX, "sky")
    os.makedirs(out, exist_ok=True)
    img.resize((1024, 512), Image.BICUBIC).save(os.path.join(out, name + ".png"))


def aura_card(name, core, glow):
    S = 256
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    halo = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    dh = ImageDraw.Draw(halo)
    dh.ellipse((28, 120, 228, 220), fill=glow + (70,))
    halo = halo.filter(ImageFilter.GaussianBlur(22))
    img = Image.alpha_composite(img, halo)
    arcs = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    da = ImageDraw.Draw(arcs)
    for k, (start, span, r, wdt) in enumerate([(200, 210, 96, 9), (20, 190, 80, 7), (110, 170, 64, 5)]):
        steps = 40
        for i in range(steps):
            u = i / steps
            a0 = math.radians(start + span * u)
            a1 = math.radians(start + span * (u + 1 / steps))
            cx, cy = S / 2, 170
            alpha = int(255 * (u ** 1.3))
            col = lerp(glow, core, u)
            w = max(1, int(wdt * (0.3 + 0.7 * u)))
            da.line([(cx + math.cos(a0) * r, cy + math.sin(a0) * r * 0.36), (cx + math.cos(a1) * r, cy + math.sin(a1) * r * 0.36)],
                    fill=col + (alpha,), width=w)
    glow_arcs = arcs.filter(ImageFilter.GaussianBlur(5))
    img = Image.alpha_composite(img, glow_arcs)
    img = Image.alpha_composite(img, arcs)
    out = os.path.join(TEX, "cosmetic")
    img.save(os.path.join(out, "aura_" + name + ".png"))


if __name__ == "__main__":
    sky("storm", 11, [(0.0, (8, 6, 22)), (0.35, (40, 20, 90)), (0.6, (96, 50, 190)), (0.8, (150, 110, 255)), (1.0, (220, 200, 255))], 0.12, 1.9)
    sky("inferno", 23, [(0.0, (30, 4, 2)), (0.3, (120, 20, 6)), (0.55, (220, 70, 12)), (0.78, (255, 150, 40)), (1.0, (255, 230, 150))], 0.25, 1.7)
    aura_card("neant", (240, 220, 255), (150, 90, 255))
    aura_card("braise", (255, 245, 200), (255, 140, 30))
    aura_card("givre", (230, 250, 255), (80, 200, 255))
    aura_card("etoiles", (255, 255, 255), (190, 200, 255))
    aura_card("coeurs", (255, 225, 240), (255, 90, 160))
    aura_card("spectre", (210, 240, 255), (60, 140, 255))
    print("ok")
