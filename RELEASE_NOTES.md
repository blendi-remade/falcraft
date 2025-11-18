# Falcraft - 腾讯混元版 v1.0.0-hunyuan

## 🎯 重大更新：集成腾讯混元生3D API

这是Falcraft的重大更新版本，将3D模型生成服务从fal.ai Meshy迁移到**腾讯混元生3D API**，提供更好的中文支持和本地化体验。

---

## ✨ 主要特性

### 🆕 全新功能
- **腾讯混元生3D** - 使用腾讯云原生AI模型生成3D内容
- **中文提示词支持** - 原生支持中文描述，无需翻译
- **GLB格式优先** - 强制使用GLB格式确保最佳兼容性
- **地域配置** - 支持配置API地域（默认：华南-广州）

### 🔧 技术改进
- ✅ 完整的TC3-HMAC-SHA256签名算法实现
- ✅ 异步任务提交和轮询机制（最多15分钟）
- ✅ ResultFormat=GLB参数强制返回GLB格式
- ✅ 修复Minecraft 1.21.1纹理提取兼容性问题
- ✅ 修复HttpClient的Host header限制错误

### 🎮 使用方式

#### 生成3D模型（使用腾讯混元）
```
/fal generate <大小> <提示词>
```

**示例**：
```
/fal generate 32 一只可爱的小猫
/fal generate 48 古代中式宫殿
/fal generate 64 赛博朋克风格机器人
/fal generate 48 medieval castle
```

**推荐大小**：
- `16-32` - 快速测试
- `48` - 平衡质量（推荐）
- `64` - 高细节
- `96-128` - 最大细节

#### 编辑方块纹理（仍使用fal.ai）
```
/fal remix <提示词>
```

---

## 📦 安装说明

### 前置要求
- **Minecraft**: 1.21.1
- **Fabric Loader**: 0.17.3 或更高版本
- **Fabric API**: 0.107.0+1.21.1 或更高版本

### 安装步骤

1. **下载模组文件**
   - 下载 `falcraft-hunyuan-edition-1.0.0-hunyuan.jar`
   - 复制到 `.minecraft/mods/` 目录

2. **配置API密钥**
   
   在 `.minecraft/` 目录下创建 `.env` 文件：
   ```env
   # 腾讯云密钥（用于3D生成）
   TENCENT_SECRET_ID=你的SecretId
   TENCENT_SECRET_KEY=你的SecretKey
   
   # fal.ai密钥（可选，用于纹理编辑）
   FAL_API_KEY=你的fal_api_key
   ```

3. **获取腾讯云密钥**
   - 访问：https://console.cloud.tencent.com/cam/capi
   - 创建或查看API密钥
   - 确保已开通混元生3D服务：https://console.cloud.tencent.com/hunyuan3d

4. **启动游戏**
   - 启动Minecraft
   - 确认模组已加载（模组列表中可见"Falcraft - 混元版"）

---

## 🐛 已修复的问题

### BUG修复
1. **修复 "restricted header name: Host" 错误**
   - 问题：Java HttpClient不允许手动设置Host header
   - 解决：移除手动设置的Host header，由HttpClient自动处理
   - 影响：`/fal generate` 命令

2. **修复 "NoSuchFieldException: byMipLevel" 错误**
   - 问题：纹理提取时字段名在MC 1.21.1中已改变
   - 解决：添加多种兼容方式获取纹理数据
   - 影响：`/fal remix` 命令

---

## 📝 与原版的差异

| 功能 | 原版 (fal.ai) | 混元版 (Tencent) |
|------|--------------|------------------|
| **3D生成API** | fal.ai Meshy | 腾讯混元生3D |
| **中文支持** | 需翻译 | ✅ 原生支持 |
| **API认证** | API Key | SecretId + SecretKey |
| **模型格式** | 自动选择 | ✅ 强制GLB |
| **生成时间** | 5-10分钟 | 5-10分钟 |
| **纹理编辑** | ✅ fal.ai | ✅ fal.ai（保留）|
| **地域配置** | ❌ | ✅ 可配置 |

---

## 📋 文件说明

### 主模组文件
- **falcraft-hunyuan-edition-1.0.0-hunyuan.jar** (约64KB)
  - 这是需要安装到 `.minecraft/mods/` 的文件

### 源代码文件（可选）
- **falcraft-hunyuan-edition-1.0.0-hunyuan-sources.jar**
  - 仅供开发者参考

