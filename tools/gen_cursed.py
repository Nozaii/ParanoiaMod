"""Aperçus de la garde-robe : méduses (pet_*.png), ailes maudites (wings_maudites.png), lunettes néon.

Usage : python -I tools/gen_cursed.py
"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "aurora", "textures", "cosmetic")
S = 512


def glowed(img, color, amount=0.6, blur=18):
    a = img.split()[3].filter(ImageFilter.GaussianBlur(blur)).point(lambda v: int(min(255, v * amount * 1.5)))
    halo = Image.new("RGBA", img.size, color + (0,))
    halo.putalpha(a)
    return Image.alpha_composite(halo, img)


def jellyfish(name, col):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    light = tuple(int(c + (255 - c) * 0.55) for c in col)
    # tentacules
    for i in range(12):
        x0 = 256 + (i - 5.5) * 17
        pts = []
        for j in range(30):
            y = 250 + j * 8
            pts.append((x0 + math.sin(j * 0.35 + i) * (6 + j * 0.6), y))
        d.line(pts, fill=light + (150,), width=3)
    for i in range(4):
        pts = [(256 + (i - 1.5) * 14 + math.sin(j * 0.4 + i * 2) * 14, 240 + j * 6) for j in range(30)]
        d.line(pts, fill=light + (110,), width=9)
    # cloche translucide
    bell = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bell)
    bd.pieslice((116, 110, 396, 390), 180, 360, fill=col + (120,))
    bd.pieslice((140, 130, 372, 370), 180, 360, fill=light + (70,))
    for k in range(9):
        a = math.radians(180 + k * 22.5)
        bd.line([(256, 250), (256 + math.cos(a) * 140, 250 + math.sin(a) * 140)], fill=(255, 255, 255, 160), width=3)
    bd.arc((116, 110, 396, 390), 180, 360, fill=(255, 255, 255, 230), width=6)
    bd.line([(116, 250), (396, 250)], fill=(255, 255, 255, 200), width=6)
    img = Image.alpha_composite(img, bell)
    img = glowed(img, col)
    img.resize((256, 256), Image.LANCZOS).save(os.path.join(OUT, "pet_" + name + ".png"))


def cursed_wings():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # une aile (droite) : 3 lames en éventail depuis la base gauche
    for ang, ln, w in ((28, 400, 52), (42, 320, 42), (57, 240, 34)):
        a = math.radians(ang)
        dx, dy = math.sin(a), -math.cos(a)
        nx, ny = -dy, dx
        pts_l, pts_r = [], []
        for i in range(21):
            u = i / 20
            wid = w * math.sin(math.pi * u ** 0.55) * (1 - u * 0.2) + 2
            b = 40 * u * u
            cx, cy = 30 + dx * ln * u + nx * b, 470 + dy * ln * u + ny * b
            pts_l.append((cx - nx * wid, cy - ny * wid))
            pts_r.append((cx + nx * wid * 0.4, cy + ny * wid * 0.4))
        d.polygon(pts_l + pts_r[::-1], fill=(246, 244, 255, 255))
    img = glowed(img, (200, 184, 255), 0.8, 14)
    img.resize((256, 256), Image.LANCZOS).save(os.path.join(OUT, "wings_maudites.png"))


def glasses():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for x0 in (70, 276):
        d.rounded_rectangle((x0, 200, x0 + 166, 300), radius=18, fill=(250, 250, 255, 255), outline=(20, 20, 26, 255), width=10)
    d.rectangle((236, 215, 276, 232), fill=(20, 20, 26, 255))
    img = glowed(img, (230, 222, 255), 0.9, 16)
    img.resize((256, 256), Image.LANCZOS).save(os.path.join(OUT, "halo_hat_lunettes.png"))


for n, c in (("meduse", (201, 168, 255)), ("abysse", (95, 225, 232)), ("rose", (255, 143, 200)), ("spectre", (242, 240, 255))):
    jellyfish(n, c)
cursed_wings()
glasses()
print("ok")
