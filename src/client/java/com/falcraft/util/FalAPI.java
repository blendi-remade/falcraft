package com.falcraft.util;

import com.falcraft.commands.ConfigCommand;
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
import java.util.Base64;

public class FalAPI {
    private static final Logger LOGGER = LoggerFactory.getLogger("FalAPI");
    private static final String FAL_QUEUE_SUBMIT = "https://queue.fal.run/fal-ai/nano-banana/edit";
    private static final String FAL_3D_QUEUE_SUBMIT = "https://queue.fal.run/fal-ai/meshy/v6-preview/text-to-3d";
    private static final String FAL_ZIMAGE_QUEUE_SUBMIT = "https://queue.fal.run/fal-ai/z-image/turbo";
    private static final String FAL_SAM3_QUEUE_SUBMIT = "https://queue.fal.run/fal-ai/sam-3/3d-objects";
    private static final String FAL_VLM_QUEUE_SUBMIT = "https://queue.fal.run/openrouter/router/vision";
    private static final String FAL_NANOBANANA_QUEUE_SUBMIT = "https://queue.fal.run/fal-ai/nano-banana-pro";
    private static final String FAL_HUNYUAN_QUEUE_SUBMIT = "https://queue.fal.run/fal-ai/hunyuan-3d/v3.1/pro/image-to-3d";
    private static final String FAL_TRIPOSPLAT_QUEUE_SUBMIT = "https://queue.fal.run/tripo3d/triposplat";
    private static final String FAL_FLUX_KLEIN_QUEUE_SUBMIT = "https://queue.fal.run/fal-ai/flux-2/klein/9b";
    private static final Gson GSON = new Gson();
    private final HttpClient httpClient;
    private final String apiKey;
    
    /**
     * Result containing both the GLB model data and optional texture URL
     */
    public record ModelResult(byte[] glbData, String textureUrl) {}

    public FalAPI() {
        this.httpClient = HttpClient.newHttpClient();
        this.apiKey = ConfigCommand.getApiKey();
        
        if (apiKey == null || apiKey.isEmpty()) {
            LOGGER.error("FAL_API_KEY not found! Use /fal setkey <key> to configure.");
            throw new IllegalStateException("API key not configured. Use /fal setkey <your-key> to set it up. Get a key at https://fal.ai/dashboard/keys");
        }
    }

    /**
     * Uploads a texture PNG file and remixes it using the fal nano-banana/edit endpoint
     * @param textureFile The PNG file to remix
     * @param prompt The text prompt describing the desired edits
     * @return The remixed PNG as a byte array
     * @throws IOException If network or file operations fail
     */
    public byte[] remixTexture(File textureFile, String prompt) throws IOException, InterruptedException {
        LOGGER.info("Starting texture remix with prompt: {}", prompt);
        
        // Step 1: Convert the file to base64 data URI
        byte[] fileBytes = Files.readAllBytes(textureFile.toPath());
        String base64Data = Base64.getEncoder().encodeToString(fileBytes);
        String dataUri = "data:image/png;base64," + base64Data;
        
        
        // Step 2: Submit the request to the queue
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("prompt", prompt);
        
        JsonArray imageUrls = new JsonArray();
        imageUrls.add(dataUri);
        requestBody.add("image_urls", imageUrls);
        
        requestBody.addProperty("num_images", 1);
        requestBody.addProperty("output_format", "png");
        
        String requestBodyJson = GSON.toJson(requestBody);
        
        HttpRequest submitRequest = HttpRequest.newBuilder()
                .uri(URI.create(FAL_QUEUE_SUBMIT))
                .header("Authorization", "Key " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
        
        HttpResponse<String> submitResponse = httpClient.send(submitRequest, HttpResponse.BodyHandlers.ofString());
        
        if (submitResponse.statusCode() != 200) {
            LOGGER.error("fal queue submit error: {} - {}", submitResponse.statusCode(), submitResponse.body());
            throw new IOException("Failed to submit request to fal queue: " + submitResponse.statusCode());
        }
        
        JsonObject submitJson = GSON.fromJson(submitResponse.body(), JsonObject.class);
        String requestId = submitJson.get("request_id").getAsString();
        String responseUrl = submitJson.get("response_url").getAsString();
        String statusUrl = submitJson.get("status_url").getAsString();
        
        // Step 3: Poll for completion
        
        boolean completed = false;
        int attempts = 0;
        int maxAttempts = 60; // 60 attempts * 2 seconds = 2 minutes max
        
        while (!completed && attempts < maxAttempts) {
            Thread.sleep(2000); // Wait 2 seconds between polls
            attempts++;
            
            HttpRequest statusRequest = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Key " + apiKey)
                    .GET()
                    .build();
            
            HttpResponse<String> statusResponse = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());
            
            // 202 = IN_PROGRESS, 200 = COMPLETED
            if (statusResponse.statusCode() == 200 || statusResponse.statusCode() == 202) {
                JsonObject statusJson = GSON.fromJson(statusResponse.body(), JsonObject.class);
                String status = statusJson.get("status").getAsString();
                
                LOGGER.info("Status check {}/{}: {}", attempts, maxAttempts, status);
                
                if ("COMPLETED".equals(status)) {
                    completed = true;
                } else if ("FAILED".equals(status)) {
                    throw new IOException("fal request failed");
                }
            }
        }
        
        if (!completed) {
            throw new IOException("Request timed out after " + maxAttempts + " attempts");
        }
        
        // Step 4: Get the result
        
        HttpRequest resultRequest = HttpRequest.newBuilder()
                .uri(URI.create(responseUrl))
                .header("Authorization", "Key " + apiKey)
                .GET()
                .build();
        
        HttpResponse<String> resultResponse = httpClient.send(resultRequest, HttpResponse.BodyHandlers.ofString());
        
        if (resultResponse.statusCode() != 200) {
            LOGGER.error("Failed to get result: {} - {}", resultResponse.statusCode(), resultResponse.body());
            throw new IOException("Failed to get result from fal");
        }
        
        JsonObject resultJson = GSON.fromJson(resultResponse.body(), JsonObject.class);
        String imageUrl = resultJson.getAsJsonArray("images")
                .get(0).getAsJsonObject()
                .get("url").getAsString();
        
        // Step 5: Download the result image
        return downloadImage(imageUrl);
    }

