package com.example.inventoryorganizer.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * One chest's routing data sent from client to server with a warehouse sort request: the chest's
 * position plus its per-chest profile slot rules (one entry per slot, "any"/"empty"/"g:..."/"t:..."
 * /"namespace:item"). Sending the rules over the wire means the server never needs the client's
 * config — so the warehouse works on a real dedicated server, not just single-player.
 *
 * <p>An empty rule list = the chest has no profile (acts as an overflow chest).
 */
public record ChestRules(BlockPos pos, List<String> rules) {

    // Bounded to defend a dedicated server against malicious oversized packets: a chest has at most
    // 54 slots, and each rule string is short ("g:blocks", "minecraft:stone", …).
    public static final StreamCodec<RegistryFriendlyByteBuf, ChestRules> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ChestRules::pos,
            ByteBufCodecs.stringUtf8(64).apply(ByteBufCodecs.list(64)), ChestRules::rules,
            ChestRules::new);
}
