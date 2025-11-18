# 🎨 Falcraft - 腾讯混元版

> 基于AI的Minecraft 3D模型生成与纹理编辑模组

使用腾讯混元生3D和fal.ai的强大AI能力，在Minecraft中即时创建3D建筑和编辑方块纹理。支持中文提示词！

[English](README.md) | **简体中文**

---

## ✨ 主要特性

### 🆕 3D模型生成（全新！）
- 🤖 **腾讯混元生3D** - 腾讯云原生AI模型
- 🇨🇳 **中文提示词** - 完全支持中文描述
- 🎯 **精确生成** - 从文字到3D模型
- 🎨 **自动着色** - AI智能配色
- 🧱 **体素化** - 转换为Minecraft方块

### 🎭 纹理编辑（保留功能）
- 🖼️ **指向编辑** - 对准方块即可编辑
- ✨ **AI重绘** - fal.ai nano-banana模型
- 🎨 **风格迁移** - 赛博朋克、卡通、写实等
- 💾 **自动应用** - 实时更新纹理

---

## 🎥 效果演示

```
游戏中输入：
/fal generate 48 古代中式宫殿

等待5-10分钟后，一座宫殿会出现在你面前！
```

---

## 🎮 使用方法

### 生成3D模型（腾讯混元）

```
/fal generate <大小> <提示词>
```

**示例**：
```
/fal generate 32 一只可爱的小猫
/fal generate 48 古代中式宫殿
/fal generate 64 赛博朋克风格机器人
/fal generate 48 长城烽火台
/fal generate 32 medieval castle
```

**推荐大小**：
- `16-32` - 快速测试，适合小型物体
- `48` - 平衡质量，推荐使用
- `64` - 高细节，适合大型建筑
- `96-128` - 最大细节，耗时较长

### 编辑方块纹理（fal.ai）

```
/fal remix <风格描述>
```

**示例**：
```
准心对准一个方块，然后输入：
/fal remix 赛博朋克风格
/fal remix 古代石刻纹理
/fal remix cyberpunk style
```

---

## 🚀 快速开始

### 玩家使用

#### 1. 安装模组

**前置要求**：
- Minecraft 1.21.1
- Fabric Loader 0.17.3+
- Fabric API 0.107.0+1.21.1

**步骤**：
1. 下载 `falcraft-hunyuan-edition-1.0.0-hunyuan.jar`
2. 放入 `.minecraft/mods/` 文件夹
3. 启动游戏

#### 2. 配置API密钥

在 `.minecraft/` 目录下创建 `.env` 文件：

```env
# 腾讯云密钥（用于3D生成）
TENCENT_SECRET_ID=你的SecretId
TENCENT_SECRET_KEY=你的SecretKey

# fal.ai密钥（可选，用于纹理编辑）
FAL_API_KEY=你的fal_api_key
```

#### 3. 获取密钥

