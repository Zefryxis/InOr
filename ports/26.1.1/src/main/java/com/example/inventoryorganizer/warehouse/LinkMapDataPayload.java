package com.example.inventoryorganizer.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server → client reply to {@link WarehouseMapQueryPayload} in the simplified link model:
 * <ul>
 *   <li>{@code ownLinkChests} — every chest of links the player OWNS (editable on the map).</li>
 *   <li>{@code foreignLinkChests} — every chest of links owned by OTHERS that have been revealed to
 *       this player (because they opened one of the link's chests). Sort-only, never editable.</li>
 *   <li>{@code foreignOwnerNames} — parallel to {@code foreignLinkChests}: each chest's link owner
 *       name (for the "X's warehouse — sort only" label).</li>
 * </ul>
 * Non-linked chests carry no per-player visibility anymore — profiles are purely local/private.
 */
public record LinkMapDataPayload(List<BlockPos> ownLinkChests,
                                 List<BlockPos> foreignLinkChests,
                                 List<String> foreignOwnerNames) implements CustomPacketPayload {

    public static final int MAX = 4096;

    public static final Type<LinkMapDataPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "warehouse_link_map_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LinkMapDataPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX)), LinkMapDataPayload::ownLinkChests,
                    BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX)), LinkMapDataPayload::foreignLinkChests,
                    ByteBufCodecs.stringUtf8(128).apply(ByteBufCodecs.list(MAX)), LinkMapDataPayload::foreignOwnerNames,
                    LinkMapDataPayload::new);

    @Override
    public Type<LinkMapDataPayload> type() { return TYPE; }
}
