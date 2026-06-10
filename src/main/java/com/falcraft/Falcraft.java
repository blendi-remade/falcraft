package com.falcraft;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Falcraft implements ModInitializer {
	public static final String MOD_ID = "falcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
	  StructurePlacementNetworking.initialize();
		LOGGER.info("Falcraft initialized");
	}
}

