package com.example.inventoryorganizer.warehouse;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → client handshake packet. The server (integrated server in single-player, or a dedicated
 * server that runs InOr) sends this to every joining player. Receiving it is how the client learns
 * the warehouse subsystem is available here — see {@link WarehouseClient}. Carries a protocol
 * version so future changes can be negotiated.
 */
public record WarehouseHelloPayload(int protocol) implements CustomPacketPayload {

    public static final Type<WarehouseHelloPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "warehouse_hello"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WarehouseHelloPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, WarehouseHelloPayload::protocol,
                    WarehouseHelloPayload::new);

    @Override
    public Type<WarehouseHelloPayload> type() {
        return TYPE;
    }
}
