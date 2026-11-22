#version 330

// AMD FidelityFX Super Resolution 1.0 - RCAS (Robust Contrast Adaptive Sharpening).
// Portage GLSL de l algorithme publie par AMD sous licence MIT (Copyright (c) 2021 Advanced Micro Devices, Inc.).
// SHARPNESS : en " stops ", 0 = nettete maximale.

uniform sampler2D InSampler;

out vec4 fragColor;

vec3 fetchC(ivec2 p) {
    ivec2 s = textureSize(InSampler, 0);
    return texelFetch(InSampler, clamp(p, ivec2(0), s - 1), 0).rgb;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 b = fetchC(p + ivec2(0, -1));
    vec3 d = fetchC(p + ivec2(-1, 0));
    vec3 e = fetchC(p);
    vec3 f = fetchC(p + ivec2(1, 0));
    vec3 h = fetchC(p + ivec2(0, 1));

    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));
    vec3 hitMin = mn4 / (4.0 * mx4 + 1.0 / 65536.0);
    vec3 hitMax = (1.0 - mx4) / (4.0 * mn4 - 4.0 - 1.0 / 65536.0);
    vec3 lobeRGB = max(-hitMin, hitMax);
    float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * exp2(-SHARPNESS);
    float rcpL = 1.0 / (4.0 * lobe + 1.0);
    fragColor = vec4(clamp((lobe * (b + d + h + f) + e) * rcpL, 0.0, 1.0), 1.0);
}
