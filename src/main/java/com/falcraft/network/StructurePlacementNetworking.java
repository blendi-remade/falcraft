package com.falcraft.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class StructurePlacementNetworking {

    /**
     * A single block in the structure packet.
     * blockId is a registry string like "minecraft:stone" — the client resolves
     * the color->block mapping (including any material filter) before sending,
     * so the server just looks up the id and places it directly.
     */
    public record BlockData(
            int x,
            int y,
            int z,
            String blockId
    ) {}

    public record PlaceStructurePayload(
            BlockPos origin,
            List<BlockData> blocks
    ) implements CustomPacketPayload {

        public static final Type<PlaceStructurePayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("falcraft", "place_structure"));

        public static final StreamCodec<RegistryFriendlyByteBuf, PlaceStructurePayload> CODEC =
                StreamCodec.of(
                        PlaceStructurePayload::write,
                        PlaceStructurePayload::read
                );

        private static void write(RegistryFriendlyByteBuf buf, PlaceStructurePayload payload) {
            buf.writeBlockPos(payload.origin);
            buf.writeVarInt(payload.blocks.size());
            for (BlockData block : payload.blocks) {
                buf.writeVarInt(block.x());
                buf.writeVarInt(block.y());
                buf.writeVarInt(block.z());
                buf.writeUtf(block.blockId());
            }
        }

        private static PlaceStructurePayload read(RegistryFriendlyByteBuf buf) {
            BlockPos origin = buf.readBlockPos();
            int count = buf.readVarInt();
            List<BlockData> blocks = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                blocks.add(new BlockData(
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readUtf()
                ));
            }
            return new PlaceStructurePayload(origin, blocks);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void initialize() {

        PayloadTypeRegistry.playC2S().register(
                PlaceStructurePayload.TYPE,
                PlaceStructurePayload.CODEC
        );

        ServerPlayNetworking.registerGlobalReceiver(
                PlaceStructurePayload.TYPE,
                (payload, context) -> {

                    ServerPlayer player = context.player();

                    context.server().execute(() -> {

                        // Security: only creative-mode players or operators may place blocks.
                        if (!player.isCreative() && !context.server().getPlayerList().isOp(player.getGameProfile())) {
                            player.sendSystemMessage(Component.literal(
                                "[Falcraft] Structure placement requires Creative mode or operator status."
                            ));
                            return;
                        }

                        payload.blocks().forEach(block -> {
                            BlockPos pos = payload.origin().offset(block.x(), block.y(), block.z());

                            // Look up block by registry ID — no color mapping needed server-side.
                            ResourceLocation loc = ResourceLocation.tryParse(block.blockId());
                            Block b = (loc != null) ? BuiltInRegistries.BLOCK.get(loc) : null;
                            BlockState state = (b != null && b != Blocks.AIR)
                                    ? b.defaultBlockState()
                                    : Blocks.STONE.defaultBlockState(); // safe fallback

                            player.serverLevel().setBlock(pos, state, 3);
                        });
                    });
                }
        );
    }
}
