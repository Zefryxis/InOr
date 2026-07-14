package com.example.inventoryorganizer.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S: client has just created/bound a profile to a shulker that had no UUID yet.
 * Server saves the UUID into the shulker's block entity NBT at the given position.
 */
public record ShulkerAssignPayload(BlockPos pos, String uuid) implements CustomPacketPayload {

    public static final Type<ShulkerAssignPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "shulker_assign"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShulkerAssignPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ShulkerAssignPayload::pos,
                    ByteBufCodecs.stringUtf8(64), ShulkerAssignPayload::uuid,
                    ShulkerAssignPayload::new);

    @Override
    public Type<ShulkerAssignPayload> type() { return TYPE; }
}