**腾讯云密钥**（必需）：
1. 访问 [腾讯云控制台](https://console.cloud.tencent.com/cam/capi)
2. 创建或查看API密钥
3. 复制 `SecretId` 和 `SecretKey`
4. 开通 [混元生3D服务](https://console.cloud.tencent.com/hunyuan3d)

**fal.ai密钥**（可选，仅纹理编辑需要）：
1. 访问 [fal.ai dashboard](https://fal.ai/dashboard/keys)
2. 创建API密钥
3. 复制密钥到 `.env` 文件

#### 4. 开始游戏！

```
/fal generate 32 一只可爱的小猫
```

---

## 🧠 技术原理

### 3D生成流程

1. **腾讯混元生3D** 根据文字描述生成带纹理的GLB模型（通过 `ResultFormat=GLB` 参数）
2. **GLB解析** 提取顶点、索引、UV坐标和嵌入式纹理
3. **纹理提取** 从GLB二进制文件中提取嵌入纹理用于颜色采样
4. **体素化** 将平滑网格转换为Minecraft方块网格
5. **感知色彩匹配** 使用LAB色彩空间（符合人眼视觉，而非简单RGB）
6. **智能放置** 自动寻找地面并平放结构

### 纹理编辑流程

1. **纹理提取** 从Minecraft的纹理图集中提取目标方块纹理
2. **AI重绘** 使用fal.ai nano-banana模型根据提示词重新生成纹理
3. **资源包注入** 实时创建并应用自定义资源包
4. **热重载** 无需重启游戏即可看到效果

### 为什么使用LAB色彩空间？

LAB色彩空间模拟人眼感知：
- **L\*** 亮度（0-100）- 黑到白
- **a\*** 绿到红（-128到+127）
- **b\*** 蓝到黄（-128到+127）

这比RGB更接近人眼实际感受的颜色差异！

---

## 📋 配置选项

### 地域配置

默认地域：`ap-guangzhou`（华南-广州）

修改方法：编辑 `HunyuanAPI.java` 中的 `REGION` 常量
```java
private static final String REGION = "ap-guangzhou";
```

### 模型格式配置

默认格式：`GLB`（推荐，与GLBParser完全兼容）

其他支持的格式（需修改代码并实现相应解析器）：
- `OBJ` - Wavefront OBJ格式
- `STL` - 3D打印常用格式  
- `USDZ` - Apple通用场景格式
- `FBX` - Autodesk格式
- `MP4` - 视频格式

---

## 🐛 故障排除

### 问题1：找不到密钥
```
[ERROR] Failed to load Tencent Cloud credentials
```

**解决方案**：
1. 检查 `.env` 文件是否在 `.minecraft/` 目录（不是 `.minecraft/mods/`）
2. 确认文件名是 `.env` 而不是 `.env.txt`
3. 检查密钥格式：
   ```env
   TENCENT_SECRET_ID=AKID开头的字符串
   TENCENT_SECRET_KEY=32-40位字符串
   ```
4. 确保密钥前后没有空格或引号

### 问题2：API调用失败
```
[ERROR] Failed to submit job: 403
```

**解决方案**：
1. 确认已在 [腾讯云控制台](https://console.cloud.tencent.com/hunyuan3d) 开通混元生3D服务
2. 检查密钥是否有效（可能已过期）
3. 确认账号有足够的配额
4. 检查网络连接能否访问 `ai3d.tencentcloudapi.com`

### 问题3：生成超时
```
[ERROR] 3D generation request timed out
```

**解决方案**：
1. 混元生3D正常生成时间为5-10分钟，请耐心等待
2. 检查提示词是否符合要求（不超过200字符）
3. 查看日志 `logs/latest.log` 中的详细信息
4. 可能是API服务器繁忙，稍后重试

### 问题4：纹理提取失败
```
[ERROR] Failed to access sprite mipmap data
```

**解决方案**：
1. 确认Minecraft版本为 1.21.1
2. 查看日志中显示的可用字段名
3. 本版本已修复大部分兼容性问题
4. 如果问题持续，请在 [GitHub Issues](https://github.com/YuYigy/falcraft/issues) 报告

### 问题5：模型放置位置不对
```
模型生成在天空中/地下
```

**解决方案**：
1. 确保你站在地面上
2. 确保脚下是实心方块
3. 避免在水中或虚空中使用命令
4. 如需精确控制，可以修改 `BlockPlacer.java`

### 问题6：方块颜色不准确
```
生成的方块颜色与模型不符
```

**解决方案**：
1. 这是体素化过程的正常现象
2. 使用更大的尺寸可以提高精度（如64或128）
3. Minecraft方块颜色有限，AI会选择最接近的颜色
4. 可以通过资源包添加更多颜色的方块

---

## 📖 详细文档

- **[API配置说明](API_CONFIG.md)** - 详细的API参数配置
- **[环境设置指南](ENV_SETUP.md)** - 完整的环境配置教程
- **[贡献指南](CONTRIBUTING.md)** - 如何参与项目开发

---

## 🆚 版本对比

### 混元版 vs 原版

| 功能 | 原版 (fal.ai) | 混元版 (Tencent) |
|------|--------------|------------------|
| **3D生成API** | fal.ai Meshy | 腾讯混元生3D ✨ |
| **中文支持** | 需翻译 | ✅ 原生支持 |
| **API认证** | API Key | SecretId + SecretKey |
| **模型格式** | 自动选择 | ✅ 强制GLB |
| **生成时间** | 5-10分钟 | 5-10分钟 |
| **纹理编辑** | ✅ fal.ai | ✅ fal.ai（保留）|
| **地域配置** | ❌ | ✅ 可配置 |
| **本地化** | ❌ | ✅ 中文文档 |

**为什么选择混元版？**
- 🇨🇳 更好的中文支持
- 🚀 可能更快的访问速度（国内用户）
- 🔧 更灵活的配置选项
- 📖 完善的中文文档

---

## 🤝 贡献

欢迎贡献代码、报告问题或提出建议！

**参与方式**：
1. Fork本项目
2. 创建功能分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送分支 (`git push origin feature/AmazingFeature`)
5. 创建Pull Request

**报告问题**：
- [GitHub Issues](https://github.com/YuYigy/falcraft/issues)
- 请提供详细的错误日志和复现步骤

---

## 📜 开源协议

本项目基于 **CC0-1.0** 协议开源

您可以自由地：
- ✅ 商业使用
- ✅ 修改
- ✅ 分发
- ✅ 私人使用

---

## 🙏 致谢

- **原版Falcraft** - [blendi-remade/falcraft](https://github.com/blendi-remade/falcraft)
- **腾讯混元生3D** - [产品页面](https://cloud.tencent.com/product/hunyuan3d)
- **fal.ai** - [官方网站](https://fal.ai)
- **Minecraft Fabric社区** - 模组开发支持

---

## 📞 支持与联系

- **GitHub仓库**: https://github.com/YuYigy/falcraft
- **问题反馈**: [GitHub Issues](https://github.com/YuYigy/falcraft/issues)
- **原作者**: [blendi-remade](https://github.com/blendi-remade)

---

## 🔄 更新日志

### v1.0.0-hunyuan (2025-11-18)

#### 新增 ✨
- 集成腾讯混元生3D API
- 添加中文提示词支持
- 添加地域配置功能
- 新增 `TencentCloudSigner.java`（TC3-HMAC-SHA256签名）
- 新增 `HunyuanAPI.java`（API客户端）
- 新增 `API_CONFIG.md` 配置文档
- 新增 `.env.example` 配置示例
- 新增完整中文文档

#### 修改 🔧
- 更新 `GenerateCommand.java` - 使用HunyuanAPI
- 更新 `ClientTextureGrabber.java` - MC 1.21.1兼容性
- 更新 `README.md` - 混元版说明
- 更新 `ENV_SETUP.md` - 环境配置指南
- 更新 `gradle.properties` - 版本号1.0.0-hunyuan
- 更新 `fabric.mod.json` - 模组名"Falcraft - 混元版"

#### 修复 🐛
- 修复HttpClient的Host header限制错误
- 修复纹理提取字段名兼容性问题
- 修复Minecraft 1.21.1下的多个兼容性问题

---

**享受AI驱动的Minecraft创作体验！** 🎮✨

![Made with ❤️ in China](https://img.shields.io/badge/Made%20with-%E2%9D%A4%EF%B8%8F-red)
![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-green)
![Fabric](https://img.shields.io/badge/Mod%20Loader-Fabric-orange)
![License CC0-1.0](https://img.shields.io/badge/License-CC0--1.0-blue)
