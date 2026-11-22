"""Ambiances de ciel en couches (panoramas 1024x512) : aurore cosmique + planète, veines d'éclairs,
nébuleuse violette, orage. Les couches « *_fx » sont additives (noir = transparent).

Usage : python -I tools/gen_sky2.py
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

import gen_fx

OUT = os.path.join(gen_fx.TEX, "sky")
W, H = 512, 256


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def save(img, name):
    os.makedirs(OUT, exist_ok=True)
    img.resize((1024, 512), Image.BICUBIC).save(os.path.join(OUT, name + ".png"))


def poles(img):
    """Fond uniforme aux pôles : pas de pincement visible au zénith / nadir."""
    px = img.load()
    for y in range(H):
        v = y / (H - 1)
        k = smooth(1 - min(v, 1 - v) / 0.18)
        if k <= 0:
            continue
        avg = [sum(px[x, y][i] for x in range(W)) // W for i in range(3)]
        for x in range(W):
            px[x, y] = gen_fx.lerp(px[x, y], avg, k)


def stars(img, seed, count, vmax=0.55):
    """Étoiles fines dessinées à la résolution finale (après agrandissement), plus rares près du zénith."""
    rnd = random.Random(seed)
    w, h = img.size
    px = img.load()
    for _ in range(count):
        v = rnd.uniform(0.16, vmax)
        x, y = int(rnd.uniform(0, w)), int(v * h)
        b = rnd.randint(90, 200) if rnd.random() < 0.9 else 235
        c = px[x, y]
        px[x, y] = tuple(min(255, c[i] + b) for i in range(3))


def save_stars(img, name, seed, count, vmax=0.55):
    os.makedirs(OUT, exist_ok=True)
    big = img.resize((1024, 512), Image.BICUBIC)
    stars(big, seed, count, vmax)
    big.save(os.path.join(OUT, name + ".png"))


def ridged(n, power):
    """Bruit « en crêtes » : lignes fines et ramifiées (veines, éclairs)."""
    p = n.load()
    out = Image.new("F", (W, H))
    o = out.load()
    for y in range(H):
        for x in range(W):
            r = 1 - abs(p[x, y] * 2 - 1)
            o[x, y] = r ** power
    return out


# ------------------------------------------------------------------ aurore cosmique (image 2)

def cosmic():
    n = gen_fx.noise(W, H, 301, 5, 4)
    a = n.load()
    base = Image.new("RGB", (W, H))
    p = base.load()
    for y in range(H):
        v = y / (H - 1)
        for x in range(W):
            hz = math.exp(-((v - 0.52) / 0.10) ** 2)
            t = 0.15 + 0.35 * (1 - abs(v - 0.5) * 2) + 0.25 * (a[x, y] - 0.5) + 0.35 * hz
            if v > 0.55:
                t *= 1 - 0.7 * (v - 0.55) / 0.45
            p[x, y] = gen_fx.ramp([(0, (6, 3, 18)), (0.35, (30, 10, 70)), (0.6, (80, 30, 150)), (0.85, (170, 90, 230)),
                                   (1, (230, 190, 255))], t)
    base = base.filter(ImageFilter.GaussianBlur(1.0))
    poles(base)
    save_stars(base, "cosmic", 302, 700)

    # rideaux d'aurore : rubans verticaux près de l'horizon, lumineux en bas, s'estompant vers le haut
    fx = Image.new("RGB", (W, H))
    q = fx.load()
    rnd = random.Random(303)
    phases = [(rnd.uniform(1, 4), rnd.uniform(0, 6.28), rnd.uniform(0.4, 1)) for _ in range(6)]
    streak = gen_fx.noise(W, 4, 304, 3, 96).resize((W, H), Image.BICUBIC).load()
    for x in range(W):
        u = x / W * math.pi * 2
        band = sum(amp * (0.5 + 0.5 * math.sin(u * f + ph)) for f, ph, amp in phases) / 3.3
        band = smooth((band - 0.35) / 0.5)
        top = 0.20 + 0.12 * math.sin(u * 2 + 1.3)
        for y in range(H):
            v = y / (H - 1)
            if v < top or v > 0.54:
                q[x, y] = (0, 0, 0)
                continue
            k = smooth((v - top) / (0.5 - top)) * smooth((0.54 - v) / 0.04)
            k *= band * (0.3 + 0.7 * streak[x, y] ** 2) * 1.9
            c = gen_fx.lerp((90, 40, 255), (230, 120, 255), (v - top) / (0.54 - top))
            q[x, y] = tuple(int(ch * k) for ch in c)
    fx = fx.filter(ImageFilter.GaussianBlur(1.4))
    save(fx, "cosmic_fx")

    # planète à anneaux (sprite)
    S = 256
    pl = Image.new("RGBA", (S * 2, S * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(pl)
    cx = cy = S
    # anneau arrière
    d.ellipse((cx - 230, cy - 62, cx + 230, cy + 62), outline=(200, 170, 255, 210), width=14)
    d.ellipse((cx - 200, cy - 50, cx + 200, cy + 50), outline=(150, 110, 230, 150), width=8)
    body = Image.new("RGBA", (S * 2, S * 2), (0, 0, 0, 0))
    bd = ImageDraw.Draw(body)
    bd.ellipse((cx - 120, cy - 120, cx + 120, cy + 120), fill=(215, 210, 230, 255))
    # cratères et ombre
    rnd = random.Random(305)
    for _ in range(26):
        r = rnd.uniform(6, 22)
        x, y = cx + rnd.uniform(-95, 95), cy + rnd.uniform(-95, 95)
        if (x - cx) ** 2 + (y - cy) ** 2 < (110 - r) ** 2:
            bd.ellipse((x - r, y - r, x + r, y + r), fill=(160, 150, 185, 255))
    shade = Image.new("L", (S * 2, S * 2), 0)
    sd = ImageDraw.Draw(shade)
    sd.ellipse((cx - 70, cy - 150, cx + 210, cy + 130), fill=200)
    shade = shade.filter(ImageFilter.GaussianBlur(40))
    dark = Image.new("RGBA", (S * 2, S * 2), (40, 20, 80, 255))
    body = Image.composite(Image.alpha_composite(body, Image.new("RGBA", body.size, (0, 0, 0, 0))), body, Image.new("L", body.size, 255))
    shaded = Image.composite(dark, body, shade)
    shaded.putalpha(body.split()[3])
    pl = Image.alpha_composite(pl, shaded)
    # anneau avant (moitié basse, par-dessus la planète)
    front = Image.new("RGBA", (S * 2, S * 2), (0, 0, 0, 0))
    fd = ImageDraw.Draw(front)
    fd.arc((cx - 230, cy - 62, cx + 230, cy + 62), 0, 180, fill=(220, 195, 255, 235), width=14)
    fd.arc((cx - 200, cy - 50, cx + 200, cy + 50), 0, 180, fill=(160, 120, 235, 170), width=8)
    pl = Image.alpha_composite(pl, front)
    glow = pl.split()[3].filter(ImageFilter.GaussianBlur(14)).point(lambda v: int(v * 0.5))
    halo = Image.new("RGBA", pl.size, (170, 120, 255, 0))
    halo.putalpha(glow)
    pl = Image.alpha_composite(halo, pl)
    pl.rotate(-14, resample=Image.BICUBIC).resize((256, 256), Image.LANCZOS).save(os.path.join(OUT, "planet.png"))


# ------------------------------------------------------------------ veines d'éclairs (image 3)

def veins():
    n = gen_fx.noise(W, H, 401, 5, 4)
    a = n.load()
    base = Image.new("RGB", (W, H))
    p = base.load()
    for y in range(H):
        v = y / (H - 1)
        for x in range(W):
            t = 0.25 + 0.4 * (a[x, y] - 0.5) + 0.25 * math.exp(-((v - 0.55) / 0.15) ** 2)
            p[x, y] = gen_fx.ramp([(0, (10, 11, 14)), (0.4, (30, 33, 40)), (0.7, (62, 66, 76)), (1, (110, 115, 128))], t)
    base = base.filter(ImageFilter.GaussianBlur(1.5))
    poles(base)
    save(base, "veins")
    r1 = ridged(gen_fx.noise(W, H, 402, 5, 6), 14).load()
    r2 = ridged(gen_fx.noise(W, H, 403, 5, 10), 18).load()
    fx = Image.new("RGB", (W, H))
    q = fx.load()
    for y in range(H):
        v = y / (H - 1)
        fade = smooth((0.62 - v) / 0.1) * smooth(v / 0.1)
        for x in range(W):
            k = min(1.0, r1[x, y] * 1.2 + r2[x, y] * 0.7) * fade
            q[x, y] = (int(215 * k), int(225 * k), int(245 * k))
    glow = fx.filter(ImageFilter.GaussianBlur(3))
    fx = Image.blend(fx, glow, 0.35)
    save(fx, "veins_fx")


# ------------------------------------------------------------------ nébuleuse violette (image 4)

def nebula():
    a = gen_fx.noise(W, H, 501, 6, 4).load()
    b = gen_fx.noise(W, H, 502, 5, 8).load()
    base = Image.new("RGB", (W, H))
    p = base.load()
    for y in range(H):
        v = y / (H - 1)
        for x in range(W):
            t = (a[x, y] - 0.5) * 2.2 + 0.45
            t = t * (0.7 + 0.6 * b[x, y])
            if v > 0.55:
                t *= 1 - 0.6 * (v - 0.55) / 0.45
            p[x, y] = gen_fx.ramp([(0, (5, 3, 10)), (0.35, (25, 12, 40)), (0.6, (70, 40, 110)), (0.85, (130, 90, 180)),
                                   (1, (190, 160, 230))], t)
    base = base.filter(ImageFilter.GaussianBlur(2.2))
    poles(base)
    save_stars(base, "nebula", 503, 260, 0.5)
    c = gen_fx.noise(W, H, 504, 6, 6).load()
    fx = Image.new("RGB", (W, H))
    q = fx.load()
    for y in range(H):
        v = y / (H - 1)
        fade = smooth((0.6 - v) / 0.15) * smooth(v / 0.12)
        for x in range(W):
            k = smooth((c[x, y] - 0.55) / 0.25) * fade
            q[x, y] = (int(120 * k), int(70 * k), int(170 * k))
    save(fx.filter(ImageFilter.GaussianBlur(3)), "nebula_fx")


# ------------------------------------------------------------------ orage (image 5)

def thunder():
    a = gen_fx.noise(W, H, 601, 6, 4).load()
    b = gen_fx.noise(W, H, 602, 4, 12).load()
    base = Image.new("RGB", (W, H))
    p = base.load()
    for y in range(H):
        v = y / (H - 1)
        for x in range(W):
            # nuages bas et lourds : plus sombres au zénith, bande claire près de l'horizon
            t = 0.30 + 0.85 * (a[x, y] - 0.5) * (0.6 + 0.8 * b[x, y]) + 0.3 * math.exp(-((v - 0.47) / 0.08) ** 2)
            t += 0.1 * (v - 0.2)
            p[x, y] = gen_fx.ramp([(0, (8, 12, 16)), (0.35, (25, 34, 42)), (0.6, (55, 70, 78)), (0.85, (100, 120, 125)),
                                   (1, (150, 170, 170))], t)
    base = base.filter(ImageFilter.GaussianBlur(1.6))
    poles(base)
    save(base, "thunder")
    # éclairs : quelques arcs ramifiés dans les nuages (flash additif)
    r = ridged(gen_fx.noise(W, H, 603, 5, 5), 22).load()
    mask = gen_fx.noise(W, H, 604, 3, 3).load()
    fx = Image.new("RGB", (W, H))
    q = fx.load()
    for y in range(H):
        v = y / (H - 1)
        fade = smooth((0.5 - v) / 0.12) * smooth((v - 0.08) / 0.1)
        for x in range(W):
            m = smooth((mask[x, y] - 0.45) / 0.15)
            k = min(1.0, r[x, y] * 2.4) * m * fade
            cloud = m * fade * 0.35
            q[x, y] = (int(170 * k + 60 * cloud), int(255 * k + 110 * cloud), int(225 * k + 100 * cloud))
    fx = Image.blend(fx, fx.filter(ImageFilter.GaussianBlur(5)), 0.5)
    save(fx, "thunder_fx")


if __name__ == "__main__":
    import sys
    for f in (cosmic, veins, nebula, thunder):
        if len(sys.argv) > 1 and f.__name__ not in sys.argv[1:]:
            continue
        f()
        print(f.__name__, "ok")
