package com.example.inventoryorganizer.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client → server: the link owner grants ({@code allow=true}) or revokes OST permission for
 * {@code target} on the link containing {@code anyPos}.
 */
public record SetOstPermPayload(BlockPos anyPos, String target, boolean allow) implements CustomPacketPayload {

    public static final Type<SetOstPermPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "warehouse_set_ost_perm"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetOstPermPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetOstPermPayload::anyPos,
                    ByteBufCodecs.stringUtf8(64), SetOstPermPayload::target,
                    ByteBufCodecs.BOOL, SetOstPermPayload::allow,
                    SetOstPermPayload::new);

    @Override
    public Type<SetOstPermPayload> type() { return TYPE; }
}
