package com.example.inventoryorganizer.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server → client reply to {@link RequestOstRosterPayload}: for the link containing {@code anyPos}, the
 * list of known players (UUID + name) and, per player, whether they are currently allowed to OST it.
 */
public record OstRosterPayload(BlockPos anyPos, List<String> uuids, List<String> names,
                               List<Boolean> allowed) implements CustomPacketPayload {

    public static final int MAX = 512;

    public static final Type<OstRosterPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "warehouse_ost_roster"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OstRosterPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, OstRosterPayload::anyPos,
                    ByteBufCodecs.stringUtf8(64).apply(ByteBufCodecs.list(MAX)), OstRosterPayload::uuids,
                    ByteBufCodecs.stringUtf8(64).apply(ByteBufCodecs.list(MAX)), OstRosterPayload::names,
                    ByteBufCodecs.BOOL.apply(ByteBufCodecs.list(MAX)), OstRosterPayload::allowed,
                    OstRosterPayload::new);

    @Override
    public Type<OstRosterPayload> type() { return TYPE; }
}
