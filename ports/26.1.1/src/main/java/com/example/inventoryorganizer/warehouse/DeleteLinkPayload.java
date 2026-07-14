package com.example.inventoryorganizer.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client → server: the owner deletes the warehouse link that contains {@code anyPos}. Only the link's
 * creator may delete it.
 */
public record DeleteLinkPayload(BlockPos anyPos) implements CustomPacketPayload {

    public static final Type<DeleteLinkPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "warehouse_delete_link"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DeleteLinkPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, DeleteLinkPayload::anyPos,
                    DeleteLinkPayload::new);

    @Override
    public Type<DeleteLinkPayload> type() { return TYPE; }
}