    /**
     * Generates a 3D model from a text prompt using the fal Meshy v6 endpoint
     * @param prompt The text prompt describing the desired 3D model
     * @return ModelResult containing GLB data and texture URL
     * @throws IOException If network operations fail
     * @throws InterruptedException If the thread is interrupted during polling
     */
    public ModelResult generateModel(String prompt) throws IOException, InterruptedException {
        LOGGER.info("Starting 3D model generation with prompt: {}", prompt);
        
        // Step 1: Submit the request to the queue
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("prompt", prompt);
        requestBody.addProperty("mode", "full"); // Use full mode (textured model with proper colors)
        requestBody.addProperty("topology", "quad"); // Quad topology for cleaner UV layouts
        
        String requestBodyJson = GSON.toJson(requestBody);
        
        HttpRequest submitRequest = HttpRequest.newBuilder()
                .uri(URI.create(FAL_3D_QUEUE_SUBMIT))
                .header("Authorization", "Key " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
        
        HttpResponse<String> submitResponse = httpClient.send(submitRequest, HttpResponse.BodyHandlers.ofString());
        
        if (submitResponse.statusCode() != 200) {
            LOGGER.error("fal 3D queue submit error: {} - {}", submitResponse.statusCode(), submitResponse.body());
            throw new IOException("Failed to submit 3D generation request to fal queue: " + submitResponse.statusCode());
        }
        
        JsonObject submitJson = GSON.fromJson(submitResponse.body(), JsonObject.class);
        String requestId = submitJson.get("request_id").getAsString();
        String responseUrl = submitJson.get("response_url").getAsString();
        String statusUrl = submitJson.get("status_url").getAsString();
        
        // Step 2: Poll for completion (3D generation takes longer, so increase timeout)
        
        boolean completed = false;
        int attempts = 0;
        int maxAttempts = 160; // 160 attempts * 5 seconds = 13.3 minutes max (3D takes longer)
        
        while (!completed && attempts < maxAttempts) {
            Thread.sleep(5000); // Wait 5 seconds between polls (longer for 3D)
            attempts++;
            
            HttpRequest statusRequest = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Key " + apiKey)
                    .GET()
                    .build();
            
            HttpResponse<String> statusResponse = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());
            
            // 202 = IN_PROGRESS, 200 = COMPLETED
            if (statusResponse.statusCode() == 200 || statusResponse.statusCode() == 202) {
                JsonObject statusJson = GSON.fromJson(statusResponse.body(), JsonObject.class);
                String status = statusJson.get("status").getAsString();
                
                if ("COMPLETED".equals(status)) {
                    completed = true;
                } else if ("FAILED".equals(status)) {
                    throw new IOException("fal 3D generation request failed");
                }
            }
        }
        
        if (!completed) {
            throw new IOException("3D generation request timed out after " + maxAttempts + " attempts");
        }
        
        // Step 3: Get the result
        
        HttpRequest resultRequest = HttpRequest.newBuilder()
                .uri(URI.create(responseUrl))
                .header("Authorization", "Key " + apiKey)
                .GET()
                .build();
        
        HttpResponse<String> resultResponse = httpClient.send(resultRequest, HttpResponse.BodyHandlers.ofString());
        
        if (resultResponse.statusCode() != 200) {
            LOGGER.error("Failed to get 3D result: {} - {}", resultResponse.statusCode(), resultResponse.body());
            throw new IOException("Failed to get 3D result from fal");
        }
        
        JsonObject resultJson = GSON.fromJson(resultResponse.body(), JsonObject.class);
        String glbUrl = resultJson.getAsJsonObject("model_glb")
                .get("url").getAsString();
        
        // Extract texture URL if available
        String textureUrl = null;
        if (resultJson.has("texture_urls")) {
            JsonArray textureUrls = resultJson.getAsJsonArray("texture_urls");
            if (!textureUrls.isEmpty()) {
                JsonObject firstTexture = textureUrls.get(0).getAsJsonObject();
                if (firstTexture.has("base_color")) {
                    textureUrl = firstTexture.getAsJsonObject("base_color")
                            .get("url").getAsString();
                }
            }
        }
        
        // Step 4: Download the GLB file
        byte[] glbData = downloadFile(glbUrl);
        
        return new ModelResult(glbData, textureUrl);
    }

