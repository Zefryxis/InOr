package com.example.inventoryorganizer;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;

public final class InOrComponents {

    public static DataComponentType<String> SHULKER_ID;

    public static void register() {
        SHULKER_ID = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath(InventoryOrganizer.MOD_ID, "shulker_id"),
            DataComponentType.<String>builder()
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                .build()
        );
    }

    private InOrComponents() {}
}
