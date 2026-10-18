package net.backshield.client;

import dev.emi.trinkets.api.TrinketComponent;
import dev.emi.trinkets.api.TrinketsApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Integracao OPCIONAL com o Backpacked (MrCrayfish).
 *
 * Antes o mod chamava UM metodo por reflexao e exigia que o retorno fosse um ItemStack; se o Backpacked
 * devolvesse outro tipo (Optional, lista...) ou lancasse excecao, o resultado era sempre "sem mochila".
 * Agora ha varias estrategias, na ordem:
 *  1. qualquer metodo estatico publico do BackpackHelper que receba o jogador e tenha "backpack" no nome
 *     (aceita retorno ItemStack, Optional, Iterable ou boolean);
 *  2. qualquer slot Trinkets com item do namespace "backpacked";
 *  3. (so jogador local) qualquer slot do inventario aberto/da tela do jogador com item "backpacked".
 * Uma falha em uma estrategia nunca desliga as outras.
 */
@Environment(EnvType.CLIENT)
final class BackpackCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("backshield");
    private static final String HELPER_CLASS = "com.mrcrayfish.backpacked.BackpackHelper";
    private static final String NAMESPACE = "backpacked";

    private static boolean initialized;
    private static boolean loaded;
    private static final List<Method> methods = new ArrayList<>();
    private static String lastMethodUsed = "-";
    private static String lastResultType = "-";

    private BackpackCompat() {
    }

    private static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        loaded = FabricLoader.getInstance().isModLoaded(NAMESPACE);
        if (!loaded) {
            return;
        }
        try {
            Class<?> helper = Class.forName(HELPER_CLASS);
            for (Method method : helper.getMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) {
                    continue;
                }
                if (!method.getParameterTypes()[0].isAssignableFrom(PlayerEntity.class)) {
                    continue;
                }
                String name = method.getName().toLowerCase();
                // So metodos de LEITURA: nunca chamar algo como equip/open/unlock a cada frame.
                boolean reader = name.startsWith("get") || name.startsWith("has") || name.startsWith("is")
                        || name.startsWith("find");
                if (!name.contains("backpack") || !reader || method.getReturnType() == void.class) {
                    continue;
                }
                method.setAccessible(true);
                methods.add(method);
            }
            // getFirstBackpackStack primeiro, depois os que tem "stack" no nome.
            methods.sort(Comparator
                    .comparing((Method m) -> !m.getName().equals("getFirstBackpackStack"))
                    .thenComparing(m -> !m.getName().toLowerCase().contains("stack"))
                    .thenComparing(Method::getName));
        } catch (Throwable t) {
            LOGGER.warn("Backpacked is installed but {} could not be loaded; using Trinkets/slot fallbacks only", HELPER_CLASS, t);
        }
    }

    /** Linhas de diagnostico para /backshield status. */
    static List<String> describe(LivingEntity entity) {
        init();
        List<String> lines = new ArrayList<>();
        lines.add("backpacked installed=" + loaded + ", helper methods=" + methods.size());
        for (Method m : methods) {
            lines.add("  api " + m.getName() + "(" + m.getParameterTypes()[0].getSimpleName() + ") -> "
                    + m.getReturnType().getSimpleName());
        }
        try {
            for (Method m : Class.forName(HELPER_CLASS).getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) {
                    StringBuilder params = new StringBuilder();
                    for (Class<?> c : m.getParameterTypes()) {
                        params.append(params.length() == 0 ? "" : ", ").append(c.getSimpleName());
                    }
                    lines.add("  helper " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
                }
            }
        } catch (Throwable t) {
            lines.add("  helper class nao encontrada: " + t);
        }
        if (entity instanceof PlayerEntity player) {
            for (Method m : methods) {
                try {
                    lines.add("  raw " + m.getName() + " = " + m.invoke(null, player));
                } catch (Throwable t) {
                    lines.add("  raw " + m.getName() + " lancou " + t);
                }
            }
        }
        describeInjected(entity, lines);
        boolean has = hasBackpack(entity);
        lines.add("backpack equipped=" + has + " (via " + lastMethodUsed + ", result type " + lastResultType + ")");
        return lines;
    }

    static boolean hasBackpack(LivingEntity entity) {
        init();
        if (!loaded || !(entity instanceof PlayerEntity player)) {
            return false;
        }

        // 1. API do Backpacked
        for (int i = 0; i < methods.size(); i++) {
            Method method = methods.get(i);
            try {
                Object result = method.invoke(null, player);
                lastResultType = result == null ? "null" : result.getClass().getSimpleName();
                if (truthy(result)) {
                    lastMethodUsed = method.getName();
                    return true;
                }
            } catch (Throwable t) {
                LOGGER.warn("Backpacked method {} failed; skipping it", method.getName(), t);
                methods.remove(i--);
            }
        }

        // 2. Backpack em slot Trinkets
        Optional<TrinketComponent> component = TrinketsApi.getTrinketComponent(player);
        if (component.isPresent() && component.get().isEquipped(BackpackCompat::isBackpackStack)) {
            lastMethodUsed = "trinkets";
            return true;
        }

        // 4. Interfaces do Backpacked injetadas no jogador/inventario (o menu do V pode guardar a mochila ai)
        if (scanInjected(player) || scanInjected(player.getInventory())) {
            lastMethodUsed = "injected interface";
            return true;
        }

        // 3. Slot extra na tela do jogador (so existe para o jogador local)
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == player) {
            for (Slot slot : player.playerScreenHandler.slots) {
                if (isBackpackStack(slot.getStack())) {
                    lastMethodUsed = "screen slot";
                    return true;
                }
            }
        }
        return false;
    }

    /** Procura, em interfaces com.mrcrayfish.backpacked.* do objeto, getters sem argumento que devolvam a mochila. */
    private static boolean scanInjected(Object target) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> iface : type.getInterfaces()) {
                if (!iface.getName().startsWith("com.mrcrayfish.backpacked")) {
                    continue;
                }
                for (Method m : iface.getMethods()) {
                    if (m.getParameterCount() != 0 || m.getReturnType() == void.class || Modifier.isStatic(m.getModifiers())) {
                        continue;
                    }
                    String name = m.getName().toLowerCase();
                    if (!(name.startsWith("get") || name.startsWith("has") || name.startsWith("is"))) {
                        continue;
                    }
                    try {
                        if (containsBackpack(m.invoke(target))) {
                            return true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    /** Aceita ItemStack, Optional, Iterable ou Inventory, mas so conta itens do namespace backpacked. */
    private static boolean containsBackpack(Object result) {
        if (result instanceof ItemStack stack) {
            return isBackpackStack(stack);
        }
        if (result instanceof Optional<?> optional) {
            return optional.isPresent() && containsBackpack(optional.get());
        }
        if (result instanceof Inventory inventory) {
            for (int i = 0; i < inventory.size(); i++) {
                if (isBackpackStack(inventory.getStack(i))) {
                    return true;
                }
            }
            return false;
        }
        if (result instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                if (containsBackpack(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Diagnostico: interfaces do Backpacked no jogador e no inventario. */
    private static void describeInjected(LivingEntity entity, List<String> lines) {
        if (!(entity instanceof PlayerEntity player)) {
            return;
        }
        for (Object target : new Object[]{player, player.getInventory()}) {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                for (Class<?> iface : type.getInterfaces()) {
                    if (iface.getName().startsWith("com.mrcrayfish.backpacked")) {
                        lines.add("  injected " + iface.getName() + " em " + target.getClass().getSimpleName());
                        for (Method m : iface.getMethods()) {
                            lines.add("    " + m.getName() + "(" + m.getParameterCount() + " args) -> " + m.getReturnType().getSimpleName());
                        }
                    }
                }
            }
        }
    }

    private static boolean isBackpackStack(ItemStack stack) {
        return !stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).getNamespace().equals(NAMESPACE);
    }

    private static boolean truthy(Object result) {
        if (result instanceof ItemStack stack) {
            return !stack.isEmpty();
        }
        if (result instanceof Optional<?> optional) {
            return optional.isPresent() && truthy(optional.get());
        }
        if (result instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                if (truthy(element)) {
                    return true;
                }
            }
            return false;
        }
        if (result instanceof Boolean bool) {
            return bool;
        }
        return false;
    }
}
