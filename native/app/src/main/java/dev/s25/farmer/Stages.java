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
    private static Field or(String k, String s) { return new Field("region", k, s, true); }
    private static Field ops(String k, String s) { return new Field("points", k, s, true); }
    private static Field p(String k, String s) { return new Field("point", k, s); }
    private static Field ps(String k, String s) { return new Field("points", k, s); }
    /** Stages from this index on are only needed for wall upgrades. */
    public static final int FIRST_WALL_STAGE = 7;
    public static final List<Stage> ALL = List.of(
        new Stage("Home village", "Open Home and close any panels. After CAPTURE the app zooms out exactly like it does after each battle, then takes the screenshot.", List.of(
            t("home", "Drag around the Attack button label, excluding scenery."),
            r("home_gold", "Drag around stored GOLD digits only. Leave room for larger amounts."),
            r("home_elixir", "Drag around stored ELIXIR digits only. Exclude other numbers."),
            or("home_builders", "FREE BUILDER counter (for example 1/7): drag around those digits only. Walls are skipped when no builder is free. Tap Save / next without a box to skip."),
            p("home_blank", "Tap empty ground that closes a selected wall panel."),
            ps("walls", "Tap the exact CENTER of 6-10 walls of your LOWEST level, spread around the village, both diagonal directions, none under Unplaced Items. The farmer finds every wall that looks like these."))),
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
        new Stage("Siege, heroes & rage (optional)", "Find a base with your full army, like Opponent scouting. Capture BEFORE deploying. These are deployed after the dragons.", List.of(
            ot("siege", "SIEGE MACHINE card: drag over the whole cyan card. Any siege machine works later, whichever your clan donates and at any level: the bot finds the cyan card. No siege? Tap Save / next without a box."),
            ot("hero_1", "FIRST HERO card: drag inside the hero's face, excluding level, ability icon and border. Tap Save / next without a box to skip."),
            ot("hero_2", "SECOND HERO card: drag inside the hero's face. Tap Save / next without a box to skip."),
            ot("hero_3", "THIRD HERO card: drag inside the hero's face. Tap Save / next without a box to skip."),
            ot("hero_4", "FOURTH HERO card: drag inside the hero's face. Tap Save / next without a box to skip."),
            ot("rage", "RAGE SPELL card: drag inside the potion picture, excluding the count and level. Tap Save / next without a box to skip."),
            ops("rage_points", "RAGE drop points: tap a little INSIDE the base, in front of where your dragons enter. One spell per point. Tap Save / next without points to skip."))),
        new Stage("Live battle", "Manually deploy a dragon, then capture DURING battle.", List.of(
            t("battle", "Drag around a fixed battle label such as Surrender. Exclude the timer."))),
        new Stage("Battle result", "Finish that manual attack. Capture the result before going Home.", List.of(
            t("result", "Drag around Return Home text inside its button."))),
        new Stage("Selected wall", "At fully zoomed-out Home, select ONE wall. Keep a builder free. Show both upgrade options.", List.of(
            t("wall_gold", "Drag around a static detail INSIDE the GOLD Upgrade button, including its gold icon but excluding price."),
            r("wall_gold_cost", "Drag around the gold Upgrade price digits only."),
            t("wall_elixir", "Drag around a static detail INSIDE the ELIXIR Upgrade button, including its elixir icon but excluding price."),
            r("wall_elixir_cost", "Drag around the elixir Upgrade price digits only."))),
        new Stage("Upgrade More button (optional)", "At Home select ONE wall of your lowest level so its button bar shows (Info, Select Row, Upgrade More, Upgrade). Do not tap anything else.", List.of(
            ot("wall_more", "UPGRADE MORE button: drag around its wall icon with the + sign and its label. Skip to upgrade one wall at a time."))),
        new Stage("Wall batch (optional)", "Select ONE lowest-level wall, tap Upgrade More, then tap +10 once so the title shows x 11. Do not pay. Capture.", List.of(
            ot("batch_add10", "+10 ADD WALL button: drag around its +10 icon and label."),
            ot("batch_add1", "+1 ADD WALL button: drag around its +1 icon and label."),
            ot("batch_gold", "GOLD Upgrade button in this mode: drag around the double hammers and the word Upgrade, excluding the price."),
            or("batch_gold_cost", "GOLD total price: drag across the WHOLE price strip at the top of that button, edge to edge, so bigger totals still fit."),
            ot("batch_elixir", "ELIXIR Upgrade button in this mode: drag around the double hammers and the word Upgrade, excluding the price."),
            or("batch_elixir_cost", "ELIXIR total price: drag across the WHOLE price strip at the top of that button, edge to edge."))),
        new Stage("Gold confirmation", "Open the selected wall's GOLD upgrade dialog. Quantity ONE. Do not confirm yet.", List.of(
            t("wall_confirm", "Drag around a fixed dialog detail, excluding changing level, price, and wall artwork."),
            t("confirm_gold", "Drag around the GOLD icon INSIDE the final confirmation button. Exclude price."),
            r("confirm_gold_cost", "Drag around the final GOLD price digits only."))),
        new Stage("Elixir confirmation", "Cancel the gold dialog. Open ELIXIR upgrade for the same wall, quantity ONE. Do not confirm.", List.of(
            t("confirm_elixir", "Drag around the ELIXIR icon INSIDE the final confirmation button. Exclude price."),
            r("confirm_elixir_cost", "Drag around the final ELIXIR price digits only.")))
    );
}
