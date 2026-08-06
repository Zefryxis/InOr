package com.example.inventoryorganizer.warehouse;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Client → server (remote crafting): deposit the stack the player is currently holding on the cursor
 * into one of their nearby known chests, then sort (OST) that chest USING ITS OWN CONFIGURED SLOT RULES
 * (not just a crude merge). {@code chests} is the candidate list — each chest's position plus its local
 * per-chest profile rules (empty list = unbound chest), same as a warehouse sort request; the server
 * re-validates distance + interaction for each and reads the held stack from the player's open menu, so
 * a hacked client can't deposit items it isn't actually holding or into a chest it couldn't reach.
 */
public record CraftDepositPayload(List<ChestRules> chests) implements CustomPacketPayload {

    public static final int MAX = 256;

    public static final Type<CraftDepositPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "craft_deposit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftDepositPayload> CODEC =
            StreamCodec.composite(
                    ChestRules.CODEC.apply(ByteBufCodecs.list(MAX)), CraftDepositPayload::chests,
                    CraftDepositPayload::new);

    @Override
    public Type<CraftDepositPayload> type() { return TYPE; }
}
