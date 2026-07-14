package com.example.inventoryorganizer.warehouse;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → client: the mod-assigned UUID for the shulker box the player just opened. */
public record ShulkerIdPayload(String shulkerId) implements CustomPacketPayload {

    public static final Type<ShulkerIdPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "shulker_id"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShulkerIdPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), ShulkerIdPayload::shulkerId,
                    ShulkerIdPayload::new);

    @Override
    public Type<ShulkerIdPayload> type() { return TYPE; }
}
