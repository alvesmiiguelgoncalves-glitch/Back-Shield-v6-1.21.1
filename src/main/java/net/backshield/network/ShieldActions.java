package net.backshield.network;

import dev.emi.trinkets.api.TrinketInventory;
import net.backshield.slot.ShieldSlot;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Logica de servidor: sacar/guardar o escudo e a defesa temporaria.
 *
 * Regras de seguranca (nada de duplicar/perder item):
 *  - o escudo SEMPRE e movido (nunca copiado): uma unica referencia muda de slot;
 *  - nunca descarta nem substitui o item da mao secundaria (so troca se for outro escudo);
 *  - qualquer situacao estranha (morte, quebra, tela aberta, desconexao) encerra o estado de defesa.
 */
public final class ShieldActions {

    /** Jogadores cujo escudo esta "emprestado" na mao secundaria pela defesa temporaria. */
    private static final Set<UUID> BLOCKING = new HashSet<>();

    private ShieldActions() {
    }

    public static void init() {
        PayloadTypeRegistry.playC2S().register(ShieldActionPayload.ID, ShieldActionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ShieldActionPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            player.server.execute(() -> handle(player, payload.action()));
        });
        ServerTickEvents.END_SERVER_TICK.register(ShieldActions::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> endBlock(handler.player));
    }

    private static void handle(ServerPlayerEntity player, ShieldActionPayload.Action action) {
        if (action == null) {
            return;
        }
        if (action == ShieldActionPayload.Action.BLOCK_STOP) {
            endBlock(player); // sempre permitido, mesmo se o jogador morreu/espectador
            return;
        }
        if (!player.isAlive() || player.isSpectator()) {
            return;
        }
        switch (action) {
            case TOGGLE -> toggle(player);
            case BLOCK_START -> startBlock(player);
            default -> {
            }
        }
    }

    private static void toggle(ServerPlayerEntity player) {
        if (BLOCKING.contains(player.getUuid())) {
            endBlock(player);
            return;
        }
        if (player.isUsingItem()) {
            return;
        }
        TrinketInventory inventory = ShieldSlot.inventory(player);
        if (inventory == null) {
            return;
        }
        ItemStack back = inventory.getStack(0);
        ItemStack offhand = player.getOffHandStack();

        if (!back.isEmpty()) {
            if (offhand.isEmpty()) {
                player.equipStack(EquipmentSlot.OFFHAND, back);
                inventory.setStack(0, ItemStack.EMPTY);
            } else if (ShieldSlot.isShield(offhand)) {
                // dois escudos: troca de lugar (nada se perde)
                inventory.setStack(0, offhand);
                player.equipStack(EquipmentSlot.OFFHAND, back);
            } else {
                player.sendMessage(Text.translatable("message.backshield.offhand_occupied"), true);
                return;
            }
            playSound(player);
        } else if (ShieldSlot.isShield(offhand)) {
            inventory.setStack(0, offhand);
            player.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            playSound(player);
        }
    }

    private static void startBlock(ServerPlayerEntity player) {
        if (BLOCKING.contains(player.getUuid()) || player.isUsingItem()) {
            return;
        }
        TrinketInventory inventory = ShieldSlot.inventory(player);
        if (inventory == null) {
            return;
        }
        ItemStack back = inventory.getStack(0);
        if (back.isEmpty()) {
            return;
        }
        if (!player.getOffHandStack().isEmpty()) {
            player.sendMessage(Text.translatable("message.backshield.offhand_occupied"), true);
            return;
        }
        if (player.getItemCooldownManager().isCoolingDown(back.getItem())) {
            return; // escudo desativado por machado
        }
        player.equipStack(EquipmentSlot.OFFHAND, back);
        inventory.setStack(0, ItemStack.EMPTY);
        BLOCKING.add(player.getUuid());
        // Mesmo estado que o clique direito com escudo cria no vanilla -> o combate reconhece o bloqueio.
        player.setCurrentHand(Hand.OFF_HAND);
        playSound(player);
    }

    /** Encerra a defesa temporaria e devolve o escudo para as costas (se der). */
    private static void endBlock(ServerPlayerEntity player) {
        if (!BLOCKING.remove(player.getUuid())) {
            return;
        }
        if (player.isUsingItem() && player.getActiveHand() == Hand.OFF_HAND) {
            player.stopUsingItem();
        }
        ItemStack offhand = player.getOffHandStack();
        if (!ShieldSlot.isShield(offhand)) {
            return; // quebrou ou foi movido: nada a devolver
        }
        TrinketInventory inventory = ShieldSlot.inventory(player);
        if (inventory == null || !inventory.getStack(0).isEmpty()) {
            return; // slot ocupado: o escudo fica na mao, nada e perdido
        }
        inventory.setStack(0, offhand);
        player.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        playSound(player);
    }

    private static void tick(MinecraftServer server) {
        if (BLOCKING.isEmpty()) {
            return;
        }
        for (UUID id : new ArrayList<>(BLOCKING)) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player == null || !player.isAlive() || player.isSpectator()) {
                BLOCKING.remove(id); // morreu/saiu: regras normais de inventario valem
                continue;
            }
            ItemStack offhand = player.getOffHandStack();
            if (!ShieldSlot.isShield(offhand)) {
                BLOCKING.remove(id); // escudo quebrou ou saiu da mao
                continue;
            }
            if (player.currentScreenHandler != player.playerScreenHandler) {
                endBlock(player); // abriu bau/mesa/etc.
                continue;
            }
            // Machado desativou o escudo: ao acabar o cooldown (tecla ainda segura) volta a defender.
            if (!player.isUsingItem() && !player.getItemCooldownManager().isCoolingDown(offhand.getItem())) {
                player.setCurrentHand(Hand.OFF_HAND);
            }
        }
    }

    private static void playSound(ServerPlayerEntity player) {
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ITEM_ARMOR_EQUIP_GENERIC, SoundCategory.PLAYERS, 1.0F, 1.0F,
                player.getServerWorld().getRandom().nextLong());
    }
}
