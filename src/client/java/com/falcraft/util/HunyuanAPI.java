package com.falcraft.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 腾讯混元生3D API客户端
 * 使用混元生3D极速版API生成3D模型
 */
public class HunyuanAPI {
    private static final Logger LOGGER = LoggerFactory.getLogger("HunyuanAPI");
    
    // API配置
    private static final String API_HOST = "ai3d.tencentcloudapi.com";
    private static final String API_ENDPOINT = "https://" + API_HOST;
    private static final String API_VERSION = "2025-05-13";
    private static final String SERVICE = "ai3d";
    
    /**
     * 地域参数 - 华南地区（广州）
     * 根据腾讯云文档，支持的地域：
     * - ap-guangzhou: 华南地区（广州）
     * 如需使用其他地域，请修改此常量
     */
    private static final String REGION = "ap-guangzhou";
    
    /**
     * 生成模型的格式
     * 可选值：OBJ, GLB, STL, USDZ, FBX, MP4
     * 当前使用GLB格式（与GLBParser兼容）
     */
    private static final String RESULT_FORMAT = "GLB";
    
    // API操作
    private static final String ACTION_SUBMIT = "SubmitHunyuanTo3DRapidJob";
    private static final String ACTION_QUERY = "QueryHunyuanTo3DRapidJob";
    
    private static final Gson GSON = new Gson();
    private final HttpClient httpClient;
    private final TencentCloudSigner signer;
    
    /**
     * 结果包含GLB模型数据和预览图URL
     */
    public record ModelResult(byte[] glbData, String textureUrl) {}
    
    public HunyuanAPI() {
        this.httpClient = HttpClient.newHttpClient();
        
        // 加载腾讯云密钥
        String[] credentials = loadCredentials();
        if (credentials == null) {
            LOGGER.error("Tencent Cloud credentials not found! Please set them in .env file.");
            throw new IllegalStateException(
                "Tencent Cloud credentials required. Create a .env file with:\n" +
                "TENCENT_SECRET_ID=your-secret-id\n" +
                "TENCENT_SECRET_KEY=your-secret-key"
            );
        }
        
        this.signer = new TencentCloudSigner(credentials[0], credentials[1]);
        LOGGER.info("Tencent Cloud credentials loaded successfully");
    }
    
