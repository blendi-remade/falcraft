# 🎨 falcraft - AI-Powered 3D Generation & Texture Remix for Minecraft

A Fabric mod for Minecraft 1.21.1 that brings AI-powered 3D model generation and texture remixing directly into your game! Now powered by **Tencent Hunyuan 3D** for cost-effective 3D generation. Generate entire 3D structures from text prompts, or remix any block's texture in real-time.

[![GitHub issues](https://img.shields.io/github/issues/blendi-remade/falcraft)](https://github.com/blendi-remade/falcraft/issues)
[![GitHub stars](https://img.shields.io/github/stars/blendi-remade/falcraft)](https://github.com/blendi-remade/falcraft/stargazers)
![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen)
![Fabric API](https://img.shields.io/badge/Fabric%20API-0.107.0-blue)
![License](https://img.shields.io/badge/License-CC0-lightgrey)

## 🎥 See It In Action

Watch as we transform Minecraft with AI-powered generation:

[![Watch the Demo](https://img.youtube.com/vi/2xAbEnfF1SM/maxresdefault.jpg)](https://www.youtube.com/watch?v=2xAbEnfF1SM)

## ✨ Features

- 🏗️ **3D Model Generation**: Generate complete 3D structures from text prompts
- 🎨 **Perceptual Color Matching**: Uses LAB color space for human-vision-accurate block selection
- 🎯 **Texture Remixing**: Point at any block and remix its texture with AI
- 🤖 **Powered by Tencent Hunyuan 3D**: Cost-effective 3D generation with high quality
- ⚡ **Dynamic Resource Packs**: Texture changes apply instantly - no restart needed
- 🧵 **Non-Blocking**: All processing runs in background threads

## 🎮 Usage

### Generate 3D Models (NEW!)

Create entire structures from text descriptions:

```
/fal generate <size> <prompt>
```

**Examples:**
```
/fal generate 32 a cute robot
/fal generate 48 medieval castle with towers
/fal generate 64 majestic desert palace
/fal generate 128 ancient dragon statue
```

**Size Guide:**
- **16-32**: Fast testing, rough shapes
- **48**: Balanced detail/speed (recommended)
- **64**: High detail
- **96-128**: Maximum detail (slower placement)

**How it works:**
1. Tencent Hunyuan 3D generates a textured GLB model (5-10 minutes)
2. GLB file is parsed to extract vertices, colors, and textures
3. Model is voxelized into Minecraft block grid
4. Colors are mapped using perceptual LAB color space
5. Structure is placed flat on the ground in your look direction

### Remix Block Textures

Transform existing block textures with AI:

```
/fal remix <prompt>
```

**Examples:**
```
/fal remix glowing alien texture
/fal remix mossy ancient ruins
/fal remix cyberpunk neon
```

## 🚀 Quick Start

1. **Prerequisites**:
   - Minecraft 1.21.1 + [Fabric Loader](https://fabricmc.net/use/) + [Fabric API](https://modrinth.com/mod/fabric-api)
   - [Java 21+](https://adoptium.net/temurin/releases/)

2. **Get Credentials**:
   - Sign up at [Tencent Cloud](https://console.cloud.tencent.com/cam)
   - Complete real-name verification
   - Activate [Hunyuan 3D](https://console.cloud.tencent.com/hunyuan3d) service
   - Get your SecretId and SecretKey

3. **Configure**:
   - Create `.env` in `.minecraft/` directory:
   ```
   TENCENT_SECRET_ID=your_secret_id
   TENCENT_SECRET_KEY=your_secret_key
   ```

4. **Install**:
   - Download from [Releases](https://github.com/blendi-remade/falcraft/releases)
   - Place JAR in `.minecraft/mods/`
   - Launch Minecraft!

### For Developers

```bash
git clone https://github.com/blendi-remade/falcraft.git
cd falcraft
echo "TENCENT_SECRET_ID=your_id" > run/.env
echo "TENCENT_SECRET_KEY=your_key" >> run/.env
./gradlew runClient
```

## 🧠 Technical Overview

### 3D Generation Pipeline

1. **Tencent Hunyuan 3D** generates textured GLB model from text prompt (via `ResultFormat=GLB` parameter)
2. **GLB Parsing** extracts vertices, indices, UV coordinates, and embedded textures
3. **Texture Extraction** pulls embedded textures from GLB binary for color sampling
4. **Voxelization** converts smooth mesh into Minecraft block grid
5. **Perceptual Color Matching** uses LAB color space (matches human vision, not just RGB math)
6. **Smart Placement** finds ground and places structure flat

### Texture Remixing Pipeline

1. **Raycast** finds target block and extracts texture
2. **AI Texture Editor** remixes texture with your prompt (fal.ai nano-banana)
3. **Dynamic Resource Pack** applies changes instantly

### Why LAB Color Space?

Instead of simple RGB distance, we use **CIE LAB color space**:
- Matches how humans actually perceive color differences
- Prevents bad matches (e.g., red → orange just because RGB distance is small)
- Uses D65 illuminant for realistic daylight matching
- Results in more accurate and natural-looking block selection

## 🐛 Troubleshooting

**"Tencent Cloud credentials not found"**
- Create `.env` file in `.minecraft/` directory with:
  ```
  TENCENT_SECRET_ID=your_id
  TENCENT_SECRET_KEY=your_key
  ```

**Texture doesn't change**
- Check `logs/latest.log` for API errors
- Verify credentials are valid
- Ensure Hunyuan 3D service is activated
- Press F3+T to force reload

**Model placement issues**
- Structures place in your horizontal look direction
- Automatically finds ground and sits flat
- Ensure you're looking at an area with ground nearby

**Region configuration**
- Default region: `ap-guangzhou` (South China - Guangzhou)
- To change region, modify `REGION` constant in `HunyuanAPI.java`

**Model format configuration**
- API is configured to return GLB format (`ResultFormat=GLB`)
- GLB is the only format supported by GLBParser
- Other available formats: OBJ, STL, USDZ, FBX, MP4 (require parser implementation)

## 🤝 Contributing

Ideas welcome! Fork, create a feature branch, test with `./gradlew runClient`, and open a PR.

**Feature Ideas:**
- Undo/history for textures and models
- Preset prompts library
- Entity/item texture support
- Model scaling and rotation commands

## 📜 License & Credits

**CC0 1.0 Universal** - Public domain. Use freely, modify, redistribute, no attribution required!

Built with [Fabric](https://fabricmc.net/), [Tencent Hunyuan 3D](https://cloud.tencent.com/product/hunyuan3d), and [Mojang Mappings](https://github.com/FabricMC/yarn).

## 📞 Support & Links

- 🐛 [Report Issues](https://github.com/blendi-remade/falcraft/issues)
- 💬 [Discussions](https://github.com/blendi-remade/falcraft/discussions)
- ⭐ Star the repo if you like it!

---

*Transform your Minecraft world with AI!* ✨🏗️
