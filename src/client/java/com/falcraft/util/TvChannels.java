package com.falcraft.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FalTV channel presets. Each preset is a Director opening prompt: what the
 * continuous stream should be, in the model's own grammar. Speech is scripted
 * (the model babbles otherwise), so the presets keep dialogue rare and quoted.
 */
public final class TvChannels {
    private TvChannels() {}

    public record Channel(String label, String prompt) {}

    private static final String SOUND =
            "Ambient sound and light score only; any spoken words are only lines quoted here, in clear English.";

    private static final Map<String, Channel> CHANNELS = new LinkedHashMap<>();

    private static void add(String key, String label, String prompt) {
        CHANNELS.put(key, new Channel(label, prompt + " " + SOUND));
    }

    static {
        add("news", "Capybara News",
            "A glossy 24-hour cable news broadcast anchored by a calm capybara in a suit at a studio desk, a lower-third news ticker and a big screen behind showing live scenes, cutting between the anchor, field reporters, and breaking-news graphics. Broadcast-quality studio lighting, crisp and continuous.");
        add("minecraft", "Blockworld Live",
            "A continuous cinematic broadcast set entirely inside a Minecraft world: blocky voxel terrain, cubic trees, pixelated textures, a Steve-like character exploring caves, building, and fighting mobs, shot like an epic adventure show. Everything is made of cubes and pixel textures, bright and colorful.");
        add("cooking", "The Sizzle Channel",
            "A warm daytime cooking show in a bright studio kitchen, a cheerful chef plating a dish, chopping and searing in close-up, steam rising, a studio audience out of focus, glossy food-network lighting, smooth continuous camera.");
        add("nature", "Wild Planet",
            "A prestige nature documentary drifting over sweeping landscapes and wildlife: herds on golden plains at dawn, birds lifting off a lake, slow majestic aerials and intimate close-ups, ultra-detailed, golden light, a calm continuous glide.");
        add("horror", "Channel Static",
            "A late-night found-footage horror broadcast: a handheld camera moving through a dim abandoned house, doorways and hallways in a weak flashlight beam, wrongness at the edges of the frame, grainy VHS look, tension rising slowly. Nothing ever clearly seen.");
        add("space", "Orbital One",
            "A continuous live feed from a vast science-fiction space station: astronauts drifting down glowing corridors, a planet turning in the huge windows, sweeping views of docked ships and starfields, sleek and cinematic, cool blue light.");
        add("wrestling", "Slam TV",
            "A loud primetime pro-wrestling broadcast in a packed arena: spotlights, pyrotechnics, two costumed wrestlers grappling and leaping in the ring, a roaring crowd, dramatic slow-motion replays, saturated color, energetic continuous camera.");
        add("aquarium", "The Deep Channel",
            "A calm continuous aquarium feed: a giant tank of drifting jellyfish, schools of silver fish turning together, a sea turtle gliding past coral, soft blue light through the water, slow meditative camera. No people.");
    }

    public static boolean has(String key) {
        return CHANNELS.containsKey(key.toLowerCase());
    }

    public static Channel get(String key) {
        return CHANNELS.get(key.toLowerCase());
    }

    public static String[] keys() {
        return CHANNELS.keySet().toArray(new String[0]);
    }

    /** A freeform channel from a raw prompt, wrapped as a continuous broadcast. */
    public static Channel freeform(String prompt) {
        return new Channel("Your Channel",
            "A continuous television broadcast, cinematic and always in motion: " + prompt + ". " + SOUND);
    }
}
