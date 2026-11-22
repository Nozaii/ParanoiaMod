package gg.aurora.ui;

/**
 * Ressort amorti : donne des mouvements naturels avec un léger rebond.
 * stiffness = vitesse, damping = 1 sans rebond, < 1 avec rebond.
 */
public final class Spring {
    public float value, velocity, target;
    private final float stiffness, damping;

    public Spring(float value, float stiffness, float damping) {
        this.value = this.target = value;
        this.stiffness = stiffness;
        this.damping = damping;
    }

    public Spring set(float v) {
        value = target = v;
        velocity = 0;
        return this;
    }

    public void update(float dt) {
        if (dt >= 1f || !gg.aurora.Settings.anim()) { // animations désactivées : saut direct
            value = target;
            velocity = 0;
            return;
        }
        // sous-pas fixes pour rester stable même à bas FPS
        float remaining = Math.min(dt, 0.1f);
        float c = 2f * damping * (float) Math.sqrt(stiffness);
        while (remaining > 0) {
            float h = Math.min(remaining, 1f / 240f);
            float a = stiffness * (target - value) - c * velocity;
            velocity += a * h;
            value += velocity * h;
            remaining -= h;
        }
        if (Math.abs(target - value) < 0.0005f && Math.abs(velocity) < 0.001f) {
            value = target;
            velocity = 0;
        }
    }

    public float update(float dt, float newTarget) {
        target = newTarget;
        update(dt);
        return value;
    }

    public boolean settled() {
        return value == target && velocity == 0;
    }
}
