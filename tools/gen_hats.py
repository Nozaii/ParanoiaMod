"""Aperçus des chapeaux pour la garde-robe (textures/cosmetic/halo_hat_*.png, 256x256)."""
import os
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "aurora", "textures", "cosmetic")
S = 256


def canvas():
    return Image.new("RGBA", (S * 2, S * 2), (0, 0, 0, 0))


def save(img, name, glow=None):
    if glow:
        halo = img.split()[3].filter(ImageFilter.GaussianBlur(18))
        g = Image.new("RGBA", img.size, glow + (0,))
        g.putalpha(halo.point(lambda a: int(a * 0.55)))
        base = Image.alpha_composite(g, img)
    else:
        base = img
    base.resize((S, S), Image.LANCZOS).save(os.path.join(OUT, "halo_" + name + ".png"))


def top_hat():
    im = canvas()
    d = ImageDraw.Draw(im)
    d.ellipse((60, 330, 452, 410), fill=(28, 26, 34, 255))
    d.rectangle((150, 110, 362, 372), fill=(30, 28, 38, 255))
    d.ellipse((150, 90, 362, 130), fill=(48, 45, 58, 255))
    d.rectangle((150, 300, 362, 350), fill=(122, 77, 255, 255))
    d.rectangle((150, 110, 175, 372), fill=(52, 49, 64, 255))
    save(im, "hat_haut", (157, 124, 255))


def crown():
    im = canvas()
    d = ImageDraw.Draw(im)
    pts = [(90, 400), (90, 190), (160, 290), (256, 150), (352, 290), (422, 190), (422, 400)]
    d.polygon(pts, fill=(242, 193, 78, 255))
    d.rectangle((90, 360, 422, 400), fill=(255, 233, 168, 255))
    for x, y in ((90, 180), (256, 140), (422, 180)):
        d.ellipse((x - 18, y - 18, x + 18, y + 18), fill=(255, 246, 218, 255))
    for x, c in ((160, (224, 40, 60)), (256, (46, 123, 255)), (352, (46, 204, 113))):
        d.ellipse((x - 22, 300, x + 22, 344), fill=c + (255,))
    save(im, "hat_couronne", (255, 214, 94))


def witch():
    im = canvas()
    d = ImageDraw.Draw(im)
    d.ellipse((40, 340, 472, 420), fill=(43, 24, 64, 255))
    d.polygon([(140, 375), (372, 375), (330, 210), (380, 70), (250, 190)], fill=(56, 32, 84, 255))
    d.polygon([(142, 330), (370, 330), (365, 368), (147, 368)], fill=(157, 124, 255, 255))
    d.rectangle((232, 330, 280, 368), outline=(242, 193, 78, 255), width=8)
    save(im, "hat_sorciere", (157, 124, 255))


def horns():
    im = canvas()
    d = ImageDraw.Draw(im)
    for side in (-1, 1):
        pts = []
        for i in range(21):
            s = i / 20
            x = 256 + side * (70 + 110 * s + 30 * s * s)
            y = 400 - 280 * s + 40 * s ** 3
            pts.append((x, y, 34 * (1 - s) + 4))
        for x, y, r in pts:
            c = (int(106 - 86 * (y < 250)), 14, 24, 255)
            d.ellipse((x - r, y - r, x + r, y + r), fill=c)
    save(im, "hat_cornes", (255, 58, 26))


def cat():
    im = canvas()
    d = ImageDraw.Draw(im)
    for side in (-1, 1):
        x0, x1, xt = 256 + side * 40, 256 + side * 200, 256 + side * 150
        d.polygon([(x0, 400), (x1, 400), (xt, 130)], fill=(44, 42, 51, 255))
        d.polygon([(x0 + side * 34, 385), (x1 - side * 34, 385), (xt, 190)], fill=(255, 143, 200, 255))
    save(im, "hat_chat", (255, 143, 200))


def pharaoh():
    im = canvas()
    d = ImageDraw.Draw(im)
    blue, gold = (30, 79, 168, 255), (232, 185, 49, 255)
    d.polygon([(120, 120), (392, 120), (430, 470), (82, 470)], fill=blue)
    for i in range(9):
        y = 130 + i * 38
        d.rectangle((90, y, 422, y + 16), fill=gold)
    d.rectangle((160, 170, 352, 470), fill=(0, 0, 0, 0))
    d.rectangle((120, 120, 392, 175), fill=gold)
    d.polygon([(256, 60), (238, 122), (274, 122)], fill=gold)
    d.ellipse((244, 128, 268, 152), fill=(224, 40, 60, 255))
    save(im, "hat_pharaon", (255, 214, 94))


for f in (top_hat, crown, witch, horns, cat, pharaoh):
    f()
print("ok")
