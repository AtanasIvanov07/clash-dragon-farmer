package dev.s25.farmer;

import java.util.List;

public final class Stages {
    public static final class Field {
        public final String kind, key, instruction;
        /** Optional fields may be skipped in calibration; the farmer then leaves that step out. */
        public final boolean optional;
        Field(String kind, String key, String instruction) { this(kind, key, instruction, false); }
        Field(String kind, String key, String instruction, boolean optional) { this.kind = kind; this.key = key; this.instruction = instruction; this.optional = optional; }
    }
    /** Cards deployed after the dragons, in this order. */
    public static final List<String> EXTRA_CARDS = List.of("siege", "hero_1", "hero_2", "hero_3", "hero_4");
    public static final class Stage {
        public final String name, instruction;
        public final List<Field> fields;
        Stage(String name, String instruction, List<Field> fields) { this.name = name; this.instruction = instruction; this.fields = fields; }
    }
    private static Field t(String k, String s) { return new Field("template", k, s); }
    private static Field r(String k, String s) { return new Field("region", k, s); }
    private static Field ot(String k, String s) { return new Field("template", k, s, true); }
    private static Field p(String k, String s) { return new Field("point", k, s); }
    private static Field ps(String k, String s) { return new Field("points", k, s); }
    /** Stages from this index on are only needed for wall upgrades. */
    public static final int FIRST_WALL_STAGE = 7;
    public static final List<Stage> ALL = List.of(
        new Stage("Home village", "Open Home. Pinch fully out, center your village, and close any panels.", List.of(
            t("home", "Drag around the Attack button label, excluding scenery."),
            t("home_anchor", "Drag around a distinctive STATIC village landmark near your walls (building base or decoration). Exclude wall pieces, animations and screen buttons. This verifies camera position."),
            r("home_gold", "Drag around stored GOLD digits only. Leave room for larger amounts."),
            r("home_elixir", "Drag around stored ELIXIR digits only. Exclude other numbers."),
            p("home_blank", "Tap empty ground that closes a selected wall panel."),
            ps("walls", "Tap centers of the individual walls you want upgraded. Add several visible walls."))),
        new Stage("Multiplayer menu", "Tap Attack. Show the repeatable farming Find a Match button.", List.of(
            t("find_match", "Drag around Find a Match text inside its button. Exclude the search price."))),
        new Stage("Army screen", "Tap Find a Match. Show the screen with the final Attack! button, before an opponent loads.", List.of(
            t("start_attack", "Drag around the Attack! text inside the final button. Exclude anything that changes."))),
        new Stage("Opponent scouting", "Find a base with your dragons ready. Pinch fully out. Capture BEFORE deploying.", List.of(
            t("scout", "Drag around Next text inside the button. Exclude its price."),
            t("dragon", "Drag inside your dragon troop card. Exclude quantity and selection border."),
            r("dragon_count", "Drag around the dragon quantity (for example x18) only. Include room for two digits; exclude other cards and troop level."),
            r("loot_gold", "Drag around available GOLD digits only, with room for larger amounts."),
            r("loot_elixir", "Drag around available ELIXIR digits only, excluding the next row."),
            ps("deploy", "Tap several deployment points around the OUTER edges, outside the red boundary and away from controls."))),
        new Stage("Siege & heroes (optional)", "Find a base with your full army, like Opponent scouting. Capture BEFORE deploying. These are deployed after the dragons.", List.of(
            ot("siege", "SIEGE MACHINE card: drag inside its picture, excluding level, arrows and border. No siege? Tap Save / next without a box."),
            ot("hero_1", "FIRST HERO card: drag inside the hero's face, excluding level, ability icon and border. Tap Save / next without a box to skip."),
            ot("hero_2", "SECOND HERO card: drag inside the hero's face. Tap Save / next without a box to skip."),
            ot("hero_3", "THIRD HERO card: drag inside the hero's face. Tap Save / next without a box to skip."),
            ot("hero_4", "FOURTH HERO card: drag inside the hero's face. Tap Save / next without a box to skip."))),
        new Stage("Live battle", "Manually deploy a dragon, then capture DURING battle.", List.of(
            t("battle", "Drag around a fixed battle label such as Surrender. Exclude the timer."))),
        new Stage("Battle result", "Finish that manual attack. Capture the result before going Home.", List.of(
            t("result", "Drag around Return Home text inside its button."))),
        new Stage("Selected wall", "At fully zoomed-out Home, select ONE wall. Keep a builder free. Show both upgrade options.", List.of(
            t("wall_gold", "Drag around a static detail INSIDE the GOLD Upgrade button, including its gold icon but excluding price."),
            r("wall_gold_cost", "Drag around the gold Upgrade price digits only."),
            t("wall_elixir", "Drag around a static detail INSIDE the ELIXIR Upgrade button, including its elixir icon but excluding price."),
            r("wall_elixir_cost", "Drag around the elixir Upgrade price digits only."))),
        new Stage("Gold confirmation", "Open the selected wall's GOLD upgrade dialog. Quantity ONE. Do not confirm yet.", List.of(
            t("wall_confirm", "Drag around a fixed dialog detail, excluding changing level, price, and wall artwork."),
            t("confirm_gold", "Drag around the GOLD icon INSIDE the final confirmation button. Exclude price."),
            r("confirm_gold_cost", "Drag around the final GOLD price digits only."))),
        new Stage("Elixir confirmation", "Cancel the gold dialog. Open ELIXIR upgrade for the same wall, quantity ONE. Do not confirm.", List.of(
            t("confirm_elixir", "Drag around the ELIXIR icon INSIDE the final confirmation button. Exclude price."),
            r("confirm_elixir_cost", "Drag around the final ELIXIR price digits only.")))
    );
}
