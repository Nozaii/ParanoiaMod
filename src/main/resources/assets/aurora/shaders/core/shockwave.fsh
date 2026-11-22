#version 330

// Shockwaves: rings that sweep across the terrain. The depth buffer gives each pixel its world position,
// so the wave follows blocks exactly. Waves: xyz = origin relative to the camera, w = radius.

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform ShockInfo {
    mat4 InvProj;
    mat4 ViewToWorld;
    vec4 Color;
    vec4 Wave0;
    vec4 Wave1;
    vec4 Wave2;
    vec4 Wave3;
    vec4 Strength;
};

in vec2 texCoord;
out vec4 fragColor;

float band(vec3 w, vec4 wave, float s) {
    if (s <= 0.0) return 0.0;
    float d = length(w - wave.xyz) - wave.w;
    float width = 0.35 + wave.w * 0.07;
    float front = exp(-d * d / (width * width));
    float wake = d < 0.0 ? exp(d / (width * 3.0)) * 0.35 : 0.0;
    return (front + wake) * s;
}

void main() {
    vec2 size = vec2(textureSize(InSampler, 0));
    vec2 uv = gl_FragCoord.xy / size;
    float depth = texture(DepthSampler, uv).r;
    if (depth >= 1.0) {
        fragColor = texture(InSampler, uv);
        return;
    }
    // Color.a = 1 sous VulkanMod : textures rangees de haut en bas
    vec2 nuv = Color.a > 0.5 ? vec2(uv.x, 1.0 - uv.y) : uv;
    vec4 v = InvProj * vec4(nuv * 2.0 - 1.0, Color.a > 0.5 ? depth : depth * 2.0 - 1.0, 1.0);
    vec3 w = (ViewToWorld * vec4(v.xyz / v.w, 0.0)).xyz;
    float k = band(w, Wave0, Strength.x) + band(w, Wave1, Strength.y) + band(w, Wave2, Strength.z) + band(w, Wave3, Strength.w);
    k = clamp(k, 0.0, 1.0);
    vec2 bend = (uv - 0.5) * k * 0.006;
    vec3 base = texture(InSampler, uv + bend).rgb;
    fragColor = vec4(mix(base, base * 0.7 + Color.rgb * 0.65, k), 1.0);
}
