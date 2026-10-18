package net.backshield.client;

import dev.emi.trinkets.api.SlotReference;
import dev.emi.trinkets.api.client.TrinketRenderer;
import net.backshield.config.BackShieldConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.RotationAxis;

/**
 * Desenha o escudo nas costas.
 *
 * Em vez de usar as transformacoes de "peito/cabeca" do Trinkets, usa o proprio ModelPart do torso
 * (body.rotate): ele ja contem a pose atual (agachar, nadar, animacoes de outros mods), entao o escudo
 * fica preso ao corpo em vez de numa coordenada fixa.
 *
 * Derivacao da orientacao (item de escudo, ModelTransformationMode.NONE = sem transformacao de display):
 *  - o renderizador do escudo desenha a placa com a face para +Z e "em pe" no eixo Y do item;
 *  - o referencial do modelo tem Y para BAIXO e +Z para TRAS, entao uma rotacao de 180 graus em Z deixa o
 *    escudo em pe e com a face voltada para fora das costas (sem espelhar);
 *  - ItemRenderer desloca o item em (-0.5, -0.5, -0.5) e a placa fica 1.5px a frente da origem do modelo:
 *    translate(0.5, 0.5, 0.40625) coloca o CENTRO da placa exatamente no pivo da nossa transformacao.
 */
@Environment(EnvType.CLIENT)
public final class BackShieldRenderer implements TrinketRenderer {

    /** Superficie das costas do torso (2px / 16). */
    private static final float BACK_SURFACE = 0.125F;
    /** Centro da placa em Z dentro do referencial do item (1.5px / 16). */
    private static final float PLATE_CENTER_Z = 0.09375F;

    /**
     * Qual perfil vale agora. Prioridade: voando com elytra > mochila > elytra equipada > normal.
     * (Mochila + elytra em pe usam o perfil "backpack".)
     */
    public static String selectProfile(LivingEntity entity) {
        if (entity.isFallFlying()) {
            return "flying";
        }
        if (BackpackCompat.hasBackpack(entity)) {
            return "backpack";
        }
        if (EquipmentCompat.hasElytra(entity)) {
            return "elytra";
        }
        return "normal";
    }

    @Override
    public void render(ItemStack stack, SlotReference slotReference, EntityModel<? extends LivingEntity> contextModel,
                       MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, LivingEntity entity,
                       float limbAngle, float limbDistance, float tickDelta, float animationProgress,
                       float headYaw, float headPitch) {
        if (!(contextModel instanceof BipedEntityModel<?> biped)) {
            return;
        }
        // Cada situacao usa SEU PROPRIO perfil; nenhum valor e somado a outro perfil.
        BackShieldConfig.Profile p = BackShieldConfig.get().profile(selectProfile(entity));

        matrices.push();
        biped.body.rotate(matrices);

        matrices.translate(p.x, p.y, BACK_SURFACE + p.z);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(p.rotY));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(p.rotX));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180.0F + p.rotZ));
        float scale = 1.0F + p.scale;
        matrices.scale(scale, scale, scale);
        matrices.translate(0.5F, 0.5F, 0.5F - PLATE_CENTER_Z);

        MinecraftClient.getInstance().getItemRenderer().renderItem(stack, ModelTransformationMode.NONE, light,
                OverlayTexture.DEFAULT_UV, matrices, vertexConsumers, entity.getWorld(), entity.getId());
        matrices.pop();
    }
}
