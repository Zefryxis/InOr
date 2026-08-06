package com.example.inventoryorganizer.warehouse;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → client: this server supports the auto-switch feature. No data — presence is the signal. */
public record SwitchCapabilityPayload() implements CustomPacketPayload {

    public static final Type<SwitchCapabilityPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "switch_capability"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SwitchCapabilityPayload> CODEC =
            StreamCodec.unit(new SwitchCapabilityPayload());

    @Override
    public Type<SwitchCapabilityPayload> type() { return TYPE; }
}
