package com.example.inventoryorganizer.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Client → server (remote crafting): "here are my known chests near the crafting table — tell me
 * their combined contents." The server re-validates each chest's distance and replies with a
 * {@link CraftStockPayload}.
 */
public record CraftStockQueryPayload(List<BlockPos> chests, boolean preferChests) implements CustomPacketPayload {

    public static final int MAX = 256;

    public static final Type<CraftStockQueryPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "craft_stock_query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftStockQueryPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX)), CraftStockQueryPayload::chests,
                    ByteBufCodecs.BOOL, CraftStockQueryPayload::preferChests,
                    CraftStockQueryPayload::new);

    @Override
    public Type<CraftStockQueryPayload> type() { return TYPE; }
}
