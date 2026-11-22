#version 330

// Motion blur de camera : chaque pixel est reprojete dans l image precedente grace a la profondeur,
// puis floute le long de son deplacement a l ecran. Rendu avant la main (elle reste nette).

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform BlurInfo {
    mat4 Reproj;
    vec4 Params;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 size = vec2(textureSize(InSampler, 0));
    vec2 uv = gl_FragCoord.xy / size;
    float d = texture(DepthSampler, uv).r;
    // Params.w = 1 sous VulkanMod : textures rangees de haut en bas
    vec2 nuv = Params.w > 0.5 ? vec2(uv.x, 1.0 - uv.y) : uv;
    vec4 prev = Reproj * vec4(nuv * 2.0 - 1.0, Params.w > 0.5 ? d : d * 2.0 - 1.0, 1.0);
    vec3 base = texture(InSampler, uv).rgb;
    if (prev.w <= 0.0) {
        fragColor = vec4(base, 1.0);
        return;
    }
    vec2 prevUv = prev.xy / prev.w * 0.5 + 0.5;
    if (Params.w > 0.5) prevUv.y = 1.0 - prevUv.y;
    vec2 vel = (uv - prevUv) * Params.x;
    float len = length(vel);
    float maxLen = Params.y;
    if (len > maxLen) vel *= maxLen / len;
    if (len * size.x < 0.5) {
        fragColor = vec4(base, 1.0);
        return;
    }
    // bruit entrelace : decale les echantillons d un pixel a l autre (pas de bandes visibles)
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    int n = int(Params.z);
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 16; i++) {
        if (i >= n) break;
        float t = (float(i) + noise) / float(n) - 0.5;
        vec2 suv = clamp(uv + vel * t, vec2(0.0), vec2(1.0));
        // poids en cloche : le centre compte plus, bords doux
        float w = 1.0 - abs(t) * 1.2;
        acc += texture(InSampler, suv).rgb * w;
        wsum += w;
    }
    fragColor = vec4(acc / wsum, 1.0);
}
