package com.falcraft.network;

import com.falcraft.util.BlockMapper;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class StructurePlacementNetworking {

    public record BlockData(
            int x,
            int y,
            int z,
            int color
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
                buf.writeVarInt(block.x);
                buf.writeVarInt(block.y);
                buf.writeVarInt(block.z);
                buf.writeInt(block.color);
            }
        }

        private static PlaceStructurePayload read(RegistryFriendlyByteBuf buf) {
            BlockPos origin = buf.readBlockPos();

            int count = buf.readVarInt();

            List<BlockData> blocks = new ArrayList<>(count);

            for (int i = 0; i < count; i++) {
                blocks.add(
                        new BlockData(
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readInt()
                        )
                );
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

                        // Security: only allow creative-mode players or server operators
                        // to place arbitrary blocks via this packet.
                        if (!player.isCreative() && !context.server().getPlayerList().isOp(player.getGameProfile())) {
                            player.sendSystemMessage(
                                net.minecraft.network.chat.Component.literal(
                                    "[Falcraft] Structure placement requires Creative mode or operator status."
                                )
                            );
                            return;
                        }

                        payload.blocks().forEach(block -> {

                            BlockPos pos = payload.origin().offset(
                                    block.x(),
                                    block.y(),
                                    block.z()
                            );

                            BlockState state =
                                    BlockMapper.getClosestBlock(block.color());

                            player.serverLevel().setBlock(
                                    pos,
                                    state,
                                    3
                            );
                        });
                    });
                }
        );
    }
}