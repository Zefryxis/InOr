package com.example.inventoryorganizer.warehouse;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Client → server: the player's custom-group membership (name → ordered item ids), so the server-side
 * warehouse/OST sort can honour groups the player edited locally. On a dedicated server the server's own
 * {@code OrganizerConfig} is empty, so without this the group-routing fell back to the data heuristic and
 * ignored items the player hand-added to a group. Sent on the warehouse handshake and right before each
 * sort request, then cached per-player in {@link WarehouseNet}.
 *
 * <p>Flat parallel-list encoding: {@code names}/{@code sizes} are parallel (one entry per group);
 * {@code sizes[i]} = how many ids group {@code i} contributes to the flattened {@code ids} (read in order).
 */
public record SyncGroupsPayload(List<String> names, List<Integer> sizes, List<String> ids)
        implements CustomPacketPayload {

    /** Defensive caps (the server also re-caps when reading). */
    public static final int MAX_GROUPS = 128;
    public static final int MAX_IDS = 8192;

    public static final Type<SyncGroupsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "sync_groups"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncGroupsPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64).apply(ByteBufCodecs.list(MAX_GROUPS)), SyncGroupsPayload::names,
                    ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_GROUPS)), SyncGroupsPayload::sizes,
                    ByteBufCodecs.stringUtf8(128).apply(ByteBufCodecs.list(MAX_IDS)), SyncGroupsPayload::ids,
                    SyncGroupsPayload::new);

    @Override
    public Type<SyncGroupsPayload> type() { return TYPE; }
}
