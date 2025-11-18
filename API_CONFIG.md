# 腾讯混元生3D API 配置说明

本文档说明falcraft使用的腾讯混元生3D API配置参数。

## 当前配置

### 基本参数

| 参数 | 值 | 说明 |
|------|-----|------|
| **API Host** | `ai3d.tencentcloudapi.com` | API服务器地址 |
| **API Version** | `2025-05-13` | API版本（极速版） |
| **Service** | `ai3d` | 服务名称 |
| **Region** | `ap-guangzhou` | 华南地区（广州） |

### 请求参数

#### 提交任务 (SubmitHunyuanTo3DRapidJob)

| 参数 | 使用值 | 类型 | 必选 | 说明 |
|------|--------|------|------|------|
| `Prompt` | 用户输入 | String | 是* | 文生3D的描述，支持中文，最多200字符 |
| `ResultFormat` | **GLB** | String | 否 | 强制返回GLB格式（兼容GLBParser） |
| `ImageBase64` | - | String | 否 | 图生3D的Base64数据（未使用） |
| `ImageUrl` | - | String | 否 | 图生3D的URL（未使用） |
| `EnablePBR` | false | Boolean | 否 | 是否开启PBR材质（默认关闭） |

**注：** Prompt、ImageBase64、ImageUrl 三选一

#### 查询任务 (QueryHunyuanTo3DRapidJob)

| 参数 | 使用值 | 类型 | 必选 | 说明 |
|------|--------|------|------|------|
| `JobId` | API返回值 | String | 是 | 提交任务返回的任务ID |

### 返回结果

#### 成功响应
```json
{
  "Response": {
    "Status": "DONE",
    "ResultFile3Ds": [
      {
        "Type": "GLB",
        "Url": "https://.../model.glb",
        "PreviewImageUrl": "https://.../preview.png"
      }
    ],
    "RequestId": "xxx"
  }
}
```

#### 任务状态
- `WAIT` - 等待中
- `RUN` - 执行中
- `DONE` - 成功完成
- `FAIL` - 任务失败

## 支持的模型格式

腾讯混元生3D极速版支持以下输出格式：

| 格式 | 是否支持 | 说明 |
|------|---------|------|
| **GLB** | ✅ 已使用 | 当前配置，兼容GLBParser |
| OBJ | ⚪ 可用 | 需要实现OBJ解析器 |
| STL | ⚪ 可用 | 3D打印格式 |
| USDZ | ⚪ 可用 | Apple AR格式 |
| FBX | ⚪ 可用 | Autodesk格式 |
| MP4 | ⚪ 可用 | 视频格式 |

## 修改配置

### 更改返回格式

在 `HunyuanAPI.java` 中修改：

```java
private static final String RESULT_FORMAT = "GLB"; // 改为其他格式
```

**注意：** 更改格式后需要实现对应的解析器！

### 更改地域

在 `HunyuanAPI.java` 中修改：

```java
private static final String REGION = "ap-guangzhou"; // 改为其他地域
```

### 启用PBR材质

在 `submitJob` 方法中添加：

```java
requestBody.addProperty("EnablePBR", true);
```

**注意：** 可能增加生成时间和成本。

### 支持图生3D

在 `generateModel` 方法中添加图片参数，修改请求体：

```java
// 使用URL方式
requestBody.addProperty("ImageUrl", imageUrl);

// 或使用Base64方式
requestBody.addProperty("ImageBase64", base64Data);
```

**注意：** 不能同时使用Prompt和图片参数。

## 认证配置

### 密钥格式

在 `.env` 文件中配置：

```
TENCENT_SECRET_ID=AKID********************************
TENCENT_SECRET_KEY=********************************
```

### 签名算法

使用 **TC3-HMAC-SHA256** 签名算法（签名方法 v3）。

详见：`TencentCloudSigner.java`

## API限制

- **并发限制**：默认1个并发（同时只能处理1个任务）
- **提示词长度**：最多200个UTF-8字符
- **图片大小**：单边128-5000像素，不超过6MB
- **超时设置**：最多轮询180次，每次间隔5秒（共15分钟）

## 常见问题

### Q: 为什么强制使用GLB格式？
A: GLBParser只支持GLB格式。如需其他格式，需实现对应的解析器。

### Q: 是否支持图生3D？
A: API支持，但当前代码未实现。需修改 `generateModel` 方法添加图片参数。

### Q: 如何提升生成质量？
A: 可以尝试：
1. 启用 `EnablePBR=true`（可能增加成本）
2. 使用更详细的中文提示词
3. 切换到专业版API（`SubmitHunyuanTo3DProJob`）

### Q: 为什么选择广州地域？
A: 根据官方文档，广州是主要支持地域。如需其他地域，请参考腾讯云地域列表。

## 参考链接

- [腾讯混元生3D产品页](https://cloud.tencent.com/product/ai3d)
- [API文档 - 提交任务](https://cloud.tencent.com/document/product/1804/123463)
- [API文档 - 查询任务](https://cloud.tencent.com/document/product/1804/123464)
- [腾讯云API公共参数](https://cloud.tencent.com/document/api/1804/120832)
- [签名方法 v3](https://cloud.tencent.com/document/api/1804/120831)
