package gg.aurora.module;

/** Un module activable depuis le menu. */
public class Module {
    public final String id;
    public final String name;
    /** Catégorie et groupe d'affichage (rangés par ModuleGroups). */
    public Category category;
    public String group = "Others";
    public int groupOrder = 999;
    public final String icon;
    public boolean enabled;
    /** Touche qui active / désactive le module (code GLFW), -1 = aucune. */
    public int key = -1;

    /** Mod installé qui fait déjà la même chose (null = aucun) : affiché dans les réglages. */
    public String conflict;

    /** Position animée du bouton on/off (0 = off, 1 = on). */
    public float toggleAnim;

    /** Options du module (sauvegardées dans la config). */
    public final java.util.Map<String, Double> opts = new java.util.LinkedHashMap<>();
    private java.util.function.Supplier<java.util.List<gg.aurora.ui.Rows.Row>> settingsFactory;

    public double opt(String key, double def) {
        // appelé par entité et par image : pas de lambda ni de recherche en double
        Double v = opts.get(key);
        if (v != null) return v;
        opts.put(key, def);
        return def;
    }

    public boolean flag(String key, boolean def) {
        return opt(key, def ? 1 : 0) != 0;
    }

    public void set(String key, double v) {
        opts.put(key, v);
    }

    public void set(String key, boolean v) {
        opts.put(key, v ? 1.0 : 0.0);
    }

    /** Déclare les réglages affichés par le bouton engrenage du module. */
    public <T extends Module> T withSettings(java.util.function.Supplier<java.util.List<gg.aurora.ui.Rows.Row>> factory) {
        this.settingsFactory = factory;
        @SuppressWarnings("unchecked") T self = (T) this;
        return self;
    }

    public boolean hasSettings() {
        return settingsFactory != null || conflict != null;
    }

    public java.util.List<gg.aurora.ui.Rows.Row> settings() {
        java.util.List<gg.aurora.ui.Rows.Row> rows = settingsFactory == null ? java.util.List.of() : settingsFactory.get();
        if (conflict == null) return rows;
        java.util.List<gg.aurora.ui.Rows.Row> out = new java.util.ArrayList<>();
        out.add(new gg.aurora.ui.Rows.Info("Duplicate", conflict + " already does this: keep only one of the two"));
        out.addAll(rows);
        return out;
    }

    /** État par défaut (sert aux mesures de performance reproductibles). */
    public final boolean def;

    public Module(String id, String name, Category category, String icon, boolean enabledByDefault) {
        this.def = enabledByDefault;
        this.id = id;
        this.name = name;
        this.category = category;
        this.icon = icon;
        this.enabled = enabledByDefault;
        this.toggleAnim = enabledByDefault ? 1f : 0f;
    }

    public void toggle() {
        if (!enabled && gg.aurora.FairPlay.blocked(this)) {
            gg.aurora.FairPlay.notice(this);
            return;
        }
        if (!enabled && gg.aurora.render.TexCopy.blocked(id)) {
            gg.aurora.ui.Toasts.push(gg.aurora.ui.Icons.INFO, name, "Not available with VulkanMod", false);
            return;
        }
        enabled = !enabled;
        onToggle();
    }

    protected void onToggle() {}
}
