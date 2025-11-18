# Falcraft - Hunyuan Edition v1.0.0

## 🎯 Major Update: Tencent Hunyuan 3D Integration

This is a major update that migrates the 3D model generation service from fal.ai Meshy to **Tencent Hunyuan 3D API**, providing better Chinese language support and localized experience.

---

## ✨ What's New

### New Features
- **Tencent Hunyuan 3D API** - Native Chinese AI model for 3D generation
- **Chinese Prompt Support** - Full native Chinese text support
- **GLB Format Enforcement** - Guaranteed GLB output for best compatibility
- **Region Configuration** - Configurable API region (default: South China - Guangzhou)

### Technical Improvements
- ✅ Complete TC3-HMAC-SHA256 signature implementation
- ✅ Async job submission and polling (up to 15 minutes)
- ✅ ResultFormat=GLB parameter for guaranteed GLB output
- ✅ Fixed Minecraft 1.21.1 texture extraction compatibility
- ✅ Fixed HttpClient Host header restriction error

---

## 📦 Installation

### Requirements
- **Minecraft**: 1.21.1
- **Fabric Loader**: 0.17.3+
- **Fabric API**: 0.107.0+1.21.1

### Setup

1. Download `falcraft-hunyuan-edition-1.0.0-hunyuan.jar`
2. Place in `.minecraft/mods/` folder
3. Create `.env` file in `.minecraft/` directory:

```env
# Tencent Cloud credentials (for 3D generation)
TENCENT_SECRET_ID=your_secret_id_here
TENCENT_SECRET_KEY=your_secret_key_here

# fal.ai credentials (optional, for texture remix)
FAL_API_KEY=your_fal_api_key
```

4. Get credentials from: https://console.cloud.tencent.com/cam/capi
5. Enable Hunyuan 3D service: https://console.cloud.tencent.com/hunyuan3d

---

## 🎮 Usage

### Generate 3D Models (Tencent Hunyuan)
```
/fal generate <size> <prompt>
```

**Examples**:
```
/fal generate 32 a cute cat
/fal generate 48 古代中式宫殿
/fal generate 64 cyberpunk robot
```

**Recommended sizes**:
- `16-32` - Quick testing
- `48` - Balanced quality (recommended)
- `64` - High detail
- `96-128` - Maximum detail

### Remix Block Textures (fal.ai - unchanged)
```
/fal remix <prompt>
```

---

## 🐛 Bug Fixes

1. **Fixed "restricted header name: Host" error**
   - Removed manual Host header setting
   - Now handled automatically by HttpClient

2. **Fixed "NoSuchFieldException: byMipLevel" error**
   - Added multiple compatibility methods for texture extraction
   - Full Minecraft 1.21.1 support

---

## 📝 What Changed

| Feature | Original (fal.ai) | Hunyuan Edition |
|---------|------------------|-----------------|
| **3D API** | fal.ai Meshy | Tencent Hunyuan 3D |
| **Chinese Support** | Translation needed | ✅ Native |
| **Authentication** | API Key | SecretId + SecretKey |
| **Model Format** | Auto-select | ✅ Forced GLB |
| **Generation Time** | 5-10 min | 5-10 min |
| **Texture Remix** | ✅ fal.ai | ✅ fal.ai (kept) |
| **Region Config** | ❌ | ✅ Configurable |

---

## 📋 Files

- **falcraft-hunyuan-edition-1.0.0-hunyuan.jar** (~64KB) - Main mod file
- **falcraft-hunyuan-edition-1.0.0-hunyuan-sources.jar** - Source code (optional)

---

## ⚠️ Important Notes

1. **Generation Time**: 3D generation takes 5-10 minutes, please be patient
2. **API Quota**: Default 1 concurrent job, wait for completion before next request
3. **Network**: Requires access to `ai3d.tencentcloudapi.com`
4. **Security**: Never share your credentials or commit `.env` to git

---

## 📖 Documentation

- **README.md** - Project overview
- **API_CONFIG.md** - API configuration details
- **ENV_SETUP.md** - Environment setup guide
- **CONTRIBUTING.md** - Contribution guidelines

---

## 🔄 Full Changelog

### Added
- Tencent Hunyuan 3D API integration
- TencentCloudSigner.java (TC3-HMAC-SHA256 signature)
- HunyuanAPI.java (API client)
- API_CONFIG.md documentation
- .env.example configuration template

### Changed
- GenerateCommand.java - Use HunyuanAPI instead of FalAPI
- ClientTextureGrabber.java - MC 1.21.1 compatibility
- README.md - Updated for Hunyuan edition
- ENV_SETUP.md - Updated setup guide
- gradle.properties - Version to 1.0.0-hunyuan
- fabric.mod.json - Mod name to "Falcraft - 混元版"

### Fixed
- HttpClient Host header restriction error
- Texture extraction field name compatibility

---

## 🙏 Credits

- Original Falcraft: https://github.com/blendi-remade/falcraft
- Tencent Hunyuan 3D: https://cloud.tencent.com/product/hunyuan3d
- Minecraft Fabric Community

---

## 📜 License

This project is licensed under CC0-1.0

---

**Enjoy AI-powered Minecraft creation!** 🎮✨
