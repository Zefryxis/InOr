package com.example.inventoryorganizer.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client → server: the link owner opens the OST-permission picker for the link containing {@code anyPos}.
 * The server replies with an {@link OstRosterPayload} (known players + their current OST grant).
 */
public record RequestOstRosterPayload(BlockPos anyPos) implements CustomPacketPayload {

    public static final Type<RequestOstRosterPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "warehouse_req_ost_roster"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestOstRosterPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RequestOstRosterPayload::anyPos,
                    RequestOstRosterPayload::new);

    @Override
    public Type<RequestOstRosterPayload> type() { return TYPE; }
}
