#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// Ambiances de ciel procedurales (bruit 3D dans la direction de vue : pas de pole, pas de pixels).
// 1 tempete violette, 2 enfer, 3 aurore cosmique, 4 eclairs, 5 nebuleuse, 6 orage.

in vec3 skyDir;
in vec2 params;
in vec4 tint;

out vec4 fragColor;

float hash(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x),
                   mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x),
                   mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p, int oct) {
    float a = 0.5, s = 0.0;
    for (int i = 0; i < 7; i++) {
        if (i >= oct) break;
        s += a * noise(p);
        p = p * 2.03 + vec3(1.7, 9.2, 3.1);
        a *= 0.5;
    }
    return s;
}

/** Bruit en cretes : filaments fins et ramifies. */
float ridged(vec3 p, int oct) {
    float a = 0.5, s = 0.0;
    for (int i = 0; i < 6; i++) {
        if (i >= oct) break;
        float n = 1.0 - abs(noise(p) * 2.0 - 1.0);
        s += a * n * n;
        p = p * 2.1 + vec3(3.1, 1.7, 7.3);
        a *= 0.5;
    }
    return s;
}

/** Etoiles nettes, de taille constante a l ecran, qui scintillent. */
vec3 stars(vec3 d, float t, float density) {
    vec3 col = vec3(0.0);
    for (int k = 0; k < 2; k++) {
        float sc = k == 0 ? 220.0 : 520.0;
        vec3 c = floor(d * sc);
        float h = hash(c + float(k) * 13.0);
        if (h > 1.0 - density) {
            vec3 f = fract(d * sc) - 0.5;
            float r = length(f);
            float tw = 0.65 + 0.35 * sin(t * (1.5 + h * 4.0) + h * 60.0);
            float b = smoothstep(0.32, 0.0, r) * tw * (k == 0 ? 1.0 : 0.55);
            col += mix(vec3(0.75, 0.8, 1.0), vec3(1.0, 0.85, 0.95), fract(h * 91.0)) * b;
        }
    }
    return col;
}

/** Point d une couche de nuages plane (perspective realiste) vue dans la direction d. */
vec2 cloudPlane(vec3 d, float h) {
    return d.xz * (h / max(d.y + 0.12, 0.02));
}

vec3 clouds(vec3 d, float t, vec3 dark, vec3 light, float cover, float speed) {
    if (d.y < -0.05) return dark;
    vec2 p = cloudPlane(d, 1.0) * 0.9 + vec2(t * speed, t * speed * 0.4);
    vec3 q = vec3(p, t * 0.02);
    // deformation de domaine : volutes plutot que taches
    vec3 w = vec3(fbm(q, 4), fbm(q + 5.2, 4), 0.0);
    float n = fbm(q + w * 1.6, 6);
    float c = smoothstep(cover, cover + 0.35, n);
    float fade = smoothstep(-0.05, 0.25, d.y);
    // bords eclaires, ventre sombre
    float lit = smoothstep(0.4, 0.9, fbm(q * 1.7 + w, 4));
    return mix(dark, mix(dark * 1.6, light, lit), c * fade);
}

float flash(float t, out float az) {
    float slot = floor(t / 7.5);
    float start = slot * 7.5 + hash(vec3(slot, 3.0, 1.0)) * 4.0;
    az = hash(vec3(slot, 7.0, 2.0)) * 6.2832;
    float x = t - start;
    if (x < 0.0 || x > 0.9) return 0.0;
    return max(0.0, sin(x * 23.0)) * exp(-x * 3.5);
}

