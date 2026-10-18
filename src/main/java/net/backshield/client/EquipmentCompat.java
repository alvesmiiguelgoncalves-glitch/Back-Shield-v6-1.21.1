package net.backshield.client;

import dev.emi.trinkets.api.SlotReference;
import dev.emi.trinkets.api.TrinketComponent;
import dev.emi.trinkets.api.TrinketsApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ElytraItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Deteccao de equipamentos que mudam a posicao do escudo.
 *
 * A elytra NAO fica so no slot vanilla de peitoral: mods de "slot de elytra" (e o proprio Trinkets) colocam
 * ela em um slot Trinkets separado. Por isso procuramos em TODOS os slots Trinkets, alem do peitoral vanilla.
 */
@Environment(EnvType.CLIENT)
final class EquipmentCompat {

    private EquipmentCompat() {
    }

    static boolean isElytra(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof ElytraItem) {
            return true;
        }
        // Elytras de outros mods que nao estendem ElytraItem (ex.: "xxx_elytra").
        return Registries.ITEM.getId(stack.getItem()).getPath().contains("elytra");
    }

    /** Elytra no peitoral vanilla OU em qualquer slot Trinkets (slot de elytra de outros mods). */
    static boolean hasElytra(LivingEntity entity) {
        if (isElytra(entity.getEquippedStack(EquipmentSlot.CHEST))) {
            return true;
        }
        Optional<TrinketComponent> component = TrinketsApi.getTrinketComponent(entity);
        return component.isPresent() && component.get().isEquipped(EquipmentCompat::isElytra);
    }

    /** Lista tudo que esta em slots Trinkets (para /backshield status). */
    static List<String> describeTrinkets(LivingEntity entity) {
        List<String> lines = new ArrayList<>();
        Optional<TrinketComponent> component = TrinketsApi.getTrinketComponent(entity);
        if (component.isEmpty()) {
            lines.add("trinkets: sem componente");
            return lines;
        }
        List<Pair<SlotReference, ItemStack>> all = component.get().getAllEquipped();
        if (all.isEmpty()) {
            lines.add("trinkets equipados: (nenhum)");
        }
        for (Pair<SlotReference, ItemStack> pair : all) {
            if (!pair.getRight().isEmpty()) {
                lines.add("trinket " + pair.getLeft().inventory().getSlotType().getGroup() + "/"
                        + pair.getLeft().inventory().getSlotType().getName() + " = "
                        + Registries.ITEM.getId(pair.getRight().getItem()));
            }
        }
        return lines;
    }
}
