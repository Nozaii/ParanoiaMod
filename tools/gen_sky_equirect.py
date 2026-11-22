# Genere sky_fx_equirect.fsh depuis sky_fx.fsh (meme ciel, rendu dans une texture equirectangulaire
# pour les packs de shaders Iris). A relancer apres toute modification de sky_fx.fsh.
import os
d = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "aurora", "shaders", "core")
src = open(os.path.join(d, "sky_fx.fsh"), encoding="utf8").read()
body = src[src.index("float hash(vec3 p)"):src.index("void main()")]
out = """#version 330

// GENERE par tools/gen_sky_equirect.py depuis sky_fx.fsh : ne pas modifier a la main.
// Ciel procedural rendu dans une texture equirectangulaire (u = longitude, v = colatitude).

layout(std140) uniform SkyInfo {
    vec4 SkyParams;
};

in vec2 texCoord;
out vec4 fragColor;

""" + body + """void main() {
    float th = texCoord.x * 6.2831853;
    float ph = texCoord.y * 3.14159265;
    vec3 d = vec3(sin(ph) * cos(th), cos(ph), sin(ph) * sin(th));
    vec3 col = sky(d, SkyParams.x, int(SkyParams.y + 0.5));
    fragColor = vec4(col, 1.0);
}
"""
open(os.path.join(d, "sky_fx_equirect.fsh"), "w", encoding="utf8", newline="\n").write(out)
print("ok")
