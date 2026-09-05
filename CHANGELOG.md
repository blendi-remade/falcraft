# Changelog

All notable changes to Falcraft will be documented in this file.

## [Unreleased]

### Added
- **FalTV**: `/fal tv <channel|prompt>` plays an endless, live-generated TV broadcast (MiniMax H3 Max Director) on an in-world canvas, steered from chat with `!...` lines. `/fal tv off` stops it.
- `bridge/`: local Node + headless-Chrome sidecar that holds the Director WebRTC session and relays frames to the mod. `npm run start:audio` plays the broadcast's audio through your speakers.

### Fixed
- FalTV frames now decode via ImageIO. Minecraft's `NativeImage.read` is PNG-only in 1.21.1, so JPEG frames were silently dropped and the TV stayed black.

## [1.0.0] - 2024-12-08

### Added
- `/fal generate <size> <prompt>` - AI-powered 3D structure generation using Z-Image + SAM-3D pipeline (~30 seconds)
- `/fal generate legacy <size> <prompt>` - Original Meshy-6 pipeline for high-quality results (~7 minutes)
- `/fal remix` - AI texture remixing for existing blocks
- `/fal setkey <key>` - Configure your fal.ai API key in-game
- `/fal status` - Check API key configuration status
- Ghost block preview system showing the structure before placement
- Rotation support (press G to rotate 90°)
- Distance control (scroll wheel to adjust preview distance)
- Animated block-by-block placement
- Support for structures up to 128x128x128 blocks
- Expanded block palette with 105 Minecraft blocks for accurate color matching
- Smart VLM fallback: When abstract prompts fail SAM-3D segmentation, a Vision Language Model analyzes the image and provides a concrete description for retry

### Technical
- Triangle splitting voxelization algorithm (based on obj2voxel)
- GLB parsing with support for both UV-mapped textures and vertex colors
- CIE LAB color space matching for accurate block selection
- Surface voxel extraction for efficient preview rendering
- VLM-assisted retry logic using Gemini 2.5 Flash for improved segmentation reliability

