package net.backshield.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.emi.trinkets.api.client.TrinketRendererRegistry;
import net.backshield.config.BackShieldConfig;
import net.backshield.network.ShieldActionPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.command.CommandSource;
import net.minecraft.item.Item;
import net.minecraft.item.ShieldItem;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

public class BackShieldClient implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private static KeyBinding blockKey;
    private static boolean blocking;
    private static boolean forcingUseKey;

    @Override
    public void onInitializeClient() {
        BackShieldConfig.load();

        // Registra o renderizador para todo ShieldItem (inclusive de outros mods) depois que todos os itens existem.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            BackShieldRenderer renderer = new BackShieldRenderer();
            for (Item item : Registries.ITEM) {
                if (item instanceof ShieldItem) {
                    TrinketRendererRegistry.registerRenderer(item, renderer);
                }
            }
        });

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.backshield.toggle",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.backshield.key"));
        blockKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.backshield.block",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.backshield.key"));

        ClientTickEvents.START_CLIENT_TICK.register(BackShieldClient::holdUseKey);
        ClientTickEvents.END_CLIENT_TICK.register(BackShieldClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> blocking = false);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("backshield")
                        .then(ClientCommandManager.literal("show")
                                .executes(ctx -> {
                                    BackShieldConfig.describe(null).forEach(line -> ctx.getSource().sendFeedback(Text.literal(line)));
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("profile", StringArgumentType.word())
                                        .suggests((ctx, builder) -> CommandSource.suggestMatching(BackShieldConfig.PROFILES, builder))
                                        .executes(ctx -> {
                                            BackShieldConfig.describe(StringArgumentType.getString(ctx, "profile"))
                                                    .forEach(line -> ctx.getSource().sendFeedback(Text.literal(line)));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("status").executes(ctx -> {
                            var player = ctx.getSource().getPlayer();
                            BackpackCompat.describe(player).forEach(line -> ctx.getSource().sendFeedback(Text.literal(line)));
                            ctx.getSource().sendFeedback(Text.literal("perfil ativo = " + BackShieldRenderer.selectProfile(player)));
                            ctx.getSource().sendFeedback(Text.literal("elytra=" + EquipmentCompat.hasElytra(player)
                                    + ", chest=" + player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem()
                                    + ", fallFlying=" + player.isFallFlying()));
                            EquipmentCompat.describeTrinkets(player).forEach(line -> ctx.getSource().sendFeedback(Text.literal(line)));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reload").executes(ctx -> {
                            BackShieldConfig.load();
                            ctx.getSource().sendFeedback(Text.literal("backshield-client.json reloaded"));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reset")
                                .executes(ctx -> {
                                    BackShieldConfig.reset(null);
                                    ctx.getSource().sendFeedback(Text.literal("Todos os perfis zerados"));
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("profile", StringArgumentType.word())
                                        .suggests((ctx, builder) -> CommandSource.suggestMatching(BackShieldConfig.PROFILES, builder))
                                        .executes(ctx -> {
                                            String profile = StringArgumentType.getString(ctx, "profile");
                                            if (!BackShieldConfig.PROFILES.contains(profile)) {
                                                ctx.getSource().sendFeedback(Text.literal("Perfil desconhecido: " + profile));
                                                return 0;
                                            }
                                            BackShieldConfig.reset(profile);
                                            ctx.getSource().sendFeedback(Text.literal("Perfil " + profile + " zerado"));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("set")
                                .then(ClientCommandManager.argument("profile", StringArgumentType.word())
                                        .suggests((ctx, builder) -> CommandSource.suggestMatching(BackShieldConfig.PROFILES, builder))
                                        .then(ClientCommandManager.argument("field", StringArgumentType.word())
                                                .suggests((ctx, builder) -> CommandSource.suggestMatching(BackShieldConfig.FIELDS, builder))
                                                .then(ClientCommandManager.argument("value", FloatArgumentType.floatArg())
                                                        .executes(ctx -> {
                                                            String profile = StringArgumentType.getString(ctx, "profile");
                                                            String field = StringArgumentType.getString(ctx, "field");
                                                            float value = FloatArgumentType.getFloat(ctx, "value");
                                                            boolean ok = BackShieldConfig.set(profile, field, value);
                                                            ctx.getSource().sendFeedback(Text.literal(ok
                                                                    ? profile + "." + field + " = " + value
                                                                    : "Perfil ou campo desconhecido: " + profile + " " + field));
                                                            return ok ? 1 : 0;
                                                        })))))));
    }

    /**
     * O vanilla solta o escudo quando o botao direito nao esta pressionado. Enquanto a defesa por tecla
     * esta ativa (e o escudo ja esta em uso na mao secundaria), mantemos o "usar" pressionado.
     * Roda no INICIO do tick, antes de handleInputEvents; so age depois que o servidor confirmou o uso,
     * entao nunca dispara um clique direito extra com a mao principal.
     */
    private static void holdUseKey(MinecraftClient client) {
        var player = client.player;
        if (blocking && player != null && player.isUsingItem() && player.getActiveHand() == Hand.OFF_HAND) {
            client.options.useKey.setPressed(true);
            forcingUseKey = true;
        } else if (forcingUseKey) {
            forcingUseKey = false;
            KeyBinding.updatePressedStates(); // devolve o estado real das teclas
        }
    }

    private static void tick(MinecraftClient client) {
        if (client.player == null) {
            blocking = false;
            return;
        }
        boolean canSend = ClientPlayNetworking.canSend(ShieldActionPayload.ID);

        while (toggleKey.wasPressed()) {
            if (canSend && client.currentScreen == null) {
                ClientPlayNetworking.send(new ShieldActionPayload(ShieldActionPayload.Action.TOGGLE));
            }
        }

        // Defesa: enquanto a tecla esta segura e nenhuma tela esta aberta.
        boolean wantBlock = canSend && blockKey.isPressed() && client.currentScreen == null && !client.player.isSpectator();
        if (wantBlock && !blocking) {
            blocking = true;
            ClientPlayNetworking.send(new ShieldActionPayload(ShieldActionPayload.Action.BLOCK_START));
        } else if (!wantBlock && blocking) {
            blocking = false;
            if (canSend) {
                ClientPlayNetworking.send(new ShieldActionPayload(ShieldActionPayload.Action.BLOCK_STOP));
            }
        }
    }
}
