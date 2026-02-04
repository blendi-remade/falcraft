package com.falcraft.commands;

import com.falcraft.util.GLBParser;
import com.falcraft.util.PlacementPreview;
import com.falcraft.util.TextureSampler;
import com.falcraft.util.Voxelizer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Command to load a local .glb file from the disk and convert it to blocks.
 * Usage: /fal load <size> <file_path>
 * Example: /fal load 64 C:\Downloads\model.glb
 */
public class LoadCommand {
	private static final Logger LOGGER = LoggerFactory.getLogger("LoadCommand");

	// Suggest sizes for autocomplete
	private static final SuggestionProvider<FabricClientCommandSource> SIZE_SUGGESTIONS = (context, builder) -> {
		builder.suggest(16, Component.literal("Tiny"));
		builder.suggest(32, Component.literal("Small"));
		builder.suggest(48, Component.literal("Medium"));
		builder.suggest(64, Component.literal("Large"));
		builder.suggest(80, Component.literal("Extra Large"));
		builder.suggest(128, Component.literal("Maximum"));
		return builder.buildFuture();
	};

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("fal")
				.then(literal("load")
						.then(argument("size", IntegerArgumentType.integer(16, 128))
								.suggests(SIZE_SUGGESTIONS)
								.then(argument("path", StringArgumentType.greedyString())
										.executes(LoadCommand::execute)))));
	}

	private static int execute(CommandContext<FabricClientCommandSource> context) {
		int size = IntegerArgumentType.getInteger(context, "size");
		String rawPath = StringArgumentType.getString(context, "path");
		FabricClientCommandSource source = context.getSource();

		// Clean up the path (Copying file paths in Windows often adds quotes)
		String cleanPathStr = rawPath.replace("\"", "");
		Path path = Paths.get(cleanPathStr);

		// Validate file existence
		if (!Files.exists(path)) {
			source.sendError(Component.literal("§c[fal] File not found: " + cleanPathStr));
			return 0;
		}

		// Validate extension
		String lowerPath = cleanPathStr.toLowerCase();
		if (!lowerPath.endsWith(".glb")) {
			source.sendFeedback(Component.literal("§6[fal] Warning: File does not appear to be a GLB model. Trying anyway..."));
		}

		source.sendFeedback(Component.literal("§e[fal] Loading local file (" + size + "x" + size + "x" + size + ")"));
		source.sendFeedback(Component.literal("§7File: " + path.getFileName()));

		// Run the generation process asynchronously to avoid blocking the game thread
		new Thread(() -> {
			try {
				// Step 1: Read bytes
				Minecraft.getInstance().execute(() ->
						source.sendFeedback(Component.literal("§e[fal] Reading file...")));

				byte[] glbData = Files.readAllBytes(path);

				// Step 2: Extract Texture (if present)
				TextureSampler textureSampler = null;
				try {
					Minecraft.getInstance().execute(() ->
							source.sendFeedback(Component.literal("§e[fal] Checking for embedded textures...")));

					byte[] embeddedTexture = GLBParser.extractEmbeddedTexture(glbData);

					if (embeddedTexture != null) {
						textureSampler = new TextureSampler(embeddedTexture);
						Minecraft.getInstance().execute(() ->
								source.sendFeedback(Component.literal("§e[fal] Texture loaded successfully!")));
					} else {
						Minecraft.getInstance().execute(() ->
								source.sendFeedback(Component.literal("§6[fal] No embedded texture found, using vertex colors.")));
					}
				} catch (Exception e) {
					LOGGER.error("Failed to extract texture from local file", e);
					Minecraft.getInstance().execute(() ->
							source.sendFeedback(Component.literal("§6[fal] Warning: Texture extraction failed.")));
				}

				// Step 3: Parse GLB file with texture sampling
				Minecraft.getInstance().execute(() ->
						source.sendFeedback(Component.literal("§e[fal] Parsing 3D model...")));

				GLBParser.MeshData meshData = GLBParser.parse(glbData, textureSampler);

				// Step 4: Voxelize the mesh with per-voxel texture sampling
				final TextureSampler finalTextureSampler = textureSampler;
				Minecraft.getInstance().execute(() ->
						source.sendFeedback(Component.literal("§e[fal] Converting to voxels (" +
								size + "x" + size + "x" + size + ")...")));

				// Pass the texture sampler to enable per-voxel UV-based color sampling
				Voxelizer.VoxelGrid voxelGrid = Voxelizer.voxelize(meshData, size, finalTextureSampler);
				LOGGER.info("Voxelized mesh: {} voxels", voxelGrid.voxels().size());

				if (voxelGrid.voxels().isEmpty()) {
					Minecraft.getInstance().execute(() ->
							source.sendError(Component.literal("§c[fal] Error: Generated model has no voxels!")));
					return;
				}

				// Step 5: Enter placement preview mode (MUST run on main thread)
				Minecraft.getInstance().execute(() -> {
					try {
						PlacementPreview.startPlacement(voxelGrid);

						source.sendFeedback(Component.literal(
								"§a[fal] ✓ Loading complete! " + voxelGrid.voxels().size() + " blocks ready."));
						source.sendFeedback(Component.literal(
								"§e[fal] Right-click to place, G to rotate!"));
					} catch (Exception e) {
						String errorMsg = e.getMessage();
						source.sendError(Component.literal("§c[fal] Error preparing placement: " + errorMsg));
						LOGGER.error("Error preparing placement", e);
					}
				});

			} catch (Exception e) {
				String errorMsg = e.getMessage();
				Minecraft.getInstance().execute(() ->
						source.sendError(Component.literal("§c[fal] Error processing file: " + errorMsg)));
				LOGGER.error("Fatal error in local load thread", e);
			}
		}, "fal-LoadCommand-Thread").start();

		return 1;
	}
}