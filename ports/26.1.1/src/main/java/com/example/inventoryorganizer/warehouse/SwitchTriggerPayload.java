package com.example.inventoryorganizer.warehouse;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client → server: player pressed the Switch trigger keybind.
 * The server re-validates the target (range check) and performs the tool swap if valid.
 *
 * <p>type: TYPE_BLOCK / TYPE_ENTITY / TYPE_AIR / TYPE_AIR_CAT. Every type carries the player's own
 * switchSlot so a dedicated server applies each player's configured slot (not one shared server value):
 * TYPE_BLOCK:    (x, y, z) = block position; entityId = switchSlot.
 * TYPE_ENTITY:   x = switchSlot; entityId = entity ID.
 * TYPE_AIR:      x = switchSlot, y = fromSlot — server swaps those two slots (restore).
 * TYPE_AIR_CAT:  x = switchSlot, entityId = category index (0=pickaxe,1=axe,2=shovel,3=hoe,4=mob).
 */
public record SwitchTriggerPayload(byte targetType, int x, int y, int z, int entityId)
        implements CustomPacketPayload {

    public static final byte TYPE_BLOCK   = 0;
    public static final byte TYPE_ENTITY  = 1;
    public static final byte TYPE_AIR     = 2;
    public static final byte TYPE_AIR_CAT = 3;

    public static final String[] AIR_CATEGORIES = {"pickaxe", "axe", "shovel", "hoe", "mob"};

    /** Max reach distance (blocks) the server accepts — vanilla reach is ~4.5 blocks. */
    public static final double MAX_REACH_SQ = 25.0; // 5 blocks squared

    public static final Type<SwitchTriggerPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "switch_trigger"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SwitchTriggerPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BYTE,     SwitchTriggerPayload::targetType,
                    ByteBufCodecs.VAR_INT,  SwitchTriggerPayload::x,
                    ByteBufCodecs.VAR_INT,  SwitchTriggerPayload::y,
                    ByteBufCodecs.VAR_INT,  SwitchTriggerPayload::z,
                    ByteBufCodecs.VAR_INT,  SwitchTriggerPayload::entityId,
                    SwitchTriggerPayload::new);

    @Override
    public Type<SwitchTriggerPayload> type() { return TYPE; }
}
