package net.backshield.network;

import net.backshield.BackShieldMain;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** Cliente -> servidor. O servidor decide tudo; o cliente so pede a acao. */
public record ShieldActionPayload(Action action) implements CustomPayload {

    public enum Action {
        /** Saca o escudo das costas para a mao secundaria (ou guarda de volta). */
        TOGGLE,
        /** Tecla de defesa pressionada. */
        BLOCK_START,
        /** Tecla de defesa solta (ou tela aberta). */
        BLOCK_STOP;

        public static Action byOrdinal(int ordinal) {
            Action[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
        }
    }

    public static final CustomPayload.Id<ShieldActionPayload> ID = new CustomPayload.Id<>(BackShieldMain.id("shield_action"));

    public static final PacketCodec<RegistryByteBuf, ShieldActionPayload> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeVarInt(value.action.ordinal()),
            buf -> new ShieldActionPayload(Action.byOrdinal(buf.readVarInt())));

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
