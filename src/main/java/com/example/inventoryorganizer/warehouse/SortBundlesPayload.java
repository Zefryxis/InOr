package com.example.inventoryorganizer.warehouse;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Client → server: sort bundles according to per-bundle profile rules.
 * For each assigned bundle the server (1) removes items that do NOT match the rules,
 * putting them back in the player's inventory, then (2) inserts items that DO match from
 * the inventory. The full operation is atomic server-side (no click simulation).
 *
 * <p>Rules are pre-expanded client-side: cg:/g: rules are resolved to their item-ID lists
 * before packing so the server does not need the client's OrganizerConfig.
 */
public record SortBundlesPayload(List<BundleAssignment> bundles) implements CustomPacketPayload {

    public static final Type<SortBundlesPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("inventory-organizer", "sort_bundles"));

    /** Safety cap: at most one bundle per inventory slot. */
    public static final int MAX_BUNDLES = 36;
    /** Safety cap: max expanded rules per bundle (each item-id is ~40 chars). */
    public static final int MAX_RULES = 512;
    /** Safety cap: max chars per rule string. */
    public static final int MAX_RULE_LEN = 128;

    /**
     * One bundle's assignment: the player-inventory slot holding the bundle + the list of rules
     * that defines what should stay in (or go into) this bundle.
     */
    public record BundleAssignment(int slot, List<String> rules) {
        public static final StreamCodec<RegistryFriendlyByteBuf, BundleAssignment> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, BundleAssignment::slot,
                        ByteBufCodecs.stringUtf8(MAX_RULE_LEN).apply(ByteBufCodecs.list(MAX_RULES)),
                                BundleAssignment::rules,
                        BundleAssignment::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SortBundlesPayload> CODEC =
            StreamCodec.composite(
                    BundleAssignment.CODEC.apply(ByteBufCodecs.list(MAX_BUNDLES)),
                    SortBundlesPayload::bundles,
                    SortBundlesPayload::new);

    @Override
    public Type<SortBundlesPayload> type() { return TYPE; }
}
