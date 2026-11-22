#version 330

// Sol miroir : reflets dans l ecran (screen-space reflections) sur les surfaces horizontales,
// comme un carrelage mouille. Lit la couleur et la profondeur du monde (avant la main).

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform SsrInfo {
    mat4 Proj;
    mat4 InvProj;
    vec4 Up;
    vec4 Params;
    vec4 Params2;
    mat4 ViewToWorld;
    vec4 CamPos;
    vec4 Puddle;
};

in vec2 texCoord;
out vec4 fragColor;

// Puddle.w = 1 sous VulkanMod : les textures y sont rangees de haut en bas (OpenGL : de bas en haut)
vec2 flipY(vec2 uv) {
    return Puddle.w > 0.5 ? vec2(uv.x, 1.0 - uv.y) : uv;
}

vec3 viewPos(vec2 uv, float d) {
    // VulkanMod garde la projection OpenGL : la profondeur stockee est directement z (et non (z + 1) / 2)
    vec4 v = InvProj * vec4(flipY(uv) * 2.0 - 1.0, Puddle.w > 0.5 ? d : d * 2.0 - 1.0, 1.0);
    return v.xyz / v.w;
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float fbm(vec2 p) {
    return vnoise(p) * 0.55 + vnoise(p * 2.13 + 17.0) * 0.3 + vnoise(p * 4.7 - 9.0) * 0.15;
}

/** Gouttes de pluie : anneaux qui s elargissent, un par case de 0,7 bloc, decales dans le temps. */
vec2 ripples(vec2 w, float t) {
    vec2 acc = vec2(0.0);
    for (int k = 0; k < 2; k++) {
        vec2 g = w / 0.7 + float(k) * 0.37;
        vec2 cell = floor(g), f = fract(g) - 0.5;
        float h = hash(cell + float(k) * 13.1);
        vec2 c = (vec2(hash(cell + 3.1), hash(cell + 7.7)) - 0.5) * 0.5;
        float life = fract(t * 0.9 + h);
        vec2 dv = f - c;
        float dd = length(dv);
        float ring = exp(-pow((dd - life * 0.45) * 22.0, 2.0)) * (1.0 - life);
        acc += (dd > 0.001 ? dv / dd : vec2(0.0)) * ring;
    }
    return acc;
}

void main() {
    vec2 size = vec2(textureSize(InSampler, 0));
    vec2 uv = gl_FragCoord.xy / size;
    vec4 base = texture(InSampler, uv);
    float d = texture(DepthSampler, uv).r;
    if (d >= 1.0) {
        fragColor = base;
        return;
    }
    vec3 P = viewPos(uv, d);
    // normale depuis les voisins (plus stable que dFdx sur les bords des blocs)
    vec2 px = 1.0 / size;
    vec3 Px = viewPos(uv + vec2(px.x, 0.0), texture(DepthSampler, uv + vec2(px.x, 0.0)).r);
    vec3 Py = viewPos(uv + vec2(0.0, px.y), texture(DepthSampler, uv + vec2(0.0, px.y)).r);
    vec3 N = normalize(cross(Px - P, Py - P));
    if (dot(N, -P) < 0.0) N = -N;
    float floorness = dot(N, Up.xyz);
    if (floorness < 0.92 || length(P) > Params.z) {
        fragColor = base;
        return;
    }
    float t = Params.w;
    // flaques (pluie) : masque de bruit dans le monde, ondes des gouttes, sol assombri par l eau
    float wet = Puddle.y, puddle = 0.0;
    vec3 rippleView = vec3(0.0);
    if (wet > 0.001) {
        vec3 W = CamPos.xyz + (ViewToWorld * vec4(P, 0.0)).xyz;
        puddle = smoothstep(Puddle.x, Puddle.x + 0.07, fbm(W.xz * 0.11)) * wet;
        vec2 r = Puddle.z > 0.001 ? ripples(W.xz, CamPos.w) * Puddle.z : vec2(0.0);
        rippleView = (transpose(ViewToWorld) * vec4(r.x, 0.0, r.y, 0.0)).xyz;
        base.rgb *= 1.0 - 0.18 * wet - 0.25 * puddle;
    }
    // rugosite : micro-desordre fixe (pas de bandes, pas de scintillement), quasi nul dans l eau
    vec2 j = vec2(hash(floor(P.xz * 16.0)), hash(floor(P.zx * 16.0) + 7.0)) - 0.5;
    float rough = mix(Params.y, 0.02, puddle);
    vec3 Nr = normalize(Up.xyz + (transpose(ViewToWorld) * vec4(j.x, 0.0, j.y, 0.0)).xyz * rough * 0.06 + rippleView * 0.35);
    vec3 V = normalize(P);
    vec3 R = normalize(reflect(V, Nr));

    // marche le long du rayon reflechi, en espace camera, avec un pas qui grandit
    float stepLen = Params2.x;
    vec3 Q = P;
    vec2 hitUv = vec2(-1.0);
    vec2 skyUv = vec2(-1.0);
    float skyHit = 1.0;
    float travelled = 0.0;
    for (int i = 0; i < 22; i++) {
        float s = stepLen * (1.0 + float(i) * 0.42);
        Q += R * s;
        travelled += s;
        vec4 c = Proj * vec4(Q, 1.0);
        vec2 q = flipY(c.xy / c.w * 0.5 + 0.5);
        if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0 || c.w <= 0.0) break;
        float sd = texture(DepthSampler, q).r;
        if (sd >= 1.0) { skyUv = q; break; }
        vec3 S = viewPos(q, sd);
        float diff = S.z - Q.z; // > 0 : le rayon est passe derriere la surface vue
        if (diff > 0.0 && diff < Params2.y * (1.0 + travelled * 0.03) && sd < 1.0) {
            // affinage binaire
            vec3 a = Q - R * s, b = Q;
            for (int k = 0; k < 3; k++) {
                vec3 m = (a + b) * 0.5;
                vec4 cm = Proj * vec4(m, 1.0);
                vec2 qm = flipY(cm.xy / cm.w * 0.5 + 0.5);
                vec3 Sm = viewPos(qm, texture(DepthSampler, qm).r);
                if (Sm.z - m.z > 0.0) b = m;
                else a = m;
            }
            vec4 cb = Proj * vec4(b, 1.0);
            hitUv = flipY(cb.xy / cb.w * 0.5 + 0.5);
            break;
        }
    }
    if (hitUv.x < 0.0) {
        if (skyUv.x < 0.0 && puddle > 0.01 && dot(R, Up.xyz) > 0.0) {
            // flaque : le rayon sort de l ecran vers le ciel -> on prend le ciel au bord de l image
            vec4 cf = Proj * vec4(P + R * 400.0, 1.0);
            if (cf.w > 0.0) skyUv = clamp(flipY(cf.xy / cf.w * 0.5 + 0.5), vec2(0.002), vec2(0.998));
        }
        if (skyUv.x < 0.0) {
            fragColor = base;
            return;
        }
        hitUv = skyUv; // le rayon part vers le ciel : on reflete le ciel (plus discret hors des flaques)
        skyHit = mix(0.45, 0.9, puddle);
    }
    vec3 refl = texture(InSampler, hitUv).rgb;
    // leger flou de la reflexion (rugosite)
    if (Params.y > 0.05 || skyHit < 1.0) {
        vec2 o = px * (1.5 + Params.y * 4.0 + (1.0 - skyHit) * 6.0);
        refl = (refl * 2.0 + texture(InSampler, hitUv + vec2(o.x, 0.0)).rgb + texture(InSampler, hitUv - vec2(o.x, 0.0)).rgb
                + texture(InSampler, hitUv + vec2(0.0, o.y)).rgb + texture(InSampler, hitUv - vec2(0.0, o.y)).rgb) / 6.0;
    }
    // attenuations : bords de l ecran, distance parcourue, distance au joueur, angle (Fresnel)
    vec2 e = smoothstep(vec2(0.0), vec2(0.08), hitUv) * (1.0 - smoothstep(vec2(0.92), vec2(1.0), hitUv));
    float edge = mix(e.x * e.y, 1.0, puddle * 0.7);
    float dist = 1.0 - smoothstep(Params.z * 0.6, Params.z, length(P));
    float fres = mix(1.0, pow(1.0 - max(dot(-V, Up.xyz), 0.0), 3.0), Params2.z);
    float strength = wet > 0.001 ? mix(Params.x * 0.3, max(Params.x, 0.9), puddle / max(wet, 0.001)) : Params.x;
    float fw = mix(0.1 + 0.9 * fres, 0.45 + 0.55 * fres, puddle);
    // rayon qui revient vers la camera : impossible a resoudre a l ecran (trainees en eventail) -> on l efface
    float back = 1.0 - smoothstep(0.05, 0.35, R.z);
    float k = clamp(strength * edge * dist * fw * skyHit * back, 0.0, 1.0);
    fragColor = vec4(mix(base.rgb, refl, k), base.a);
}
