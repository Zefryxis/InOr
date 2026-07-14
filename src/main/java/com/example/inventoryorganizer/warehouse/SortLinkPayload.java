package com.example.inventoryorganizer.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client → server: sort the whole warehouse link that contains {@code anyPos}, using the OWNER's
 * stored rules. Sent when a (possibly non-owner) player presses OST at a linked chest. The server
 * looks up the link, re-validates reach, and routes items via the existing warehouse engine.
 */
public record SortLinkPayload(BlockPos anyPos) implements CustomPacketPayload {

    public static final Type<SortLinkPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "warehouse_sort_link"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SortLinkPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SortLinkPayload::anyPos,
                    SortLinkPayload::new);

    @Override
    public Type<SortLinkPayload> type() { return TYPE; }
}
