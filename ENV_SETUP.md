# Environment Setup

## Required: Tencent Cloud Credentials

Falcraft now uses Tencent Hunyuan 3D API. Here's how to set it up:

### 1. Get Your Credentials

1. Visit [Tencent Cloud Console](https://console.cloud.tencent.com/cam)
2. Sign up and complete real-name verification
3. Open [Hunyuan 3D Console](https://console.cloud.tencent.com/hunyuan3d) and activate the service
4. Get your SecretId and SecretKey from [API Keys](https://console.cloud.tencent.com/cam/capi)

### 2. Create `.env` File

Create a file named `.env` in one of these locations:

**For Development:**
```
falcraft/run/.env
```

**For Production (Players):**
```
.minecraft/.env
```

**Full paths by OS:**
- Windows: `C:\Users\YourName\AppData\Roaming\.minecraft\.env`
- Linux: `~/.minecraft/.env`
- macOS: `~/Library/Application Support/minecraft/.env`

### 3. Add Your Credentials

Open the `.env` file in a text editor and add:

```
TENCENT_SECRET_ID=your_secret_id_here
TENCENT_SECRET_KEY=your_secret_key_here
```

**Example:**
```
TENCENT_SECRET_ID=AKIDxxxxxxxxxxxxxxxxxxxxx
TENCENT_SECRET_KEY=xxxxxxxxxxxxxxxxxxxxxxxx
```

### 4. Verify Setup

Start Minecraft with the mod and check the logs:

**✅ Success:**
```
[HunyuanAPI] ✓ Loaded Tencent Cloud credentials from .env file
[HunyuanAPI] Tencent Cloud credentials loaded successfully
```

**❌ Error:**
```
[HunyuanAPI] ✗ .env file not found at: /path/to/.env
[HunyuanAPI] Tencent Cloud credentials not found! Please set them in .env file.
```

## Security Notes

⚠️ **Important:**
- **Never commit** `.env` files to git
- **Never share** your credentials publicly
- The `.gitignore` already blocks `.env` files
- If you accidentally commit your keys, **regenerate them immediately** on Tencent Cloud Console

## Alternative: Environment Variable

Instead of a `.env` file, you can set a system environment variable:

**Windows (PowerShell):**
```powershell
$env:TENCENT_SECRET_ID="your_secret_id"
$env:TENCENT_SECRET_KEY="your_secret_key"
```

**Linux/macOS:**
```bash
export TENCENT_SECRET_ID="your_secret_id"
export TENCENT_SECRET_KEY="your_secret_key"
```

**Permanent (add to shell profile):**
```bash
# Add to ~/.bashrc or ~/.zshrc
export TENCENT_SECRET_ID="your_secret_id"
export TENCENT_SECRET_KEY="your_secret_key"
```

## Troubleshooting

### Key Not Loading

1. **Check file location** - `.env` must be in `.minecraft/` or `run/` (for development)
2. **Check file name** - must be exactly `.env` (not `env.txt` or `.env.txt`)  
3. **Check format** - must be `TENCENT_SECRET_ID=value` and `TENCENT_SECRET_KEY=value` (no spaces around `=`)
4. **Check permissions** - file must be readable
5. **Restart Minecraft** - changes require restart

### Credentials Invalid

1. Verify credentials on [Tencent Cloud Console](https://console.cloud.tencent.com/cam/capi)
2. Make sure Hunyuan 3D service is activated
3. Check if you have QcloudAI3DFullAccess permission
4. Make sure no extra spaces/characters in `.env`

### Still Not Working?

Check the full logs at `.minecraft/logs/latest.log` for detailed error messages.

