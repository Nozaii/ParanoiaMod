#version 330

// AMD FidelityFX Super Resolution 1.0 - EASU (Edge Adaptive Spatial Upsampling).
// Portage GLSL de l algorithme publie par AMD sous licence MIT (Copyright (c) 2021 Advanced Micro Devices, Inc.).

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

vec3 fetchC(ivec2 p) {
    ivec2 s = textureSize(InSampler, 0);
    return texelFetch(InSampler, clamp(p, ivec2(0), s - 1), 0).rgb;
}

float luma2(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

void easuSet(inout vec2 dir, inout float len, vec2 pp, float w, float lA, float lB, float lC, float lD, float lE) {
    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = 1.0 / max(max(abs(dc), abs(cb)), 1.0 / 65536.0);
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    lenX *= lenX;
    len += lenX * w;

    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = 1.0 / max(max(abs(ec), abs(ca)), 1.0 / 65536.0);
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    lenY *= lenY;
    len += lenY * w;
}

void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * (-dir.y) + off.y * dir.x) * len;
    float d2 = min(v.x * v.x + v.y * v.y, clp);
    float wB = 2.0 / 5.0 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

void main() {
    vec2 inSize = vec2(textureSize(InSampler, 0));
    vec2 pp = texCoord * inSize - 0.5;
    vec2 fp = floor(pp);
    pp -= fp;
    ivec2 ip = ivec2(fp);

    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec3 b = fetchC(ip + ivec2(0, -1)), c = fetchC(ip + ivec2(1, -1));
    vec3 e = fetchC(ip + ivec2(-1, 0)), f = fetchC(ip), g = fetchC(ip + ivec2(1, 0)), h = fetchC(ip + ivec2(2, 0));
    vec3 i = fetchC(ip + ivec2(-1, 1)), j = fetchC(ip + ivec2(0, 1)), k = fetchC(ip + ivec2(1, 1)), l = fetchC(ip + ivec2(2, 1));
    vec3 n = fetchC(ip + ivec2(0, 2)), o = fetchC(ip + ivec2(1, 2));

    float bL = luma2(b), cL = luma2(c), eL = luma2(e), fL = luma2(f), gL = luma2(g), hL = luma2(h);
    float iL = luma2(i), jL = luma2(j), kL = luma2(k), lL = luma2(l), nL = luma2(n), oL = luma2(o);

    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, pp, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    easuSet(dir, len, pp, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
    easuSet(dir, len, pp, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
    easuSet(dir, len, pp, pp.x * pp.y, gL, jL, kL, lL, oL);

    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool zro = dirR < 1.0 / 32768.0;
    dirR = zro ? 1.0 : inversesqrt(dirR);
    dir.x = zro ? 1.0 : dir.x;
    dir *= dirR;

    len = len * 0.5;
    len *= len;
    float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = 1.0 / lob;

    vec3 min4 = min(min(f, g), min(j, k));
    vec3 max4 = max(max(f, g), max(j, k));

    vec3 aC = vec3(0.0);
    float aW = 0.0;
    easuTap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
    easuTap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
    easuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
    easuTap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
    easuTap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
    easuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
    easuTap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
    easuTap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
    easuTap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
    easuTap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
    easuTap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
    easuTap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);

    fragColor = vec4(min(max4, max(min4, aC / aW)), 1.0);
}
