#version 330

// Menu en 3D : chaque pixel de l ecran est renvoye dans le rectangle du panneau dessine a plat
// (homographie inverse). Dedans : le panneau ; a la place du panneau a plat : le monde capture avant
// l interface (assombri comme le fond du menu) ; ailleurs : l image normale.

// image apres l interface (panneau a plat)
uniform sampler2D InSampler;
// image avant l interface (monde seul)
uniform sampler2D PreSampler;

layout(std140) uniform Panel3dInfo {
    mat4 Hinv;
    vec4 Rect;
    vec4 Params;
};

out vec4 fragColor;

float roundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 p = gl_FragCoord.xy;
    ivec2 ip = ivec2(p);
    vec3 after = texelFetch(InSampler, ip, 0).rgb;
    bool inFlat = p.x >= Rect.x && p.x <= Rect.z && p.y >= Rect.y && p.y <= Rect.w;
    vec3 base = (inFlat ? texelFetch(PreSampler, ip, 0).rgb : after) * (1.0 - Params.y);

    vec3 hq = (Hinv * vec4(p, 1.0, 0.0)).xyz;
    if (hq.z > 0.0) {
        vec2 s = hq.xy / hq.z;
        vec2 c = (Rect.xy + Rect.zw) * 0.5, b = (Rect.zw - Rect.xy) * 0.5;
        float d = roundedBox(s - c, b, Params.z);
        if (d < 0.0) {
            vec3 panel = texture(InSampler, s / vec2(textureSize(InSampler, 0))).rgb;
            float k = Params.x * clamp(-d, 0.0, 1.0);
            base = mix(base, panel, k);
        }
    }
    fragColor = vec4(base, 1.0);
}