vec3 sky(vec3 d, float t, int mode) {
    float up = clamp(d.y, -1.0, 1.0);
    float hz = exp(-abs(up) * 7.0);
    if (mode == 1) {
        // tempete violette : nuages tourmentes, eclairs internes
        vec3 base = mix(vec3(0.05, 0.02, 0.10), vec3(0.20, 0.10, 0.38), hz);
        vec3 col = clouds(d, t, base, vec3(0.78, 0.6, 1.0), 0.34, 0.012);
        float az;
        float f = flash(t, az);
        float near = exp(-pow(abs(mod(atan(d.z, d.x) - az + 3.1416, 6.2832) - 3.1416) * 1.4, 2.0));
        return col + vec3(0.55, 0.4, 1.0) * f * near * 0.8 + stars(d, t, 0.004) * smoothstep(0.2, 0.6, up) * 0.4;
    }
    if (mode == 2) {
        // enfer : fumees rougeoyantes, braises
        vec3 base = mix(vec3(0.06, 0.01, 0.0), vec3(0.55, 0.12, 0.02), pow(hz, 0.7));
        vec3 col = clouds(d, t, base, vec3(1.0, 0.45, 0.1), 0.38, 0.02);
        float embers = step(0.9985, hash(floor(d * 300.0 + vec3(0.0, -t * 3.0, 0.0))));
        return col + vec3(1.0, 0.5, 0.15) * embers * smoothstep(0.0, 0.4, up);
    }
    if (mode == 3) {
        // aurore cosmique : ciel indigo etoile, rideaux d aurore qui ondulent au-dessus de l horizon
        vec3 col = mix(vec3(0.02, 0.01, 0.07), vec3(0.20, 0.06, 0.36), pow(hz, 1.5));
        col += stars(d, t, 0.006) * smoothstep(0.0, 0.3, up);
        col += vec3(0.08, 0.03, 0.14) * fbm(d * 3.0 + t * 0.01, 4);
        if (up > 0.0) {
            // rideaux fins : 3 voiles, chacun empile en hauteur (rayons verticaux nets), bord bas lumineux
            vec3 aur = vec3(0.0);
            for (int k = 0; k < 3; k++) {
                float fk = float(k);
                for (int i = 0; i < 22; i++) {
                    float fi = (float(i) + hash(d * 311.0 + float(i))) / 22.0;
                    float h = 1.0 + fi * 1.8;
                    vec2 p = d.xz * (h / (up + 0.04)) * 0.18;
                    float line = sin(p.x * 0.7 + fk * 2.1 + t * 0.04) * 0.9 + sin(p.x * 1.9 - fk + t * 0.07) * 0.25 + (fk - 1.0) * 1.6;
                    float dist = abs(p.y - line);
                    float core = exp(-dist * dist * 2600.0) + 0.25 * exp(-dist * dist * 260.0);
                    float rays = 0.15 + 0.85 * pow(noise(vec3(p.x * 22.0 + fk * 5.0, t * 0.15, fk)), 3.0);
                    float lower = (1.0 - fi) * (1.0 - fi);
                    vec3 c = mix(vec3(0.40, 1.0, 0.82), vec3(0.78, 0.36, 1.0), smoothstep(0.0, 0.6, fi));
                    aur += c * core * rays * (0.12 + 0.88 * lower) * 0.22;
                }
            }
            col += aur * smoothstep(0.02, 0.15, up) * (1.0 - smoothstep(0.65, 0.95, up));
        }
        return col;
    }
    if (mode == 4) {
        // eclairs : nuages gris, filaments lumineux qui respirent et glissent
        vec3 col = clouds(d, t, mix(vec3(0.04, 0.045, 0.055), vec3(0.16, 0.17, 0.2), hz), vec3(0.35, 0.37, 0.42), 0.45, 0.008);
        vec3 q = d * 2.2 + vec3(t * 0.015, 0.0, t * 0.01);
        float r = ridged(q, 5);
        float veins = pow(r, 6.0) * 2.4;
        float halo = pow(r, 3.0) * 0.35;
        float pulse = 0.65 + 0.35 * sin(t * 1.3 + fbm(q * 0.5, 2) * 6.0);
        float mask = smoothstep(-0.1, 0.15, up);
        return col + vec3(0.82, 0.88, 1.0) * (veins + halo) * pulse * mask;
    }
    if (mode == 5) {
        // nebuleuse : gaz deformes violets / roses, voies sombres, etoiles
        vec3 q = d * 1.6;
        vec3 w = vec3(fbm(q + t * 0.004, 5), fbm(q + 3.3, 5), fbm(q + 7.1, 5));
        float n = fbm(q + w * 2.2, 6);
        float dust = smoothstep(0.45, 0.75, fbm(q * 2.4 + w, 5));
        vec3 gas = mix(vec3(0.03, 0.01, 0.06), vec3(0.42, 0.16, 0.62), smoothstep(0.35, 0.75, n));
        gas = mix(gas, vec3(0.95, 0.55, 0.9), smoothstep(0.62, 0.9, n) * 0.6);
        gas *= 1.0 - dust * 0.7;
        return gas + stars(d, t, 0.008) * (1.0 - dust * 0.8);
    }
    if (mode == 7) {
        // nuit maudite : plafond gris-bleu epais, lune voilee, eclairs lointains dans les nuages
        vec3 moonDir = normalize(vec3(0.55, 0.42, -0.62));
        float md = dot(d, moonDir);
        vec3 base = mix(vec3(0.018, 0.02, 0.03), vec3(0.11, 0.12, 0.15), pow(hz, 0.7));
        base += vec3(0.35, 0.36, 0.42) * pow(max(md, 0.0), 40.0) * 0.6 + vec3(0.12, 0.12, 0.16) * pow(max(md, 0.0), 6.0);
        vec3 col = base;
        if (up > -0.05) {
            vec2 p = cloudPlane(d, 1.0) * 0.8 + vec2(t * 0.012, t * 0.004);
            vec3 q = vec3(p, t * 0.015);
            vec3 w = vec3(fbm(q, 4), fbm(q + 5.2, 4), 0.0);
            float n = fbm(q + w * 1.8, 6);
            float c = smoothstep(0.32, 0.7, n) * smoothstep(-0.05, 0.25, up);
            float rim = smoothstep(0.45, 0.85, fbm(q * 1.6 + w, 4));
            vec3 cloudCol = mix(vec3(0.05, 0.055, 0.07), vec3(0.32, 0.33, 0.38), rim);
            cloudCol += vec3(0.55, 0.56, 0.62) * pow(max(md, 0.0), 10.0) * rim;
            col = mix(col, cloudCol, c);
            // lune nette dans les trouees
            float disk = smoothstep(0.9994, 0.9997, md);
            col += vec3(0.95, 0.95, 1.0) * disk * (1.0 - c * 0.85);
            float az;
            float f = flash(t * 0.8, az);
            if (f > 0.0) {
                float da = abs(mod(atan(d.z, d.x) - az + 3.1416, 6.2832) - 3.1416);
                col += vec3(0.6, 0.62, 0.8) * f * exp(-da * da * 6.0) * c * smoothstep(0.0, 0.25, up) * 0.9;
            }
        }
        col += stars(d, t, 0.003) * smoothstep(0.15, 0.5, up) * 0.35;
        return col;
    }
    // 6 orage : plafond bas et lourd, eclairs qui illuminent le dessous des nuages
    vec3 base = mix(vec3(0.03, 0.05, 0.06), vec3(0.22, 0.29, 0.31), pow(hz, 0.8));
    vec3 col = clouds(d, t, base, vec3(0.5, 0.6, 0.62), 0.26, 0.03);
    float az;
    float f = flash(t, az);
    if (f > 0.0) {
        float da = abs(mod(atan(d.z, d.x) - az + 3.1416, 6.2832) - 3.1416);
        float glow = exp(-da * da * 2.5) * smoothstep(-0.05, 0.3, up);
        // trait de foudre : filament vertical tremble
        float x = da * 6.0 + (noise(vec3(up * 18.0, az, floor(t * 20.0))) - 0.5) * 0.6;
        float bolt = smoothstep(0.08, 0.0, abs(x)) * smoothstep(0.55, 0.05, up) * step(0.0, up);
        col += vec3(0.55, 0.9, 0.85) * f * glow * 0.9 + vec3(0.85, 1.0, 1.0) * bolt * f * 2.0;
    }
    return col;
}

void main() {
    vec3 d = normalize(skyDir);
    int mode = int(params.y + 0.5);
    vec3 col = sky(d, params.x, mode);
    // leger grain : supprime les bandes de degrade
    col += (hash(vec3(gl_FragCoord.xy, params.x)) - 0.5) / 255.0;
    fragColor = vec4(col, tint.a) * ColorModulator;
}
