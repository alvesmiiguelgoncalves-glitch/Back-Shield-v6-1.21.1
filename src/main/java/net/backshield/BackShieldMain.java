package net.backshield;

import dev.emi.trinkets.api.TrinketsApi;
import net.backshield.network.ShieldActions;
import net.backshield.slot.ShieldSlot;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.util.Identifier;

public class BackShieldMain implements ModInitializer {

    public static final String MOD_ID = "backshield";

    /** Id do validador de slot referenciado em data/trinkets/slots/chest/back_shield.json. */
    public static final Identifier SHIELD_PREDICATE = id("shield");

    @Override
    public void onInitialize() {
        // O slot so aceita escudos (qualquer ShieldItem, inclusive de outros mods).
        TrinketsApi.registerTrinketPredicate(SHIELD_PREDICATE,
                (stack, slotRef, entity) -> ShieldSlot.isShield(stack) ? TriState.TRUE : TriState.FALSE);

        ShieldActions.init();
    }

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }
}