---

## 🔧 配置选项

### 地域配置
默认地域：`ap-guangzhou`（华南-广州）

修改地域：编辑 `HunyuanAPI.java` 中的 `REGION` 常量
```java
private static final String REGION = "ap-guangzhou";
```

### 模型格式配置
默认格式：`GLB`（推荐，与GLBParser完全兼容）

其他支持的格式（需修改代码）：
- `OBJ` - 需要实现OBJ解析器
- `STL` - 需要实现STL解析器
- `USDZ` - 需要实现USDZ解析器
- `FBX` - 需要实现FBX解析器
- `MP4` - 视频格式

---

## ⚠️ 注意事项

1. **生成时间**
   - 3D模型生成需要5-10分钟，请耐心等待
   - 游戏不会卡死，可以继续游玩

2. **API配额**
   - 腾讯混元生3D默认提供1个并发
   - 上一个任务完成后才能开始下一个
   - 请勿频繁提交请求

3. **网络要求**
   - 需要能够访问 `ai3d.tencentcloudapi.com`
   - 确保网络连接稳定

4. **密钥安全**
   - 不要分享你的密钥
   - 不要将 `.env` 文件提交到Git

---

## 🆚 API对比

### 腾讯混元生3D vs fal.ai

**优势**：
- ✅ 更好的中文支持
- ✅ 本地化服务，访问速度可能更快
- ✅ 腾讯云生态集成
- ✅ 强制GLB格式，避免兼容性问题

**考虑**：
- ⚠️ 需要腾讯云账号
- ⚠️ 需要开通混元生3D服务
- ⚠️ API配额限制（默认1并发）

---

## 📖 文档

- **README.md** - 项目整体介绍
- **API_CONFIG.md** - API配置详细说明
- **ENV_SETUP.md** - 环境配置指南
- **CONTRIBUTING.md** - 贡献指南

---

## 🐛 故障排除

### 问题1：提示找不到密钥
**解决方案**：
1. 检查 `.env` 文件是否在 `.minecraft/` 目录下
2. 检查密钥格式是否正确
3. 确认密钥前后没有多余空格

### 问题2：API调用失败
**解决方案**：
1. 确认已开通腾讯混元生3D服务
2. 检查密钥是否有效
3. 查看 `logs/latest.log` 获取详细错误信息

### 问题3：模型生成失败
**解决方案**：
1. 确保提示词不超过200字符
2. 等待足够的时间（最多15分钟）
3. 检查网络连接
4. 查看日志中的错误信息

### 问题4：纹理提取失败
**解决方案**：
1. 确保游戏版本为 Minecraft 1.21.1
2. 查看日志中显示的可用字段名
3. 如果问题持续，请在GitHub上报告

---

## 📞 获取帮助

- **GitHub Issues**: https://github.com/YuYigy/falcraft/issues
- **查看日志**: `.minecraft/logs/latest.log`
- **腾讯云文档**: https://cloud.tencent.com/document/product/1804

---

## 🙏 致谢

- 原版Falcraft项目：https://github.com/blendi-remade/falcraft
- 腾讯混元生3D：https://cloud.tencent.com/product/hunyuan3d
- Minecraft Fabric社区

---

## 📜 开源协议

本项目基于 CC0-1.0 协议开源

---

## 🔄 更新日志

### v1.0.0-hunyuan (2025-11-18)

#### 新增
- ✅ 集成腾讯混元生3D API
- ✅ 添加TencentCloudSigner.java（TC3-HMAC-SHA256签名）
- ✅ 添加HunyuanAPI.java（API客户端）
- ✅ 添加API_CONFIG.md文档
- ✅ 添加.env.example配置示例

#### 修改
- 🔧 GenerateCommand.java - 使用HunyuanAPI替换FalAPI
- 🔧 ClientTextureGrabber.java - 修复MC 1.21.1兼容性
- 🔧 README.md - 更新为混元版说明
- 🔧 ENV_SETUP.md - 更新环境配置指南
- 🔧 gradle.properties - 版本改为1.0.0-hunyuan
- 🔧 fabric.mod.json - 模组名改为"Falcraft - 混元版"

#### 修复
- 🐛 修复HttpClient的Host header限制错误
- 🐛 修复纹理提取字段名兼容性问题

---

**享受AI驱动的Minecraft创作体验！** 🎮✨