    /**
     * Downloads an image from a URL
     * @param imageUrl The URL of the image
     * @return The image as a byte array
     */
    private byte[] downloadImage(String imageUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .GET()
                .build();
        
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download image from: " + imageUrl);
        }
        
        return response.body();
    }

    /**
     * Downloads a file from a URL
     * @param fileUrl The URL of the file
     * @return The file as a byte array
     */
    public byte[] downloadFile(String fileUrl) throws IOException, InterruptedException {
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

    // ==================== FAST MODE: Z-Image + SAM-3 Pipeline ====================

    /**
     * Generates an image from a text prompt using Z-Image Turbo
     * Optimized for 3D conversion with white background and diagonal view
     * @param prompt The base prompt (will be augmented for 3D-friendly output)
     * @return The URL of the generated image
     * @throws IOException If network operations fail
     * @throws InterruptedException If the thread is interrupted during polling
     */
    public String generateImageWithZImage(String prompt) throws IOException, InterruptedException {
        // Augment prompt for 3D-friendly image generation
        String augmentedPrompt = prompt + " image with plain white background, view from diagonally above";
        
        // Build request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("prompt", augmentedPrompt);
        requestBody.addProperty("image_size", "square_hd");
        requestBody.addProperty("num_inference_steps", 8);
        requestBody.addProperty("num_images", 1);
        requestBody.addProperty("enable_safety_checker", true);
        requestBody.addProperty("output_format", "png");
        
        String requestBodyJson = GSON.toJson(requestBody);
        
        HttpRequest submitRequest = HttpRequest.newBuilder()
                .uri(URI.create(FAL_ZIMAGE_QUEUE_SUBMIT))
                .header("Authorization", "Key " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
        
        HttpResponse<String> submitResponse = httpClient.send(submitRequest, HttpResponse.BodyHandlers.ofString());
        
        if (submitResponse.statusCode() != 200) {
            LOGGER.error("Z-Image queue submit error: {} - {}", submitResponse.statusCode(), submitResponse.body());
            throw new IOException("Failed to submit Z-Image request to fal queue: " + submitResponse.statusCode());
        }
        
        JsonObject submitJson = GSON.fromJson(submitResponse.body(), JsonObject.class);
        String requestId = submitJson.get("request_id").getAsString();
        String responseUrl = submitJson.get("response_url").getAsString();
        String statusUrl = submitJson.get("status_url").getAsString();
        
        // Poll for completion (Z-Image is fast, ~1 second)
        boolean completed = false;
        int attempts = 0;
        int maxAttempts = 30; // 30 attempts * 1 second = 30 seconds max
        
        while (!completed && attempts < maxAttempts) {
            Thread.sleep(1000); // Poll every 1 second (Z-Image is fast)
            attempts++;
            
            HttpRequest statusRequest = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Key " + apiKey)
                    .GET()
                    .build();
            
            HttpResponse<String> statusResponse = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());
            
            if (statusResponse.statusCode() == 200 || statusResponse.statusCode() == 202) {
                JsonObject statusJson = GSON.fromJson(statusResponse.body(), JsonObject.class);
                String status = statusJson.get("status").getAsString();
                
                if ("COMPLETED".equals(status)) {
                    completed = true;
                } else if ("FAILED".equals(status)) {
                    throw new IOException("Z-Image generation failed");
                }
            }
        }
        
        if (!completed) {
            throw new IOException("Z-Image generation timed out after " + maxAttempts + " attempts");
        }
        
        // Get the result
        HttpRequest resultRequest = HttpRequest.newBuilder()
                .uri(URI.create(responseUrl))
                .header("Authorization", "Key " + apiKey)
                .GET()
                .build();
        
        HttpResponse<String> resultResponse = httpClient.send(resultRequest, HttpResponse.BodyHandlers.ofString());
        
        if (resultResponse.statusCode() != 200) {
            LOGGER.error("Failed to get Z-Image result: {} - {}", resultResponse.statusCode(), resultResponse.body());
            throw new IOException("Failed to get Z-Image result from fal");
        }
        
        JsonObject resultJson = GSON.fromJson(resultResponse.body(), JsonObject.class);
        String imageUrl = resultJson.getAsJsonArray("images")
                .get(0).getAsJsonObject()
                .get("url").getAsString();
        
        return imageUrl;
    }

    /**
     * Converts an image to a 3D model using SAM-3
     * @param imageUrl The URL of the source image
     * @param prompt A short description of the object (helps SAM-3 understand the subject)
     * @return ModelResult containing the GLB data
     * @throws IOException If network operations fail
     * @throws InterruptedException If the thread is interrupted during polling
     */
    public ModelResult generate3DWithSam3(String imageUrl, String prompt) throws IOException, InterruptedException {
        // Build request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("image_url", imageUrl);
        requestBody.addProperty("prompt", prompt);
        requestBody.add("point_prompts", new JsonArray());
        requestBody.add("box_prompts", new JsonArray());
        
        String requestBodyJson = GSON.toJson(requestBody);
        
        HttpRequest submitRequest = HttpRequest.newBuilder()
                .uri(URI.create(FAL_SAM3_QUEUE_SUBMIT))
                .header("Authorization", "Key " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
        
        HttpResponse<String> submitResponse = httpClient.send(submitRequest, HttpResponse.BodyHandlers.ofString());
        
        if (submitResponse.statusCode() != 200) {
            LOGGER.error("SAM-3 queue submit error: {} - {}", submitResponse.statusCode(), submitResponse.body());
            throw new IOException("Failed to submit SAM-3 request to fal queue: " + submitResponse.statusCode());
        }
        
        JsonObject submitJson = GSON.fromJson(submitResponse.body(), JsonObject.class);
        String requestId = submitJson.get("request_id").getAsString();
        String responseUrl = submitJson.get("response_url").getAsString();
        String statusUrl = submitJson.get("status_url").getAsString();
        
        // Poll for completion (SAM-3 takes ~30-60 seconds)
        boolean completed = false;
        int attempts = 0;
        int maxAttempts = 60; // 60 attempts * 2 seconds = 2 minutes max
        
        while (!completed && attempts < maxAttempts) {
            Thread.sleep(2000); // Poll every 2 seconds
            attempts++;
            
            HttpRequest statusRequest = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Key " + apiKey)
                    .GET()
                    .build();
            
            HttpResponse<String> statusResponse = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());
            
            if (statusResponse.statusCode() == 200 || statusResponse.statusCode() == 202) {
                JsonObject statusJson = GSON.fromJson(statusResponse.body(), JsonObject.class);
                String status = statusJson.get("status").getAsString();
                
                if ("COMPLETED".equals(status)) {
                    completed = true;
                } else if ("FAILED".equals(status)) {
                    throw new IOException("SAM-3 3D generation failed");
                }
            }
        }
        
        if (!completed) {
            throw new IOException("SAM-3 3D generation timed out after " + maxAttempts + " attempts");
        }
        
        // Get the result
        HttpRequest resultRequest = HttpRequest.newBuilder()
                .uri(URI.create(responseUrl))
                .header("Authorization", "Key " + apiKey)
                .GET()
                .build();
        
        HttpResponse<String> resultResponse = httpClient.send(resultRequest, HttpResponse.BodyHandlers.ofString());
        
        if (resultResponse.statusCode() != 200) {
            String errorBody = resultResponse.body();
            LOGGER.error("Failed to get SAM-3D result: {} - {}", resultResponse.statusCode(), errorBody);
            // Include error details in exception for retry logic
            if (errorBody != null && errorBody.contains("no masks")) {
                throw new IOException("SAM-3D segmentation failed: no masks produced");
            }
            throw new IOException("Failed to get SAM-3D result from fal: " + resultResponse.statusCode());
        }
        
        JsonObject resultJson = GSON.fromJson(resultResponse.body(), JsonObject.class);
        String glbUrl = resultJson.getAsJsonObject("model_glb")
                .get("url").getAsString();
        
        // Download the GLB file
        byte[] glbData = downloadFile(glbUrl);
        
        // SAM-3 embeds textures in the GLB, no separate texture URL
        return new ModelResult(glbData, null);
    }

    /**
     * Uses a Vision Language Model to describe an image for better SAM-3D segmentation.
     * When SAM-3D fails because the user prompt is too abstract (e.g. "A grand Minecraft lobby"),
     * this method analyzes the actual generated image and produces a concrete description
     * that SAM-3D can use for segmentation.
     * 
     * @param imageUrl The URL of the image to describe
     * @param originalPrompt The original user prompt (for context)
     * @return A concrete, visual description suitable for SAM-3D segmentation
     * @throws IOException If network operations fail
     * @throws InterruptedException If the thread is interrupted during polling
     */
    public String describeImageForSegmentation(String imageUrl, String originalPrompt) throws IOException, InterruptedException {
        // System prompt instructs the VLM to produce a concrete, segmentation-friendly description
        String systemPrompt = "Describe the main subject in this image in simple, concrete, visual terms " +
                "for object segmentation. Focus on physical appearance, not abstract concepts. " +
                "Keep it very short (under 10 words). " +
                "Examples: 'A stone castle with towers', 'A medieval building', 'A character figure', 'A wooden house'. " +
                "Just output the description, nothing else.";
        
        // Build request body
        JsonObject requestBody = new JsonObject();
        JsonArray imageUrls = new JsonArray();
        imageUrls.add(imageUrl);
        requestBody.add("image_urls", imageUrls);
        requestBody.addProperty("prompt", "What is the main subject in this image? The original request was: " + originalPrompt);
        requestBody.addProperty("system_prompt", systemPrompt);
        requestBody.addProperty("model", "google/gemini-2.5-flash");
        
        String requestBodyJson = GSON.toJson(requestBody);
        
        HttpRequest submitRequest = HttpRequest.newBuilder()
                .uri(URI.create(FAL_VLM_QUEUE_SUBMIT))
                .header("Authorization", "Key " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
        
        HttpResponse<String> submitResponse = httpClient.send(submitRequest, HttpResponse.BodyHandlers.ofString());
        
        if (submitResponse.statusCode() != 200) {
            LOGGER.error("VLM queue submit error: {} - {}", submitResponse.statusCode(), submitResponse.body());
            throw new IOException("Failed to submit VLM request: " + submitResponse.statusCode());
        }
        
        JsonObject submitJson = GSON.fromJson(submitResponse.body(), JsonObject.class);
        String responseUrl = submitJson.get("response_url").getAsString();
        String statusUrl = submitJson.get("status_url").getAsString();
        
        // Poll for completion (VLM is fast, usually 2-5 seconds)
        boolean completed = false;
        int attempts = 0;
        int maxAttempts = 30;
        
        while (!completed && attempts < maxAttempts) {
            Thread.sleep(1000);
            attempts++;
            
            HttpRequest statusRequest = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Key " + apiKey)
                    .GET()
                    .build();
            
            HttpResponse<String> statusResponse = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());
            
            if (statusResponse.statusCode() == 200 || statusResponse.statusCode() == 202) {
                JsonObject statusJson = GSON.fromJson(statusResponse.body(), JsonObject.class);
                String status = statusJson.get("status").getAsString();
                
                if ("COMPLETED".equals(status)) {
                    completed = true;
                } else if ("FAILED".equals(status)) {
                    throw new IOException("VLM request failed");
                }
            }
        }
        
        if (!completed) {
            throw new IOException("VLM request timed out");
        }
        
        // Get the result
        HttpRequest resultRequest = HttpRequest.newBuilder()
                .uri(URI.create(responseUrl))
                .header("Authorization", "Key " + apiKey)
                .GET()
                .build();
        
        HttpResponse<String> resultResponse = httpClient.send(resultRequest, HttpResponse.BodyHandlers.ofString());
        
        if (resultResponse.statusCode() != 200) {
            LOGGER.error("Failed to get VLM result: {} - {}", resultResponse.statusCode(), resultResponse.body());
            throw new IOException("Failed to get VLM result");
        }
        
        JsonObject resultJson = GSON.fromJson(resultResponse.body(), JsonObject.class);
        String description = resultJson.get("output").getAsString().trim();
        
        // Clean up the description - remove quotes, periods, etc.
        description = description.replaceAll("^[\"']|[\"']$", "").trim();
        if (description.endsWith(".")) {
            description = description.substring(0, description.length() - 1);
        }
        
        LOGGER.info("VLM described image as: '{}'", description);
        return description;
    }

    /**
     * Fast 3D model generation using Z-Image Turbo + SAM-3D pipeline
     * Much faster than Meshy-6 (~30 seconds vs ~7 minutes)
     * 
     * If SAM-3D fails to segment with the user's prompt (too abstract),
     * we use a VLM to analyze the actual image and produce a better description.
     * 
     * @param prompt The text prompt describing the desired 3D model
     * @return ModelResult containing GLB data
     * @throws IOException If network operations fail
     * @throws InterruptedException If the thread is interrupted during polling
     */
    public ModelResult generateModelFast(String prompt) throws IOException, InterruptedException {
        // Step 1: Generate 2D image with Z-Image Turbo
        String imageUrl = generateImageWithZImage(prompt);
        
        // Step 2: Convert image to 3D with SAM-3D
        // If segmentation fails, use VLM to get a better description, then fall back to "figure"
        ModelResult result;
        try {
            result = generate3DWithSam3(imageUrl, prompt);
        } catch (IOException e) {
            // Check if this is a segmentation failure (no masks found)
            if (e.getMessage() != null && e.getMessage().contains("no masks")) {
                LOGGER.warn("SAM-3D segmentation failed with original prompt, using VLM to analyze image...");
                
                // Try using VLM to get a better description
                try {
                    String betterPrompt = describeImageForSegmentation(imageUrl, prompt);
                    LOGGER.info("Retrying SAM-3D with VLM description: '{}'", betterPrompt);
                    result = generate3DWithSam3(imageUrl, betterPrompt);
                } catch (IOException vlmError) {
                    // VLM failed or SAM-3D still failed - use ultimate fallback
                    LOGGER.warn("VLM approach failed, using generic 'figure' fallback...");
                    result = generate3DWithSam3(imageUrl, "figure");
                }
            } else {
                throw e; // Re-throw other errors
            }
        }
        
        return result;
    }

    // ==================== CRAFT MODE: Nano Banana Pro + Hunyuan 3D Pipeline ====================

    /**
     * Generates an image from a text prompt using Nano Banana Pro (Gemini 3 Pro Image)
     * Optimized for Hunyuan 3D: front-facing view with clean background
     * @param prompt The base prompt (will be augmented for 3D-friendly output)
     * @return The URL of the generated image
     */
    public String generateImageWithNanoBanana(String prompt) throws IOException, InterruptedException {
        String augmentedPrompt = prompt + ", centered, plain white background, single subject";

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("prompt", augmentedPrompt);
        requestBody.addProperty("num_images", 1);
        requestBody.addProperty("aspect_ratio", "1:1");
        requestBody.addProperty("output_format", "png");
        requestBody.addProperty("resolution", "1K");

        String requestBodyJson = GSON.toJson(requestBody);

        HttpRequest submitRequest = HttpRequest.newBuilder()
                .uri(URI.create(FAL_NANOBANANA_QUEUE_SUBMIT))
                .header("Authorization", "Key " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();

        HttpResponse<String> submitResponse = httpClient.send(submitRequest, HttpResponse.BodyHandlers.ofString());

        if (submitResponse.statusCode() != 200) {
            LOGGER.error("Nano Banana Pro queue submit error: {} - {}", submitResponse.statusCode(), submitResponse.body());
            throw new IOException("Failed to submit Nano Banana Pro request: " + submitResponse.statusCode());
        }

        JsonObject submitJson = GSON.fromJson(submitResponse.body(), JsonObject.class);
        String responseUrl = submitJson.get("response_url").getAsString();
        String statusUrl = submitJson.get("status_url").getAsString();

        // Poll for completion
        boolean completed = false;
        int attempts = 0;
        int maxAttempts = 60; // 60 attempts * 2 seconds = 2 minutes max

        while (!completed && attempts < maxAttempts) {
            Thread.sleep(2000);
            attempts++;

            HttpRequest statusRequest = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Key " + apiKey)
                    .GET()
                    .build();

            HttpResponse<String> statusResponse = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());

            if (statusResponse.statusCode() == 200 || statusResponse.statusCode() == 202) {
                JsonObject statusJson = GSON.fromJson(statusResponse.body(), JsonObject.class);
                String status = statusJson.get("status").getAsString();

                if ("COMPLETED".equals(status)) {
                    completed = true;
                } else if ("FAILED".equals(status)) {
                    throw new IOException("Nano Banana Pro generation failed");
                }
            }
        }

        if (!completed) {
            throw new IOException("Nano Banana Pro generation timed out after " + maxAttempts + " attempts");
        }

        HttpRequest resultRequest = HttpRequest.newBuilder()
                .uri(URI.create(responseUrl))
                .header("Authorization", "Key " + apiKey)
                .GET()
                .build();

        HttpResponse<String> resultResponse = httpClient.send(resultRequest, HttpResponse.BodyHandlers.ofString());

        if (resultResponse.statusCode() != 200) {
            LOGGER.error("Failed to get Nano Banana Pro result: {} - {}", resultResponse.statusCode(), resultResponse.body());
            throw new IOException("Failed to get Nano Banana Pro result from fal");
        }

        JsonObject resultJson = GSON.fromJson(resultResponse.body(), JsonObject.class);
        String imageUrl = resultJson.getAsJsonArray("images")
                .get(0).getAsJsonObject()
                .get("url").getAsString();

        LOGGER.info("Nano Banana Pro image generated: {}", imageUrl);
        return imageUrl;
    }

    /**
     * Converts an image to a 3D model using Hunyuan 3D v3.1 Pro
     * Produces high-quality UV-textured GLB models
     * @param imageUrl The URL of the front-view source image
     * @return ModelResult containing the GLB data
     */
    public ModelResult generate3DWithHunyuan(String imageUrl) throws IOException, InterruptedException {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("input_image_url", imageUrl);
        requestBody.addProperty("generate_type", "Normal");
        requestBody.addProperty("enable_pbr", false);

        String requestBodyJson = GSON.toJson(requestBody);

        HttpRequest submitRequest = HttpRequest.newBuilder()
                .uri(URI.create(FAL_HUNYUAN_QUEUE_SUBMIT))
                .header("Authorization", "Key " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();

        HttpResponse<String> submitResponse = httpClient.send(submitRequest, HttpResponse.BodyHandlers.ofString());

        if (submitResponse.statusCode() != 200) {
            LOGGER.error("Hunyuan 3D queue submit error: {} - {}", submitResponse.statusCode(), submitResponse.body());
            throw new IOException("Failed to submit Hunyuan 3D request: " + submitResponse.statusCode());
        }

        JsonObject submitJson = GSON.fromJson(submitResponse.body(), JsonObject.class);
        String responseUrl = submitJson.get("response_url").getAsString();
        String statusUrl = submitJson.get("status_url").getAsString();

        // Poll for completion (Hunyuan 3D can take a few minutes)
        boolean completed = false;
        int attempts = 0;
        int maxAttempts = 120; // 120 attempts * 3 seconds = 6 minutes max

        while (!completed && attempts < maxAttempts) {
            Thread.sleep(3000);
            attempts++;

            HttpRequest statusRequest = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Key " + apiKey)
                    .GET()
                    .build();

            HttpResponse<String> statusResponse = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());

            if (statusResponse.statusCode() == 200 || statusResponse.statusCode() == 202) {
                JsonObject statusJson = GSON.fromJson(statusResponse.body(), JsonObject.class);
                String status = statusJson.get("status").getAsString();

                if ("COMPLETED".equals(status)) {
                    completed = true;
                } else if ("FAILED".equals(status)) {
                    throw new IOException("Hunyuan 3D generation failed");
                }
            }
        }

        if (!completed) {
            throw new IOException("Hunyuan 3D generation timed out after " + maxAttempts + " attempts");
        }

        HttpRequest resultRequest = HttpRequest.newBuilder()
                .uri(URI.create(responseUrl))
                .header("Authorization", "Key " + apiKey)
                .GET()
                .build();

        HttpResponse<String> resultResponse = httpClient.send(resultRequest, HttpResponse.BodyHandlers.ofString());

        if (resultResponse.statusCode() != 200) {
            LOGGER.error("Failed to get Hunyuan 3D result: {} - {}", resultResponse.statusCode(), resultResponse.body());
            throw new IOException("Failed to get Hunyuan 3D result from fal: " + resultResponse.statusCode());
        }

        JsonObject resultJson = GSON.fromJson(resultResponse.body(), JsonObject.class);
        String glbUrl = resultJson.getAsJsonObject("model_glb")
                .get("url").getAsString();

        LOGGER.info("Hunyuan 3D model generated, downloading GLB...");
        byte[] glbData = downloadFile(glbUrl);

        // Hunyuan produces UV-textured GLB with embedded textures
        return new ModelResult(glbData, null);
    }

    /**
     * Full Craft pipeline: Nano Banana Pro (text→image) + Hunyuan 3D v3.1 Pro (image→3D)
     * Higher quality than Z-Image + SAM-3D, takes a bit longer
     * @param prompt The text prompt describing the desired 3D model
     * @return ModelResult containing GLB data
     */
    public ModelResult generateModelHunyuan(String prompt) throws IOException, InterruptedException {
        // Step 1: Generate 2D image with Nano Banana Pro
        String imageUrl = generateImageWithNanoBanana(prompt);

        // Step 2: Convert image to 3D with Hunyuan 3D v3.1 Pro
        return generate3DWithHunyuan(imageUrl);
    }

    // ==================== SPLAT MODE: FLUX.2 [klein] + TripoSplat Pipeline ====================

    /**
     * Generates an image from a text prompt using FLUX.2 [klein] 9B (fast 4-step distilled model).
     * Used by /fal splat for a faster text->image stage than Nano Banana Pro.
     * Augments the prompt for 3D-friendly output (single centered subject, clean background).
     *
     * @param prompt The base prompt (will be augmented)
     * @return The URL of the generated image
     */
    public String generateImageWithFluxKlein(String prompt) throws IOException, InterruptedException {
        String augmentedPrompt = prompt + ", centered, plain white background, single subject";

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("prompt", augmentedPrompt);
        requestBody.addProperty("num_inference_steps", 4);
        requestBody.addProperty("image_size", "square_hd");
        requestBody.addProperty("num_images", 1);
        requestBody.addProperty("enable_safety_checker", true);
        requestBody.addProperty("output_format", "png");

        String requestBodyJson = GSON.toJson(requestBody);

        HttpRequest submitRequest = HttpRequest.newBuilder()
                .uri(URI.create(FAL_FLUX_KLEIN_QUEUE_SUBMIT))
                .header("Authorization", "Key " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();

        HttpResponse<String> submitResponse = httpClient.send(submitRequest, HttpResponse.BodyHandlers.ofString());

        if (submitResponse.statusCode() != 200) {
            LOGGER.error("FLUX klein queue submit error: {} - {}", submitResponse.statusCode(), submitResponse.body());
            throw new IOException("Failed to submit FLUX klein request: " + submitResponse.statusCode());
        }

        JsonObject submitJson = GSON.fromJson(submitResponse.body(), JsonObject.class);
        String responseUrl = submitJson.get("response_url").getAsString();
        String statusUrl = submitJson.get("status_url").getAsString();

        // Poll for completion (klein is fast: 4 steps)
        boolean completed = false;
        int attempts = 0;
        int maxAttempts = 30; // 30 * 1s = 30s max

        while (!completed && attempts < maxAttempts) {
            Thread.sleep(1000);
            attempts++;

            HttpRequest statusRequest = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Key " + apiKey)
                    .GET()
                    .build();

            HttpResponse<String> statusResponse = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());

            if (statusResponse.statusCode() == 200 || statusResponse.statusCode() == 202) {
                JsonObject statusJson = GSON.fromJson(statusResponse.body(), JsonObject.class);
                String status = statusJson.get("status").getAsString();

                if ("COMPLETED".equals(status)) {
                    completed = true;
                } else if ("FAILED".equals(status)) {
                    throw new IOException("FLUX klein generation failed");
                }
            }
        }

        if (!completed) {
            throw new IOException("FLUX klein generation timed out after " + maxAttempts + " attempts");
        }

        HttpRequest resultRequest = HttpRequest.newBuilder()
                .uri(URI.create(responseUrl))
                .header("Authorization", "Key " + apiKey)
                .GET()
                .build();

        HttpResponse<String> resultResponse = httpClient.send(resultRequest, HttpResponse.BodyHandlers.ofString());

        if (resultResponse.statusCode() != 200) {
            LOGGER.error("Failed to get FLUX klein result: {} - {}", resultResponse.statusCode(), resultResponse.body());
            throw new IOException("Failed to get FLUX klein result from fal");
        }

        JsonObject resultJson = GSON.fromJson(resultResponse.body(), JsonObject.class);
        String imageUrl = resultJson.getAsJsonArray("images")
                .get(0).getAsJsonObject()
                .get("url").getAsString();

        LOGGER.info("FLUX klein image generated: {}", imageUrl);
        return imageUrl;
    }

    /**
     * Converts an image to a 3D Gaussian splat using TripoSplat (tripo3d/triposplat).
     * Returns the raw .splat file bytes (binary 32-byte-per-Gaussian format).
     *
     * @param imageUrl     URL of the source image (front view, clean background)
     * @param numGaussians Target Gaussian count (rounded to nearest 32 internally)
     * @return The raw .splat file as a byte array
     */
    public byte[] generateSplatWithTripoSplat(String imageUrl, int numGaussians) throws IOException, InterruptedException {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("image_url", imageUrl);
        requestBody.addProperty("num_gaussians", numGaussians);
        requestBody.addProperty("num_inference_steps", 20);
        requestBody.addProperty("guidance_scale", 3);
        // Request the flat binary .splat format (color + opacity already decoded to bytes)
        // rather than .ply (raw SH coefficients + logit opacity that need decoding).
        requestBody.addProperty("output_format", "splat");
        requestBody.addProperty("enable_safety_checker", true);

        String requestBodyJson = GSON.toJson(requestBody);

        HttpRequest submitRequest = HttpRequest.newBuilder()
                .uri(URI.create(FAL_TRIPOSPLAT_QUEUE_SUBMIT))
                .header("Authorization", "Key " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();

        HttpResponse<String> submitResponse = httpClient.send(submitRequest, HttpResponse.BodyHandlers.ofString());

        if (submitResponse.statusCode() != 200) {
            LOGGER.error("TripoSplat queue submit error: {} - {}", submitResponse.statusCode(), submitResponse.body());
            throw new IOException("Failed to submit TripoSplat request: " + submitResponse.statusCode());
        }

        JsonObject submitJson = GSON.fromJson(submitResponse.body(), JsonObject.class);
        String responseUrl = submitJson.get("response_url").getAsString();
        String statusUrl = submitJson.get("status_url").getAsString();

        // Poll for completion (splat generation is typically faster than Hunyuan)
        boolean completed = false;
        int attempts = 0;
        int maxAttempts = 120; // 120 attempts * 3 seconds = 6 minutes max

        while (!completed && attempts < maxAttempts) {
            Thread.sleep(3000);
            attempts++;

            HttpRequest statusRequest = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Key " + apiKey)
                    .GET()
                    .build();

            HttpResponse<String> statusResponse = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());

            if (statusResponse.statusCode() == 200 || statusResponse.statusCode() == 202) {
                JsonObject statusJson = GSON.fromJson(statusResponse.body(), JsonObject.class);
                String status = statusJson.get("status").getAsString();

                if ("COMPLETED".equals(status)) {
                    completed = true;
                } else if ("FAILED".equals(status)) {
                    throw new IOException("TripoSplat generation failed");
                }
            }
        }

        if (!completed) {
            throw new IOException("TripoSplat generation timed out after " + maxAttempts + " attempts");
        }

        HttpRequest resultRequest = HttpRequest.newBuilder()
                .uri(URI.create(responseUrl))
                .header("Authorization", "Key " + apiKey)
                .GET()
                .build();

        HttpResponse<String> resultResponse = httpClient.send(resultRequest, HttpResponse.BodyHandlers.ofString());

        if (resultResponse.statusCode() != 200) {
            LOGGER.error("Failed to get TripoSplat result: {} - {}", resultResponse.statusCode(), resultResponse.body());
            throw new IOException("Failed to get TripoSplat result from fal: " + resultResponse.statusCode());
        }

        JsonObject resultJson = GSON.fromJson(resultResponse.body(), JsonObject.class);
        // Output field is named "model_mesh" in the schema (it holds the .splat/.ply file).
        String splatUrl = resultJson.getAsJsonObject("model_mesh")
                .get("url").getAsString();

        LOGGER.info("TripoSplat generated, downloading .splat...");
        byte[] splatData = downloadFile(splatUrl);
        LOGGER.info("Downloaded .splat ({} bytes)", splatData.length);

        return splatData;
    }

    /**
     * Full Splat pipeline: Nano Banana Pro (text→image) + TripoSplat (image→Gaussian splat).
     * Experimental alternative to the Hunyuan mesh path; better suited to organic/fuzzy subjects.
     *
     * @param prompt       The text prompt describing the desired object
     * @param numGaussians Target Gaussian count
     * @return The raw .splat file as a byte array
     */
    public byte[] generateSplatModel(String prompt, int numGaussians) throws IOException, InterruptedException {
        // Step 1: Generate 2D image with FLUX.2 [klein] (fast 4-step)
        String imageUrl = generateImageWithFluxKlein(prompt);

        // Step 2: Convert image to a Gaussian splat with TripoSplat
        return generateSplatWithTripoSplat(imageUrl, numGaussians);
    }
}

