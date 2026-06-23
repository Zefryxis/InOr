package com.example.inventoryorganizer.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Client → server: "here are all the chests I know about; tell me which I may show on my map."
 * The server replies with a {@link WarehouseMapDataPayload}. Lets the server hide other players'
 * private chests even though this client physically opened them.
 */
public record WarehouseMapQueryPayload(List<BlockPos> known) implements CustomPacketPayload {

    public static final int MAX = 4096;

    public static final Type<WarehouseMapQueryPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "warehouse_map_query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WarehouseMapQueryPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX)), WarehouseMapQueryPayload::known,
                    WarehouseMapQueryPayload::new);

    @Override
    public Type<WarehouseMapQueryPayload> type() { return TYPE; }
}
