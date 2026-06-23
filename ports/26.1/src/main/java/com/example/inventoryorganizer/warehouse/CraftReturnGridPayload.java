package com.example.inventoryorganizer.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Client → server (remote crafting): cancel the current recipe — send every ingredient sitting in the
 * crafting grid back to the player's nearby chests, one stack at a time, sorting (OST) each chest an
 * item lands in. Anything that doesn't fit stays in the grid. Server reads the grid from the player's
 * open crafting menu and re-validates reach/interaction per chest (see {@link RemoteStock}).
 */
public record CraftReturnGridPayload(List<BlockPos> chests) implements CustomPacketPayload {

    public static final int MAX = 256;

    public static final Type<CraftReturnGridPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "craft_return_grid"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftReturnGridPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX)), CraftReturnGridPayload::chests,
                    CraftReturnGridPayload::new);

    @Override
    public Type<CraftReturnGridPayload> type() { return TYPE; }
}
