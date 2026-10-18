package net.backshield.slot;

import dev.emi.trinkets.api.TrinketComponent;
import dev.emi.trinkets.api.TrinketInventory;
import dev.emi.trinkets.api.TrinketsApi;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShieldItem;

import java.util.Map;
import java.util.Optional;

/** Acesso ao slot Trinkets "chest/back_shield". Todo o armazenamento/sync/morte fica por conta do Trinkets. */
public final class ShieldSlot {

    public static final String GROUP = "chest";
    public static final String SLOT = "back_shield";

    private ShieldSlot() {
    }

    public static boolean isShield(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ShieldItem;
    }

    /** @return o inventario do slot ou null se a entidade nao tem o slot. */
    public static TrinketInventory inventory(LivingEntity entity) {
        Optional<TrinketComponent> component = TrinketsApi.getTrinketComponent(entity);
        if (component.isEmpty()) {
            return null;
        }
        Map<String, TrinketInventory> group = component.get().getInventory().get(GROUP);
        if (group == null) {
            return null;
        }
        TrinketInventory inventory = group.get(SLOT);
        return inventory != null && inventory.size() > 0 ? inventory : null;
    }

    public static ItemStack get(LivingEntity entity) {
        TrinketInventory inventory = inventory(entity);
        return inventory == null ? ItemStack.EMPTY : inventory.getStack(0);
    }
}
