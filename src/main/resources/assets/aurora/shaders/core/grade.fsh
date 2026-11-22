#version 330

// Filtres Paranoia : etalonnage couleur (prereglages), lueur douce des zones claires, vignette, nettete.
// PRESET : 0 Vivid, 1 Cinema, 2 Chaud, 3 Froid, 4 Noir & blanc, 5 Reve, 6 Nuit violette
// STRENGTH : 0..1, BLOOM : 0..1, VIGNETTE : 0..1, SHARPEN : 0..1

uniform sampler2D InSampler;

out vec4 fragColor;

vec3 fetchC(ivec2 p) {
    ivec2 s = textureSize(InSampler, 0);
    return texelFetch(InSampler, clamp(p, ivec2(0), s - 1), 0).rgb;
}

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 saturate3(vec3 c, float s) {
    return mix(vec3(luma(c)), c, s);
}

vec3 contrast(vec3 c, float k) {
    return (c - 0.5) * k + 0.5;
}

vec3 grade(vec3 c) {
#if PRESET == 0
    c = saturate3(c, 1.35);
    c = contrast(c, 1.12);
#elif PRESET == 1
    // cinema : courbe en S, ombres bleu-vert, hautes lumieres orangees
    float l = luma(c);
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.55);
    c += (1.0 - l) * vec3(-0.02, 0.015, 0.05) + l * vec3(0.05, 0.015, -0.04);
    c = saturate3(c, 0.95);
#elif PRESET == 2
    c *= vec3(1.08, 1.0, 0.86);
    c = saturate3(c, 1.12);
    c = contrast(c, 1.05);
#elif PRESET == 3
    c *= vec3(0.9, 1.0, 1.12);
    c = saturate3(c, 0.95);
    c = contrast(c, 1.06);
#elif PRESET == 4
    c = vec3(luma(c));
    c = contrast(c, 1.18);
#elif PRESET == 5
    c = mix(c, vec3(1.0, 0.92, 0.98), 0.08);
    c = saturate3(c, 1.15);
    c = pow(max(c, 0.0), vec3(0.92));
#elif PRESET == 6
    // nuit violette : ombres pourpres, ciel lavande
    float l = luma(c);
    c *= vec3(0.9, 0.78, 1.12);
    c += (1.0 - l) * vec3(0.06, 0.0, 0.1);
    c = saturate3(c, 1.15);
    c = contrast(c, 1.12);
#else
    // cursed : sombre, desature, froid, noirs ecrases, hautes lumieres argentees
    float l = luma(c);
    c = saturate3(c, 0.42);
    c *= vec3(0.86, 0.9, 1.04);
    c = c * c * (3.0 - 2.0 * c) * 0.55 + c * 0.45;
    c = max(c - 0.025, 0.0) * 0.92;
    c += smoothstep(0.65, 1.0, l) * vec3(0.04, 0.04, 0.07);
#endif
    return c;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InSampler, 0);
    vec3 src = fetchC(p);
    vec3 c = src;

#if SHARPEN_ON
    vec3 blur = (fetchC(p + ivec2(1, 0)) + fetchC(p - ivec2(1, 0)) + fetchC(p + ivec2(0, 1)) + fetchC(p - ivec2(0, 1))) * 0.25;
    c = c + (c - blur) * SHARPEN;
#endif

#if BLOOM_ON
    // lueur : moyenne des zones claires sur un anneau de 16 echantillons (une seule passe)
    vec3 glow = vec3(0.0);
    float rad = float(size.y) * 0.012;
    for (int i = 0; i < 16; i++) {
        float a = float(i) * 0.3927;
        float r = rad * (1.0 + float(i % 4) * 0.8);
        vec3 s = fetchC(p + ivec2(cos(a) * r, sin(a) * r));
        glow += max(s - 0.8, 0.0);
    }
    c += glow / 16.0 * 3.0 * BLOOM;
#endif

    vec3 graded = grade(c);
    c = mix(c, graded, STRENGTH);

#if VIGNETTE_ON
    vec2 uv = (vec2(p) + 0.5) / vec2(size) - 0.5;
    float v = smoothstep(0.85, 0.25, length(uv * vec2(1.0, 0.85)));
    c *= mix(1.0, v, VIGNETTE);
#endif

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
