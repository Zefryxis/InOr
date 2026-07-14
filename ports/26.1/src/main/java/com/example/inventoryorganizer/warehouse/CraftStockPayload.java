package com.example.inventoryorganizer.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server → client (remote crafting) reply to {@link CraftStockQueryPayload}: the nearby chests' contents
 * broken down PER CHEST, so the materials panel can show a section per chest (with its name) and a
 * withdrawal knows which chest to pull from.
 *
 * <p>Flat parallel-list encoding (one codec, no nested records): {@code chests}/{@code names}/{@code sizes}
 * are parallel — one entry per chest; {@code sizes[i]} = how many distinct item entries chest {@code i}
 * contributes to the flattened {@code itemIds}/{@code itemCounts}, read in chest order.
 */
public record CraftStockPayload(List<BlockPos> chests, List<String> names, List<Integer> sizes,
                                List<String> itemIds, List<Integer> itemCounts) implements CustomPacketPayload {

    public static final int MAX = 512;

    public static final Type<CraftStockPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "craft_stock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftStockPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX)), CraftStockPayload::chests,
                    ByteBufCodecs.stringUtf8(64).apply(ByteBufCodecs.list(MAX)), CraftStockPayload::names,
                    ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX)), CraftStockPayload::sizes,
                    ByteBufCodecs.stringUtf8(128).apply(ByteBufCodecs.list(MAX)), CraftStockPayload::itemIds,
                    ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX)), CraftStockPayload::itemCounts,
                    CraftStockPayload::new);

    @Override
    public Type<CraftStockPayload> type() { return TYPE; }
}
