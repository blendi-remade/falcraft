# 🎨 falcraft - AI-Powered 3D Generation for Minecraft

A Fabric mod for Minecraft 1.21.1 that brings 3D model generation directly into your game. Powered by [fal.ai](https://fal.ai).

[![GitHub stars](https://img.shields.io/github/stars/blendi-remade/falcraft)](https://github.com/blendi-remade/falcraft/stargazers)
![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen)
![Fabric API](https://img.shields.io/badge/Fabric%20API-0.107.0-blue)

## 📺 Quick Demo

[![Watch the demo](https://img.youtube.com/vi/j2dOOceVniY/hqdefault.jpg)](https://www.youtube.com/watch?v=j2dOOceVniY)

**[▶️ Watch on YouTube](https://www.youtube.com/watch?v=j2dOOceVniY)** - See falcraft in action!

## 🚀 Quick Start

1. **[⬇️ Download Falcraft](https://github.com/blendi-remade/falcraft/releases/download/v1.0.0/falcraft-1.0.0.jar)**
2. **[⬇️ Download Fabric API](https://modrinth.com/mod/fabric-api/version/0.116.7+1.21.1)**
3. Drop both JARs in `.minecraft/mods/`
4. Run `/fal setkey "YOUR_KEY"` ([get key here](https://fal.ai/dashboard/keys))

📖 [Full installation guide](https://github.com/blendi-remade/falcraft/releases/tag/v1.0.0) | Requires Minecraft 1.21.1 + Fabric Loader

## 🎮 Commands

### Generate 3D Structures

```
/fal generate <size> <prompt>
```

**Examples:**
```
/fal generate 48 medieval castle
/fal generate 64 spongebob squarepants
/fal generate 96 ancient temple
```

**Controls in preview mode:**
- **Right-click** - Place the structure
- **G** - Rotate 90°

**Size guide:** 16-32 (small), 48-64 (recommended), 96-128 (large/detailed)

Generation takes ~30 seconds using Z-Image + SAM-3D pipeline.

### Stream Mode (Live Preview)

Watch structures emerge from noise in real-time!

```
/fal stream <size> <prompt>
```

**Examples:**
```
/fal stream 64 dragon statue
/fal stream 96 futuristic mech
/fal stream 80 medieval castle
```

Stream mode shows the SAM-3D diffusion process live - you'll see the shape form first (geometry phase), then colors appear (appearance phase). Powered by [Manifold](https://github.com/rehan-remade/Manifold), a real-time 3D streaming pipeline built on [fal Serverless](https://fal.ai).

**Controls:**
- **G** - Rotate during preview
- **Right-click** - Place when complete
- `/fal stream cancel` - Cancel generation

### Legacy Mode (Meshy-6)

For UV-textured models (~7 minutes):
```
/fal generate legacy <size> <prompt>
```

## ⚙️ Setup Details

1. Install [Fabric Loader](https://fabricmc.net/use/) + [Fabric API](https://modrinth.com/mod/fabric-api) for MC 1.21.1
2. Drop the mod JAR in `.minecraft/mods/`
3. Launch Minecraft and run:
   ```
   /fal setkey "YOUR_API_KEY"
   ```
   Get your API key at [fal.ai/dashboard/keys](https://fal.ai/dashboard/keys)
   
   **Note:** Wrap your API key in quotes to handle the special characters.

That's it! Your key is saved to `config/falcraft/api-key.txt`.

### Check Status
```
/fal status
```
Shows if your API key is configured.

## 💰 API Cost

This mod uses [fal.ai](https://fal.ai)'s cloud APIs. You'll need to [add credits](https://fal.ai/dashboard/billing) to your account.

### Cost Breakdown (Per Structure)

| Step | Model | Cost |
|------|-------|------|
| 1. Text → Image | Z-Image Turbo | ~$0.005-0.008 |
| 2. Image → 3D | SAM-3D Objects | $0.02 (flat) |
| 3. Prompt fix* | OpenRouter Vision | ~$0.001 |

**Total: ~$0.025-0.03 per generation** (regardless of Minecraft size)

*\*Only triggered when the 3D segmentation fails on complex/abstract prompts*

## 🧠 How It Works

1. **Z-Image Turbo** generates a 2D image from your prompt
2. **SAM-3D** converts the image to a 3D GLB model with vertex colors
3. **Voxelizer** converts the mesh to Minecraft blocks using triangle-voxel intersection
4. **Block Mapper** matches colors using CIE-LAB perceptual color space (100+ block palette)
5. **Ghost Preview** shows the structure before placement
6. **Animated Build** places blocks layer-by-layer

## 🐛 Troubleshooting

- **"API key not configured"** - Run `/fal setkey "YOUR_KEY"` (with quotes) or check with `/fal status`
- **403 error** - Your fal.ai account needs credits. [Add billing here](https://fal.ai/dashboard/billing)
- **Structure not visible** - Look where you want to place (up to 200 blocks away)
- **Colors look off** - The 100+ block palette maps colors as close as Minecraft allows

## 📜 License

CC0 1.0 - Public domain. Use freely!

Built with [Fabric](https://fabricmc.net/) and [fal.ai](https://fal.ai).
