package com.example.inventoryorganizer.warehouse;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Client → server: the link owner uploads/refreshes a warehouse link. Carries the link's name and,
 * per member chest, its position + the owner's per-chest slot rules ({@link ChestRules}). The server
 * stores this so OTHER players who later open a chest of the link can OST it using the owner's rules,
 * without ever editing it themselves.
 */
public record UploadLinkPayload(String name, List<ChestRules> chests) implements CustomPacketPayload {

    public static final int MAX_CHESTS = 256;

    public static final Type<UploadLinkPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "warehouse_upload_link"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UploadLinkPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(128), UploadLinkPayload::name,
                    ChestRules.CODEC.apply(ByteBufCodecs.list(MAX_CHESTS)), UploadLinkPayload::chests,
                    UploadLinkPayload::new);

    @Override
    public Type<UploadLinkPayload> type() { return TYPE; }
}
