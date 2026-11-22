"""Génère les icônes lisses (style trait arrondi, grille 24) en PNG blanc + .mcmeta (filtrage linéaire).

Usage : python -I tools/gen_icons.py
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw

SS = 512  # résolution de travail (supersampling)
SIZES = {"": 64, "_s": 32}
STROKE = 2.0
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                   "assets", "aurora", "textures", "icon")


class Pen:
    def __init__(self):
        self.img = Image.new("L", (SS, SS), 0)
        self.d = ImageDraw.Draw(self.img)
        self.k = SS / 24.0
        self.w = STROKE * self.k

    def p(self, x, y):
        return (x * self.k, y * self.k)

    def dot(self, x, y, r=None):
        r = (r if r is not None else STROKE / 2) * self.k
        cx, cy = self.p(x, y)
        self.d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)

    def line(self, *pts, closed=False):
        pts = list(pts)
        if closed:
            pts.append(pts[0])
        sp = [self.p(*q) for q in pts]
        self.d.line(sp, fill=255, width=int(round(self.w)), joint="curve")
        for q in pts:
            self.dot(*q)

    def arc(self, cx, cy, r, a0, a1, n=64):
        """Arc de a0 à a1 (degrés, 0 = droite, sens horaire écran)."""
        pts = []
        for i in range(n + 1):
            a = math.radians(a0 + (a1 - a0) * i / n)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
        self.line(*pts)
        return pts

    def circle(self, cx, cy, r):
        self.arc(cx, cy, r, 0, 360, 96)

    def ellipse(self, cx, cy, rx, ry):
        pts = [(cx + rx * math.cos(math.tau * i / 96), cy + ry * math.sin(math.tau * i / 96)) for i in range(97)]
        self.line(*pts)

    def rrect(self, x, y, w, h, r):
        pts = []
        for cx, cy, a in ((x + w - r, y + r, -90), (x + w - r, y + h - r, 0), (x + r, y + h - r, 90), (x + r, y + r, 180)):
            for i in range(17):
                t = math.radians(a + 90 * i / 16)
                pts.append((cx + r * math.cos(t), cy + r * math.sin(t)))
        self.line(*pts, closed=True)

    def quad(self, p0, p1, p2, n=32):
        return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
                 (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1])
                for t in (i / n for i in range(n + 1))]

    def fill_circle(self, cx, cy, r):
        self.dot(cx, cy, r)


def shield(p):
    left = p.quad((4, 13), (4.6, 19.2), (12, 22))
    right = p.quad((12, 22), (19.4, 19.2), (20, 13))
    top_r = p.quad((20, 6), (15.5, 5.4), (12, 2.4))
    top_l = p.quad((12, 2.4), (8.5, 5.4), (4, 6))
    p.line(*([(4, 6)] + left + right + [(20, 6)] + top_r + top_l), closed=True)
    p.line((9, 12), (11, 14), (15, 10))


def hud(p):
    p.rrect(3, 3, 18, 18, 2.5)
    p.line((3, 9), (21, 9))
    p.line((9, 21), (9, 9))


def wand(p):
    p.line((3, 21), (14, 10))
    p.line((13, 7.5), (16.5, 11))
    for cx, cy, s in ((18.5, 5.5, 3.2), (8, 4.5, 2.0), (19.5, 15, 2.0)):
        p.line((cx, cy - s), (cx, cy + s))
        p.line((cx - s, cy), (cx + s, cy))


def chevrons(p):
    p.line((6, 17), (11, 12), (6, 7))
    p.line((13, 17), (18, 12), (13, 7))


def sliders(p):
    p.line((21, 5), (15, 5)); p.line((11, 5), (3, 5)); p.line((15, 2.5), (15, 7.5))
    p.line((21, 12), (11, 12)); p.line((7, 12), (3, 12)); p.line((7, 9.5), (7, 14.5))
    p.line((21, 19), (17, 19)); p.line((13, 19), (3, 19)); p.line((17, 16.5), (17, 21.5))


def user(p):
    p.circle(12, 7, 4)
    p.line(*([(5, 21), (5, 19)] + p.quad((5, 19), (5, 15), (9, 15)) + [(15, 15)] + p.quad((15, 15), (19, 15), (19, 19)) + [(19, 21)]))


def search(p):
    p.circle(11, 11, 7.5)
    p.line((21, 21), (16.4, 16.4))


def users(p):
    p.circle(9, 7, 4)
    p.line(*([(2, 21), (2, 19)] + p.quad((2, 19), (2, 15), (6, 15)) + [(12, 15)] + p.quad((12, 15), (16, 15), (16, 19)) + [(16, 21)]))
    p.arc(16, 7, 4, -75, 75, 24)
    p.line(*(p.quad((19, 15.2), (22, 16), (22, 19)) + [(22, 21)]))


def layers(p):
    p.line((12, 2), (22, 7), (12, 12), (2, 7), closed=True)
    p.line((2, 12), (12, 17), (22, 12))
    p.line((2, 17), (12, 22), (22, 17))


def chevron_down(p):
    p.line((6, 9), (12, 15), (18, 9))


def keyboard(p):
    p.rrect(2, 5, 20, 14, 2.5)
    for x in (6, 10, 14, 18):
        p.dot(x, 9, 1.1)
    for x in (8, 12, 16):
        p.dot(x, 12.5, 1.1)
    p.line((7, 15.8), (17, 15.8))


def totem(p):
    p.circle(12, 6, 3.5)
    p.line((4.5, 12), (19.5, 12))
    p.rrect(8.5, 10, 7, 11.5, 2)
    p.dot(10.6, 5.6, 0.9); p.dot(13.4, 5.6, 0.9)


def gauge(p):
    p.arc(12, 14, 9.5, 150, 390, 72)
    p.line((12, 14), (16, 9.5))


def mouse(p):
    p.rrect(5.5, 2, 13, 20, 6.5)
    p.line((12, 6), (12, 10))


def pin(p):
    top = p.arc(12, 10, 8, 180, 360, 48)
    right = p.quad((20, 10), (19.6, 16.5), (12, 22))
    left = p.quad((12, 22), (4.4, 16.5), (4, 10))
    p.line(*(top + right + left))
    p.circle(12, 10, 3)


def wifi(p):
    for r in (4.5, 9, 13.5):
        p.arc(12, 20.5, r, 225, 315, 32)
    p.fill_circle(12, 20.2, 1.4)


def flask(p):
    p.line((10, 2), (10, 9.5), (4.7, 20.3), (5.7, 22), (18.3, 22), (19.3, 20.3), (14, 9.5), (14, 2))
    p.line((8.5, 2), (15.5, 2))
    p.line((7, 16), (17, 16))


def shirt(p):
    neck = p.quad((8, 2.2), (12, 6.5), (16, 2.2))
    p.line(*(neck + [(20.5, 3.6), (21.7, 9.5), (18, 10), (18, 20)] + p.quad((18, 20), (18, 22), (16, 22))
             + [(8, 22)] + p.quad((8, 22), (6, 22), (6, 20)) + [(6, 10), (2.3, 9.5), (3.5, 3.6)]), closed=True)


def clock(p):
    p.circle(12, 12, 10)
    p.line((12, 6), (12, 12), (16, 14))


def globe(p):
    p.circle(12, 12, 10)
    p.ellipse(12, 12, 4.5, 10)
    p.line((2, 12), (22, 12))


def sun(p):
    p.circle(12, 12, 4)
    for i in range(8):
        a = math.tau * i / 8
        p.line((12 + 7.2 * math.cos(a), 12 + 7.2 * math.sin(a)), (12 + 9.8 * math.cos(a), 12 + 9.8 * math.sin(a)))


def eye(p):
    up = p.quad((2, 12), (12, 2.5), (22, 12))
    down = p.quad((22, 12), (12, 21.5), (2, 12))
    p.line(*(up + down))
    p.circle(12, 12, 3)


def list_(p):
    for y in (6, 12, 18):
        p.line((8.5, y), (21, y))
        p.dot(3.5, y, 1.3)


def skull(p):
    head = p.arc(12, 10, 8, 135, 405, 72)
    p.line((8, 20), *head[:1])
    p.line(head[-1], (16, 20))
    p.line((8, 17.5), (8, 22), (16, 22), (16, 17.5))
    p.fill_circle(9, 11.5, 1.6)
    p.fill_circle(15, 11.5, 1.6)


def arrow_down(p):
    p.line((12, 4), (12, 19))
    p.line((5, 12), (12, 19), (19, 12))


def ruler(p):
    c, s = math.cos(math.radians(-45)), math.sin(math.radians(-45))

    def rot(x, y):
        return (12 + x * c - y * s, 12 + x * s + y * c)
    p.line(rot(-11, -4), rot(11, -4), rot(11, 4), rot(-11, 4), closed=True)
    for x in (-6, -2, 2, 6):
        p.line(rot(x, -4), rot(x, -1 if x in (-2, 6) else 0.5))


def sword(p):
    p.line((14.5, 17.5), (3, 6), (3, 3), (6, 3), (17.5, 14.5))
    p.line((13, 19), (19, 13))
    p.line((16, 16), (20, 20))
    p.line((19, 21), (21, 19))


def crosshair(p):
    p.circle(12, 12, 9.5)
    p.line((22, 12), (17.5, 12)); p.line((6.5, 12), (2, 12))
    p.line((12, 6.5), (12, 2)); p.line((12, 22), (12, 17.5))


def heart(p):
    pts = []
    for i in range(121):
        t = math.tau * i / 120
        x = 16 * math.sin(t) ** 3
        y = -(13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t))
        pts.append((12 + x * 0.6, 12.3 + y * 0.6))
    p.line(*pts)


def move(p):
    p.line((12, 2), (12, 22)); p.line((2, 12), (22, 12))
    p.line((9, 5), (12, 2), (15, 5)); p.line((9, 19), (12, 22), (15, 19))
    p.line((5, 9), (2, 12), (5, 15)); p.line((19, 9), (22, 12), (19, 15))


def gear(p):
    p.circle(12, 12, 3)
    pts = []
    for i in range(8 * 12 + 1):
        a = math.tau * i / 96
        k = (i % 12) / 12
        r = 9.6 if 0.12 < k < 0.5 else 7.4
        pts.append((12 + r * math.cos(a), 12 + r * math.sin(a)))
    p.line(*pts)


def lock(p):
    p.rrect(4, 11, 16, 11, 2.5)
    p.arc(12, 9, 4.5, 180, 360, 24)
    p.line((7.5, 9), (7.5, 11)); p.line((16.5, 9), (16.5, 11))


def palette(p):
    p.circle(12, 12, 10)
    for x, y in ((8, 8.5), (12.5, 6.5), (16.5, 9.5), (7.5, 13.5)):
        p.fill_circle(x, y, 1.4)
    p.fill_circle(15, 16, 2.2)


def monitor(p):
    p.rrect(2, 3, 20, 14, 2)
    p.line((8, 21), (16, 21)); p.line((12, 17), (12, 21))


def info(p):
    p.circle(12, 12, 10)
    p.line((12, 16), (12, 11.5))
    p.fill_circle(12, 8, 1.3)


def arrow_left(p):
    p.line((19, 12), (5, 12))
    p.line((12, 19), (5, 12), (12, 5))


def check(p):
    p.line((4.5, 12.5), (9.5, 17.5), (19.5, 7))


def cape(p):
    p.line((7, 3), (17, 3), (19, 21), (12, 18.5), (5, 21), closed=True)
    p.line((7, 3), (12, 6), (17, 3))


def gem(p):
    p.line((6, 3), (18, 3), (22, 9), (12, 21.5), (2, 9), closed=True)
    p.line((2, 9), (22, 9))
    p.line((9, 3), (7.5, 9), (12, 21.5), (16.5, 9), (15, 3))


def gift(p):
    p.rrect(3, 8, 18, 5, 1.2)
    p.rrect(4.5, 13, 15, 8.5, 1.5)
    p.line((12, 8), (12, 21.5))
    p.line(*(p.quad((12, 8), (8, 2), (6.5, 5.5)) + p.quad((6.5, 5.5), (6, 8), (12, 8))))
    p.line(*(p.quad((12, 8), (16, 2), (17.5, 5.5)) + p.quad((17.5, 5.5), (18, 8), (12, 8))))


def bag(p):
    p.line((5, 7), (19, 7), (20.5, 21), (3.5, 21), closed=True)
    p.arc(12, 7, 3.5, 180, 360, 24)


def sparkles(p):
    def star(cx, cy, r):
        pts = []
        for i in range(4):
            a = math.tau * i / 4 - math.pi / 2
            b = a + math.pi / 4
            pts += [(cx + r * math.cos(a), cy + r * math.sin(a)), (cx + r * 0.28 * math.cos(b), cy + r * 0.28 * math.sin(b))]
        p.line(*pts, closed=True)
    star(10, 13, 8)
    star(19, 5, 3.2)


def eye_logo(p):
    eye(p)
    p.fill_circle(12, 12, 1.4)


def bolt(p):
    p.line((13, 2), (4, 14), (12, 14), (11, 22), (20, 10), (12, 10), closed=True)


def _star_pts(r_out=10, r_in=4.6):
    pts = []
    for i in range(10):
        a = math.tau * i / 10 - math.pi / 2
        r = r_out if i % 2 == 0 else r_in
        pts.append((12 + r * math.cos(a), 12.6 + r * math.sin(a)))
    return pts


def star(p):
    p.line(*_star_pts(), closed=True)


def star_fill(p):
    pts = _star_pts()
    p.line(*pts, closed=True)
    p.d.polygon([p.p(*q) for q in pts], fill=255)


def image(p):
    p.rrect(3, 4, 18, 16, 2.5)
    p.line((3.5, 17), (8.5, 11.5), (12.5, 15.5), (15.5, 12.5), (20.5, 17.5))
    p.dot(16, 8.5, 1.7)


def copy_(p):
    p.rrect(9, 9, 12, 12, 2)
    p.line((15.5, 4.5), (6.5, 4.5))
    p.arc(6.5, 6.5, 2, 180, 270)
    p.line((4.5, 6.5), (4.5, 15.5))


def download(p):
    p.line((12, 3), (12, 15))
    p.line((7.5, 10.5), (12, 15), (16.5, 10.5))
    p.line((3.5, 16), (3.5, 20), (20.5, 20), (20.5, 16))


def share(p):
    p.line((8, 7), (5, 7), (5, 20.5), (19, 20.5), (19, 7), (16, 7))
    p.line((12, 14.5), (12, 2.5))
    p.line((8.5, 6), (12, 2.5), (15.5, 6))


def pencil(p):
    p.line((4, 20), (4.6, 15.8), (16.5, 3.9), (20.1, 7.5), (8.2, 19.4), closed=True)
    p.line((14, 6.4), (17.6, 10))


def crop(p):
    p.line((7, 2), (7, 17), (22, 17))
    p.line((2, 7), (17, 7), (17, 22))


def undo(p):
    p.line((8, 4.5), (3, 9.5), (8, 14.5))
    p.line((3, 9.5), (14.5, 9.5))
    p.arc(14.5, 15, 5.5, 270, 450)
    p.line((14.5, 20.5), (9, 20.5))


def redo(p):
    p.line((16, 4.5), (21, 9.5), (16, 14.5))
    p.line((21, 9.5), (9.5, 9.5))
    p.arc(9.5, 15, 5.5, 90, 270)
    p.line((9.5, 20.5), (15, 20.5))


def trash(p):
    p.line((4, 6.5), (20, 6.5))
    p.line((9, 6.5), (9, 3.5), (15, 3.5), (15, 6.5))
    p.line((6, 6.5), (7, 20.5), (17, 20.5), (18, 6.5))
    p.line((10, 10.5), (10, 16.5))
    p.line((14, 10.5), (14, 16.5))


def heart_fill(p):
    pts = []
    for i in range(121):
        t = math.tau * i / 120
        x = 16 * math.sin(t) ** 3
        y = -(13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t))
        pts.append((12 + x * 0.6, 12.3 + y * 0.6))
    p.line(*pts)
    p.d.polygon([p.p(*q) for q in pts], fill=255)


def folder(p):
    p.line((3, 6), (3, 19.5), (21, 19.5), (21, 8.5), (11.5, 8.5), (9.5, 6), closed=True)


def _grid(p, n):
    s = 18 / n
    gap = 1.2
    for i in range(n):
        for j in range(n):
            x, y = 3 + i * s, 3 + j * s
            p.rrect(x + gap / 2, y + gap / 2, s - gap, s - gap, 1)


def grid_large(p):
    _grid(p, 2)


def grid_medium(p):
    _grid(p, 3)


def grid_small(p):
    _grid(p, 4)


ICONS = {
    "shield": shield, "hud": hud, "wand": wand, "chevrons": chevrons, "sliders": sliders,
    "user": user, "search": search, "users": users, "layers": layers, "chevron_down": chevron_down,
    "keyboard": keyboard, "totem": totem, "gauge": gauge, "mouse": mouse, "pin": pin, "wifi": wifi,
    "flask": flask, "shirt": shirt, "clock": clock, "globe": globe, "sun": sun, "eye": eye,
    "list": list_, "skull": skull, "arrow_down": arrow_down, "ruler": ruler, "sword": sword,
    "crosshair": crosshair, "heart": heart, "move": move, "gear": gear, "lock": lock, "palette": palette,
    "monitor": monitor, "info": info, "arrow_left": arrow_left, "check": check, "cape": cape, "gem": gem,
    "gift": gift, "bag": bag, "sparkles": sparkles, "eye_logo": eye_logo, "bolt": bolt, "star": star, "star_fill": star_fill,
    "image": image, "copy": copy_, "download": download, "share": share, "pencil": pencil, "crop": crop, "undo": undo,
    "redo": redo, "trash": trash, "heart_fill": heart_fill, "folder": folder, "grid_large": grid_large,
    "grid_medium": grid_medium, "grid_small": grid_small,
}


def main():
    os.makedirs(OUT, exist_ok=True)
    meta = json.dumps({"texture": {"blur": True, "clamp": True}})
    for name, fn in ICONS.items():
        pen = Pen()
        fn(pen)
        for suffix, size in SIZES.items():
            alpha = pen.img.resize((size, size), Image.LANCZOS)
            out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
            out.putalpha(alpha)
            path = os.path.join(OUT, f"{name}{suffix}.png")
            out.save(path)
            with open(path + ".mcmeta", "w") as f:
                f.write(meta)
    print(f"{len(ICONS)} icônes -> {os.path.normpath(OUT)}")


if __name__ == "__main__":
    sys.exit(main())
