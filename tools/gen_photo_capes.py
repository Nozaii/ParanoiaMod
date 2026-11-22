"""Capes HD à partir d'images (1024x512, 16x vanilla : face 160x256).

Usage : python -I tools/gen_photo_capes.py <hypergamy.png> <johnpork.png>
Dessine aussi la zone elytra (22,0)-(46,22) pour que les elytra vanilla affichent l'image.
"""
import os
import sys

from PIL import Image, ImageFilter, ImageOps

K = 16
W, H = 64 * K, 32 * K
FW, FH = 10 * K, 16 * K
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                   "assets", "aurora", "textures", "cape")


def fit(img, w, h, bg):
    """Fait tenir l'image dans w x h sans la déformer, fond bg."""
    img = img.convert("RGBA")
    s = min(w / img.width, h / img.height)
    r = img.resize((max(1, round(img.width * s)), max(1, round(img.height * s))), Image.LANCZOS)
    out = Image.new("RGBA", (w, h), bg)
    out.paste(r, ((w - r.width) // 2, (h - r.height) // 2), r)
    return out


def cover(img, w, h):
    """Remplit w x h en recadrant au centre."""
    return ImageOps.fit(img.convert("RGBA"), (w, h), Image.LANCZOS, centering=(0.5, 0.5))


def assemble(name, face, inner, edge):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    e = Image.new("RGBA", (K, FH), edge)
    img.paste(e, (0, K))
    img.paste(e, (11 * K, K))
    t = Image.new("RGBA", (FW, K), edge)
    img.paste(t, (K, 0))
    img.paste(t, (11 * K, 0))
    img.paste(face, (K, K))
    img.paste(inner, (12 * K, K))
    # elytra : bloc 22..46 x 0..22 ; face extérieure de l'aile (24,2) 10x20, entourée des bords
    img.paste(Image.new("RGBA", (24 * K, 22 * K), edge), (22 * K, 0))
    img.paste(cover(face, 10 * K, 20 * K), (24 * K, 2 * K))
    img.save(os.path.join(OUT, name + ".png"))


def main(hyper_path, pork_path):
    hyper = Image.open(hyper_path).convert("RGBA")
    # titre + colonnes, sans le texte du bas
    hyper = hyper.crop((0, 0, hyper.width, round(hyper.height * 0.868)))
    face = fit(hyper, FW, FH, (255, 255, 255, 255))
    assemble("hypergamy", face, Image.new("RGBA", (FW, FH), (12, 12, 14, 255)), (20, 20, 24, 255))

    pork = Image.open(pork_path).convert("RGBA")
    face = cover(pork, FW, FH)
    inner = cover(pork, FW, FH).transpose(Image.FLIP_LEFT_RIGHT).filter(ImageFilter.GaussianBlur(10))
    assemble("johnpork", face, inner, (70, 40, 44, 255))
    print("ok ->", os.path.normpath(OUT))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