    /**
     * 从.env文件或环境变量加载腾讯云密钥
     * @return [SecretId, SecretKey] 或 null
     */
    private String[] loadCredentials() {
        File gameDir = Minecraft.getInstance().gameDirectory;
        Path envFile = gameDir.toPath().resolve(".env");
        
        LOGGER.info("Looking for .env file at: {}", envFile.toAbsolutePath());
        
        String secretId = null;
        String secretKey = null;
        
        // 尝试从.env文件读取
        if (Files.exists(envFile)) {
            try {
                String content = Files.readString(envFile);
                for (String line : content.split("\\r?\\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    
                    if (line.startsWith("TENCENT_SECRET_ID=")) {
                        secretId = extractValue(line, "TENCENT_SECRET_ID=");
                    } else if (line.startsWith("TENCENT_SECRET_KEY=")) {
                        secretKey = extractValue(line, "TENCENT_SECRET_KEY=");
                    }
                }
                
                if (secretId != null && secretKey != null) {
                    LOGGER.info("✓ Loaded Tencent Cloud credentials from .env file");
                    return new String[]{secretId, secretKey};
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to read .env file: {}", e.getMessage());
            }
        }
        
        // 回退到环境变量
        secretId = System.getenv("TENCENT_SECRET_ID");
        secretKey = System.getenv("TENCENT_SECRET_KEY");
        
        if (secretId != null && secretKey != null) {
            LOGGER.info("✓ Loaded Tencent Cloud credentials from environment variables");
            return new String[]{secretId, secretKey};
        }
        
        return null;
    }
    
    private String extractValue(String line, String prefix) {
        String value = line.substring(prefix.length()).trim();
        // 移除引号
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
    
    /**
     * 根据文本提示生成3D模型
     * @param prompt 文本描述
     * @return 包含GLB数据的ModelResult
     */
    public ModelResult generateModel(String prompt) throws IOException, InterruptedException {
        LOGGER.info("Starting 3D model generation with Tencent Hunyuan...");
        LOGGER.info("Prompt: {}", prompt);
        
        // 步骤1: 提交任务
        String jobId = submitJob(prompt);
        LOGGER.info("Job submitted successfully, JobId: {}", jobId);
        
        // 步骤2: 轮询任务状态
        String glbUrl = pollJobStatus(jobId);
        LOGGER.info("Job completed, GLB URL: {}", glbUrl);
        
        // 步骤3: 下载GLB文件
        byte[] glbData = downloadFile(glbUrl);
        LOGGER.info("Downloaded GLB file: {} bytes", glbData.length);
        
        return new ModelResult(glbData, null);
    }
    
    /**
     * 提交3D生成任务
     * @return JobId
     */
    private String submitJob(String prompt) throws IOException, InterruptedException {
        try {
            // 构建请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("Prompt", prompt);
            requestBody.addProperty("ResultFormat", RESULT_FORMAT); // 指定返回格式（GLB）
            String payload = GSON.toJson(requestBody);
            
            // 生成签名
            long timestamp = System.currentTimeMillis() / 1000;
            String authorization = signer.generateAuthorization(
                SERVICE, API_HOST, ACTION_SUBMIT, API_VERSION, REGION, timestamp, payload
            );
            
            // 发送请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("X-TC-Action", ACTION_SUBMIT)
                    .header("X-TC-Version", API_VERSION)
                    .header("X-TC-Region", REGION)
                    .header("X-TC-Timestamp", String.valueOf(timestamp))
                    .header("Authorization", authorization)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                LOGGER.error("Submit job failed: {} - {}", response.statusCode(), response.body());
                throw new IOException("Failed to submit job: " + response.statusCode());
            }
            
            // 解析响应
            JsonObject responseJson = GSON.fromJson(response.body(), JsonObject.class);
            JsonObject responseObj = responseJson.getAsJsonObject("Response");
            
            // 检查错误
            if (responseObj.has("Error")) {
                JsonObject error = responseObj.getAsJsonObject("Error");
                String errorCode = error.get("Code").getAsString();
                String errorMessage = error.get("Message").getAsString();
                throw new IOException("API Error: " + errorCode + " - " + errorMessage);
            }
            
            return responseObj.get("JobId").getAsString();
            
        } catch (Exception e) {
            throw new IOException("Failed to submit job: " + e.getMessage(), e);
        }
    }
    
    /**
     * 轮询任务状态直到完成
     * @return GLB文件URL
     */
    private String pollJobStatus(String jobId) throws IOException, InterruptedException {
        int maxAttempts = 180; // 180次 * 5秒 = 15分钟
        int attempts = 0;
        
        while (attempts < maxAttempts) {
            Thread.sleep(5000); // 每5秒查询一次
            attempts++;
            
            try {
                // 构建请求体
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("JobId", jobId);
                String payload = GSON.toJson(requestBody);
                
                // 生成签名
                long timestamp = System.currentTimeMillis() / 1000;
                String authorization = signer.generateAuthorization(
                    SERVICE, API_HOST, ACTION_QUERY, API_VERSION, REGION, timestamp, payload
                );
                
                // 发送请求
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(API_ENDPOINT))
                        .header("Content-Type", "application/json")
                        .header("X-TC-Action", ACTION_QUERY)
                        .header("X-TC-Version", API_VERSION)
                        .header("X-TC-Region", REGION)
                        .header("X-TC-Timestamp", String.valueOf(timestamp))
                        .header("Authorization", authorization)
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() != 200) {
                    LOGGER.warn("Query job status failed, retry... ({}/{})", attempts, maxAttempts);
                    continue;
                }
                
                // 解析响应
                JsonObject responseJson = GSON.fromJson(response.body(), JsonObject.class);
                JsonObject responseObj = responseJson.getAsJsonObject("Response");
                
                // 检查错误
                if (responseObj.has("Error")) {
                    JsonObject error = responseObj.getAsJsonObject("Error");
                    String errorCode = error.get("Code").getAsString();
                    String errorMessage = error.get("Message").getAsString();
                    throw new IOException("API Error: " + errorCode + " - " + errorMessage);
                }
                
                String status = responseObj.get("Status").getAsString();
                LOGGER.info("Job status check {}/{}: {}", attempts, maxAttempts, status);
                
                switch (status) {
                    case "DONE":
                        // 任务完成，提取GLB URL
                        JsonArray resultFiles = responseObj.getAsJsonArray("ResultFile3Ds");
                        
                        // 优先查找GLB格式
                        for (int i = 0; i < resultFiles.size(); i++) {
                            JsonObject file = resultFiles.get(i).getAsJsonObject();
                            String type = file.get("Type").getAsString();
                            
                            if ("GLB".equals(type)) {
                                LOGGER.info("Found GLB file in results");
                                return file.get("Url").getAsString();
                            }
                        }
                        
                        // 如果没有GLB，记录可用的格式并报错
                        StringBuilder availableTypes = new StringBuilder();
                        for (int i = 0; i < resultFiles.size(); i++) {
                            JsonObject file = resultFiles.get(i).getAsJsonObject();
                            String type = file.get("Type").getAsString();
                            if (availableTypes.length() > 0) availableTypes.append(", ");
                            availableTypes.append(type);
                        }
                        
                        String errorMsg = resultFiles.isEmpty() 
                            ? "No result files found in API response" 
                            : "No GLB file found despite requesting ResultFormat=GLB. Available formats: " + availableTypes + 
                              ". This should not happen. Please check API configuration.";
                        
                        LOGGER.error(errorMsg);
                        throw new IOException(errorMsg);
                        
                    case "FAIL":
                        String errorCode = responseObj.get("ErrorCode").getAsString();
                        String errorMessage = responseObj.get("ErrorMessage").getAsString();
                        throw new IOException("Job failed: " + errorCode + " - " + errorMessage);
                        
                    case "WAIT":
                    case "RUN":
                        // 继续等待
                        break;
                        
                    default:
                        LOGGER.warn("Unknown status: {}", status);
                }
                
            } catch (Exception e) {
                if (e instanceof IOException) {
                    throw (IOException) e;
                }
                LOGGER.warn("Error querying job status, retry... {}", e.getMessage());
            }
        }
        
        throw new IOException("Job timed out after " + maxAttempts + " attempts");
    }
    
    /**
     * 下载文件
     */
    private byte[] downloadFile(String fileUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fileUrl))
                .GET()
                .build();
        
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download file from: " + fileUrl);
        }
        
        return response.body();
    }
}
