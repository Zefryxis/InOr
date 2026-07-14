package com.example.inventoryorganizer.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client → server (remote crafting): pull up to {@code amount} of {@code itemId} out of ONE specific
 * chest ({@code source}) into the player's inventory. The server re-validates that the chest is in reach,
 * not a foreign link, and interactable. Per-chest now, so the panel shows a section per chest and a
 * withdrawal targets exactly the chest the player clicked in.
 */
public record CraftWithdrawPayload(String itemId, int amount, BlockPos source) implements CustomPacketPayload {

    public static final Type<CraftWithdrawPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "craft_withdraw"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftWithdrawPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(128), CraftWithdrawPayload::itemId,
                    ByteBufCodecs.VAR_INT, CraftWithdrawPayload::amount,
                    BlockPos.STREAM_CODEC, CraftWithdrawPayload::source,
                    CraftWithdrawPayload::new);

    @Override
    public Type<CraftWithdrawPayload> type() { return TYPE; }
}
