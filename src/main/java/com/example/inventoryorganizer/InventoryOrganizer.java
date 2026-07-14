package com.example.inventoryorganizer;

import com.example.inventoryorganizer.InOrComponents;
import com.example.inventoryorganizer.ShulkerIdHolder;
import com.example.inventoryorganizer.warehouse.ShulkerIdPayload;
import com.example.inventoryorganizer.warehouse.WarehouseNet;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Common entrypoint — runs on BOTH the client and the (integrated/dedicated) server. Hosts the
 * server-side + common parts of the warehouse subsystem. Keep this strictly server-safe: do NOT
 * reference any client-only class from here (it would crash a dedicated server on class-load).
 */
public class InventoryOrganizer implements ModInitializer {
    public static final String MOD_ID = "inventory-organizer";

    @Override
    public void onInitialize() {
        // Register custom DataComponentType for shulker UUID persistence (break→item→place).
        InOrComponents.register();

        // Warehouse handshake: register payload types (both sides) + greet joining players (server).
        WarehouseNet.registerCommon();
        WarehouseNet.registerServer();

        // Shulker box identity: assign a mod UUID the first time a player opens each shulker box.
        // The UUID lives in the block entity NBT (saved via ShulkerBoxBlockEntityMixin), so it
        // survives break → item → place cycles through the BlockEntityData item component.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !(hitResult instanceof BlockHitResult bhr)
                    || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            var pos = bhr.getBlockPos();
            if (!(world.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock)) {
                return InteractionResult.PASS;
            }
            if (!(world.getBlockEntity(pos) instanceof ShulkerIdHolder holder)) {
                return InteractionResult.PASS;
            }
            String id = holder.inor$getShulkerId();
            if (id == null || id.isEmpty()) {
                id = java.util.UUID.randomUUID().toString();
                holder.inor$setShulkerId(id);
            }
            ServerPlayNetworking.send(serverPlayer, new ShulkerIdPayload(id));
            return InteractionResult.PASS;
        });
    }
}
