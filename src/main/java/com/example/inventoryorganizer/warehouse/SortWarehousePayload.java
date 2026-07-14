package com.example.inventoryorganizer.warehouse;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Client → server request to sort a warehouse group. Carries each member chest's position AND its
 * per-chest profile slot rules ({@link ChestRules}); the server reads each chest's contents and
 * routes items between them using only the rules provided here — so it needs no client config and
 * works on a real dedicated server.
 */
public record SortWarehousePayload(List<ChestRules> chests) implements CustomPacketPayload {

    public static final Type<SortWarehousePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "warehouse_sort"));

    /** Hard cap on chests per request — defends the server against a malicious huge packet. */
    public static final int MAX_CHESTS = 256;

    public static final StreamCodec<RegistryFriendlyByteBuf, SortWarehousePayload> CODEC =
            StreamCodec.composite(
                    ChestRules.CODEC.apply(ByteBufCodecs.list(MAX_CHESTS)), SortWarehousePayload::chests,
                    SortWarehousePayload::new);

    @Override
    public Type<SortWarehousePayload> type() {
        return TYPE;
    }
}
