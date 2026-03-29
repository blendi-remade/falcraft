package com.falcraft.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for GLB (GL Transmission Format Binary) files
 * GLB format structure:
 * - 12-byte header (magic, version, length)
 * - JSON chunk (scene structure, buffers, accessors)
 * - BIN chunk (binary mesh data)
 */
public class GLBParser {
    private static final Logger LOGGER = LoggerFactory.getLogger("GLBParser");
    private static final Gson GSON = new Gson();
    
    // GLB constants
    private static final int GLB_MAGIC = 0x46546C67; // "glTF" in hex
    private static final int JSON_CHUNK_TYPE = 0x4E4F534A; // "JSON"
    private static final int BIN_CHUNK_TYPE = 0x004E4942; // "BIN\0"
    
    /**
     * Represents parsed mesh data from a GLB file
     * @param vertices Array of vertex positions (x,y,z,x,y,z,...)
     * @param indices Array of triangle indices
     * @param colors Array of vertex colors (RGB as integers) - fallback when no texture
     * @param uvs Array of UV coordinates (u,v,u,v,...), null if no UVs
     */
    public record MeshData(float[] vertices, int[] indices, int[] colors, float[] uvs) {}
    
    /**
     * Extracts the embedded texture from a GLB file if present
     * @param glbData The GLB file as a byte array
     * @return The texture as a byte array (PNG or JPEG), or null if no embedded texture
     */
    public static byte[] extractEmbeddedTexture(byte[] glbData) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(glbData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // Parse GLB header
        int magic = buffer.getInt();
        if (magic != GLB_MAGIC) {
            return null;
        }
        buffer.getInt(); // version
        buffer.getInt(); // length
        
        // Parse JSON chunk
        int jsonChunkLength = buffer.getInt();
        buffer.getInt(); // chunk type
        
        byte[] jsonBytes = new byte[jsonChunkLength];
        buffer.get(jsonBytes);
        String jsonString = new String(jsonBytes);
        JsonObject gltf = GSON.fromJson(jsonString, JsonObject.class);
        
        // Parse BIN chunk
        int binChunkLength = buffer.getInt();
        buffer.getInt(); // chunk type
        
        byte[] binData = new byte[binChunkLength];
        buffer.get(binData);
        
        // Check for embedded images
        if (!gltf.has("images")) {
            return null;
        }
        
        JsonArray images = gltf.getAsJsonArray("images");
        if (images.isEmpty()) {
            return null;
        }
        
        // Get first image
        JsonObject image = images.get(0).getAsJsonObject();
        
        // Check if image is embedded (has bufferView) or external (has uri)
        if (!image.has("bufferView")) {
            return null;
        }
        
        int bufferViewIndex = image.get("bufferView").getAsInt();
        
        // Get buffer view
        JsonArray bufferViews = gltf.getAsJsonArray("bufferViews");
        JsonObject bufferView = bufferViews.get(bufferViewIndex).getAsJsonObject();
        
        int byteOffset = bufferView.has("byteOffset") ? bufferView.get("byteOffset").getAsInt() : 0;
        int byteLength = bufferView.get("byteLength").getAsInt();
        
        // Extract texture data from binary buffer
        byte[] textureData = new byte[byteLength];
        System.arraycopy(binData, byteOffset, textureData, 0, byteLength);
        
        return textureData;
    }
    
    /**
     * Parses a GLB file and extracts mesh data
     * Note: textureSampler parameter is kept for API compatibility but not used.
     * Color sampling now happens in the Voxelizer for better accuracy.
     * @param glbData The GLB file as a byte array
     * @param textureSampler Unused (kept for API compatibility)
     * @return Parsed mesh data
     * @throws IOException If the GLB format is invalid
     */
    public static MeshData parse(byte[] glbData, TextureSampler textureSampler) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(glbData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // Parse GLB header
        int magic = buffer.getInt();
        if (magic != GLB_MAGIC) {
            throw new IOException("Invalid GLB magic number: 0x" + Integer.toHexString(magic));
        }
        
        int version = buffer.getInt();
        int length = buffer.getInt();
        
        // Parse JSON chunk
        int jsonChunkLength = buffer.getInt();
        int jsonChunkType = buffer.getInt();
        
        if (jsonChunkType != JSON_CHUNK_TYPE) {
            throw new IOException("Expected JSON chunk, got: 0x" + Integer.toHexString(jsonChunkType));
        }
        
        byte[] jsonBytes = new byte[jsonChunkLength];
        buffer.get(jsonBytes);
        String jsonString = new String(jsonBytes);
        
        JsonObject gltf = GSON.fromJson(jsonString, JsonObject.class);
        
        // Parse BIN chunk
        int binChunkLength = buffer.getInt();
        int binChunkType = buffer.getInt();
        
        if (binChunkType != BIN_CHUNK_TYPE) {
            throw new IOException("Expected BIN chunk, got: 0x" + Integer.toHexString(binChunkType));
        }
        
        byte[] binData = new byte[binChunkLength];
        buffer.get(binData);
        
        // Diagnostic: dump full glTF structure
        dumpGltfDiagnostics(gltf, binData);

        // Extract mesh data from glTF structure
        return extractMeshData(gltf, binData);
    }

    /**
     * Dumps comprehensive diagnostic info about the glTF structure.
     * This tells us exactly what's inside a Hunyuan (or any) GLB.
     */
    private static void dumpGltfDiagnostics(JsonObject gltf, byte[] binData) {
        LOGGER.info("========== GLB DIAGNOSTICS ==========");
        LOGGER.info("BIN chunk size: {} bytes", binData.length);

        // Images
        if (gltf.has("images")) {
            JsonArray images = gltf.getAsJsonArray("images");
            LOGGER.info("IMAGES: {} total", images.size());
            JsonArray bufferViews = gltf.getAsJsonArray("bufferViews");
            for (int i = 0; i < images.size(); i++) {
                JsonObject img = images.get(i).getAsJsonObject();
                String mimeType = img.has("mimeType") ? img.get("mimeType").getAsString() : "unknown";
                String name = img.has("name") ? img.get("name").getAsString() : "unnamed";
                if (img.has("bufferView")) {
                    int bvIdx = img.get("bufferView").getAsInt();
                    JsonObject bv = bufferViews.get(bvIdx).getAsJsonObject();
                    int len = bv.get("byteLength").getAsInt();
                    LOGGER.info("  Image[{}]: name='{}' mime='{}' bufferView={} size={}bytes", i, name, mimeType, bvIdx, len);
                } else if (img.has("uri")) {
                    LOGGER.info("  Image[{}]: name='{}' mime='{}' uri='{}'", i, name, mimeType, img.get("uri").getAsString().substring(0, Math.min(80, img.get("uri").getAsString().length())));
                } else {
                    LOGGER.info("  Image[{}]: name='{}' mime='{}' (no bufferView or uri!)", i, name, mimeType);
                }
            }
        } else {
            LOGGER.info("IMAGES: NONE");
        }

        // Textures
        if (gltf.has("textures")) {
            JsonArray textures = gltf.getAsJsonArray("textures");
            LOGGER.info("TEXTURES: {} total", textures.size());
            for (int i = 0; i < textures.size(); i++) {
                JsonObject tex = textures.get(i).getAsJsonObject();
                int source = tex.has("source") ? tex.get("source").getAsInt() : -1;
                int sampler = tex.has("sampler") ? tex.get("sampler").getAsInt() : -1;
                LOGGER.info("  Texture[{}]: source(image)={} sampler={}", i, source, sampler);
            }
        } else {
            LOGGER.info("TEXTURES: NONE");
        }

        // Materials
        if (gltf.has("materials")) {
            JsonArray materials = gltf.getAsJsonArray("materials");
            LOGGER.info("MATERIALS: {} total", materials.size());
            for (int i = 0; i < materials.size(); i++) {
                JsonObject mat = materials.get(i).getAsJsonObject();
                String name = mat.has("name") ? mat.get("name").getAsString() : "unnamed";
                StringBuilder sb = new StringBuilder();
                sb.append("  Material[").append(i).append("]: name='").append(name).append("'");

                if (mat.has("pbrMetallicRoughness")) {
                    JsonObject pbr = mat.getAsJsonObject("pbrMetallicRoughness");
                    if (pbr.has("baseColorTexture")) {
                        int texIdx = pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt();
                        sb.append(" baseColorTexture=").append(texIdx);
                    }
                    if (pbr.has("baseColorFactor")) {
                        sb.append(" baseColorFactor=").append(pbr.getAsJsonArray("baseColorFactor"));
                    }
                }
                if (mat.has("normalTexture")) {
                    int texIdx = mat.getAsJsonObject("normalTexture").get("index").getAsInt();
                    sb.append(" normalTexture=").append(texIdx);
                }
                LOGGER.info(sb.toString());
            }
        } else {
            LOGGER.info("MATERIALS: NONE");
        }

        // Meshes + primitives
        if (gltf.has("meshes")) {
            JsonArray meshes = gltf.getAsJsonArray("meshes");
            LOGGER.info("MESHES: {} total", meshes.size());
            for (int m = 0; m < meshes.size(); m++) {
                JsonObject mesh = meshes.get(m).getAsJsonObject();
                String name = mesh.has("name") ? mesh.get("name").getAsString() : "unnamed";
                JsonArray primitives = mesh.getAsJsonArray("primitives");
                LOGGER.info("  Mesh[{}]: name='{}' primitives={}", m, name, primitives.size());

                for (int p = 0; p < primitives.size(); p++) {
                    JsonObject prim = primitives.get(p).getAsJsonObject();
                    JsonObject attrs = prim.getAsJsonObject("attributes");
                    int matIdx = prim.has("material") ? prim.get("material").getAsInt() : -1;

                    StringBuilder sb = new StringBuilder();
                    sb.append("    Prim[").append(p).append("]: material=").append(matIdx);
                    sb.append(" attrs=[");
                    for (String key : attrs.keySet()) {
                        sb.append(key).append("(acc=").append(attrs.get(key).getAsInt()).append(") ");
                    }
                    sb.append("]");

                    if (prim.has("indices")) {
                        int indicesAcc = prim.get("indices").getAsInt();
                        JsonObject acc = gltf.getAsJsonArray("accessors").get(indicesAcc).getAsJsonObject();
                        sb.append(" indices=").append(acc.get("count").getAsInt());
                    }

                    LOGGER.info(sb.toString());
                }
            }
        }

        // BufferViews with stride info
        if (gltf.has("bufferViews")) {
            JsonArray bvs = gltf.getAsJsonArray("bufferViews");
            int stridedCount = 0;
            for (int i = 0; i < bvs.size(); i++) {
                if (bvs.get(i).getAsJsonObject().has("byteStride")) stridedCount++;
            }
            LOGGER.info("BUFFER_VIEWS: {} total ({} with byteStride)", bvs.size(), stridedCount);
        }

        // UV range check - sample first mesh's first primitive
        if (gltf.has("meshes")) {
            try {
                JsonObject firstPrim = gltf.getAsJsonArray("meshes").get(0).getAsJsonObject()
                        .getAsJsonArray("primitives").get(0).getAsJsonObject();
                JsonObject attrs = firstPrim.getAsJsonObject("attributes");
                if (attrs.has("TEXCOORD_0")) {
                    int uvAcc = attrs.get("TEXCOORD_0").getAsInt();
                    float[] uvs = extractFloatArray(gltf, binData, uvAcc);
                    float uMin = Float.MAX_VALUE, uMax = -Float.MAX_VALUE;
                    float vMin = Float.MAX_VALUE, vMax = -Float.MAX_VALUE;
                    for (int i = 0; i < uvs.length; i += 2) {
                        uMin = Math.min(uMin, uvs[i]);
                        uMax = Math.max(uMax, uvs[i]);
                        vMin = Math.min(vMin, uvs[i + 1]);
                        vMax = Math.max(vMax, uvs[i + 1]);
                    }
                    LOGGER.info("UV RANGE (first primitive): U=[{}, {}] V=[{}, {}]",
                            String.format("%.4f", uMin), String.format("%.4f", uMax),
                            String.format("%.4f", vMin), String.format("%.4f", vMax));
                    LOGGER.info("  -> If V range is [0,1]: flipV=false is correct (glTF standard)");
                    LOGGER.info("  -> If V goes negative or >1: UVs may use wrapping/repeat");
                } else {
                    LOGGER.info("UV: First primitive has NO TEXCOORD_0");
                }
            } catch (Exception e) {
                LOGGER.warn("Could not analyze UV range: {}", e.getMessage());
            }
        }

        LOGGER.info("========== END DIAGNOSTICS ==========");
    }
    
    /**
     * Extracts mesh data from glTF JSON structure and binary buffer
     * UV coordinates are passed through for per-voxel sampling in the Voxelizer
     */
    private static MeshData extractMeshData(JsonObject gltf, byte[] binData) throws IOException {
        JsonArray meshes = gltf.getAsJsonArray("meshes");
        if (meshes == null || meshes.isEmpty()) {
            throw new IOException("No meshes found in glTF");
        }
        
        List<Float> allVertices = new ArrayList<>();
        List<Integer> allIndices = new ArrayList<>();
        List<Integer> allColors = new ArrayList<>();
        List<Float> allUVs = new ArrayList<>();

        int vertexOffset = 0;

        LOGGER.info("Processing {} meshes from GLB", meshes.size());

        // Process ALL meshes (not just the first)
        for (int m = 0; m < meshes.size(); m++) {
        JsonObject mesh = meshes.get(m).getAsJsonObject();
        JsonArray primitives = mesh.getAsJsonArray("primitives");

        for (int i = 0; i < primitives.size(); i++) {
            JsonObject primitive = primitives.get(i).getAsJsonObject();
            JsonObject attributes = primitive.getAsJsonObject("attributes");
            
            // Extract vertex positions
            int positionAccessorIndex = attributes.get("POSITION").getAsInt();
            float[] positions = extractFloatArray(gltf, binData, positionAccessorIndex);
            
            // Extract indices
            if (primitive.has("indices")) {
                int indicesAccessorIndex = primitive.get("indices").getAsInt();
                int[] indices = extractIntArray(gltf, binData, indicesAccessorIndex);
                
                // Add indices with offset
                for (int index : indices) {
                    allIndices.add(index + vertexOffset);
                }
            } else {
                // No indices - create them sequentially
                for (int j = 0; j < positions.length / 3; j++) {
                    allIndices.add(vertexOffset + j);
                }
            }
            
            // Extract UV coordinates (critical for per-voxel texture sampling!)
            float[] uvs = null;
            if (attributes.has("TEXCOORD_0")) {
                int uvAccessorIndex = attributes.get("TEXCOORD_0").getAsInt();
                uvs = extractFloatArray(gltf, binData, uvAccessorIndex);
            }
            
            // Generate fallback colors from vertex colors or position-based
            // These are only used if texture sampling fails
            int[] colors;
            if (attributes.has("COLOR_0")) {
                // Use vertex colors from GLB
                int colorAccessorIndex = attributes.get("COLOR_0").getAsInt();
                colors = extractColorArray(gltf, binData, colorAccessorIndex);
            } else {
                // Generate fallback colors based on vertex position
                colors = new int[positions.length / 3];
                for (int j = 0; j < colors.length; j++) {
                    // Generate varied colors based on position (normalized)
                    float x = positions[j * 3];
                    float y = positions[j * 3 + 1];
                    float z = positions[j * 3 + 2];
                    
                    // Create neutral gray colors as fallback
                    int gray = (int) ((Math.abs(x * 50) + Math.abs(y * 50) + Math.abs(z * 50)) % 128) + 64;
                    colors[j] = (gray << 16) | (gray << 8) | gray;
                }
            }
            
            // Add to combined lists
            for (float pos : positions) {
                allVertices.add(pos);
            }
            for (int color : colors) {
                allColors.add(color);
            }
            if (uvs != null) {
                for (float uv : uvs) {
                    allUVs.add(uv);
                }
            } else {
                // Add zero UVs as placeholder (will not be used for texture sampling)
                for (int j = 0; j < positions.length / 3; j++) {
                    allUVs.add(0.0f);
                    allUVs.add(0.0f);
                }
            }
            
            vertexOffset += positions.length / 3;
        }
        } // end mesh loop

        LOGGER.info("Total: {} vertices, {} indices from all meshes", vertexOffset, allIndices.size());

        // Convert lists to arrays
        float[] vertices = new float[allVertices.size()];
        for (int i = 0; i < allVertices.size(); i++) {
            vertices[i] = allVertices.get(i);
        }
        
        int[] indices = allIndices.stream().mapToInt(Integer::intValue).toArray();
        int[] colors = allColors.stream().mapToInt(Integer::intValue).toArray();
        
        float[] uvs = new float[allUVs.size()];
        for (int i = 0; i < allUVs.size(); i++) {
            uvs[i] = allUVs.get(i);
        }
        
        return new MeshData(vertices, indices, colors, uvs);
    }
    
    /**
     * Extracts a float array from glTF accessor
     */
    private static float[] extractFloatArray(JsonObject gltf, byte[] binData, int accessorIndex) throws IOException {
        JsonObject accessor = gltf.getAsJsonArray("accessors").get(accessorIndex).getAsJsonObject();
        int bufferViewIndex = accessor.get("bufferView").getAsInt();
        int count = accessor.get("count").getAsInt();
        String type = accessor.get("type").getAsString();

        int byteOffset = accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0;

        JsonObject bufferView = gltf.getAsJsonArray("bufferViews").get(bufferViewIndex).getAsJsonObject();
        int bufferViewOffset = bufferView.has("byteOffset") ? bufferView.get("byteOffset").getAsInt() : 0;
        int byteStride = bufferView.has("byteStride") ? bufferView.get("byteStride").getAsInt() : 0;

        int componentsPerElement = getComponentCount(type);
        int elementSize = componentsPerElement * 4; // 4 bytes per float
        if (byteStride == 0) byteStride = elementSize; // tightly packed

        ByteBuffer buffer = ByteBuffer.wrap(binData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int baseOffset = bufferViewOffset + byteOffset;
        float[] result = new float[count * componentsPerElement];

        for (int i = 0; i < count; i++) {
            buffer.position(baseOffset + i * byteStride);
            for (int c = 0; c < componentsPerElement; c++) {
                result[i * componentsPerElement + c] = buffer.getFloat();
            }
        }

        return result;
    }
    
    /**
     * Extracts an integer array from glTF accessor (for indices)
     */
    private static int[] extractIntArray(JsonObject gltf, byte[] binData, int accessorIndex) throws IOException {
        JsonObject accessor = gltf.getAsJsonArray("accessors").get(accessorIndex).getAsJsonObject();
        int bufferViewIndex = accessor.get("bufferView").getAsInt();
        int count = accessor.get("count").getAsInt();
        int componentType = accessor.get("componentType").getAsInt();
        
        int byteOffset = accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0;
        
        JsonObject bufferView = gltf.getAsJsonArray("bufferViews").get(bufferViewIndex).getAsJsonObject();
        int bufferViewOffset = bufferView.has("byteOffset") ? bufferView.get("byteOffset").getAsInt() : 0;
        
        ByteBuffer buffer = ByteBuffer.wrap(binData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(bufferViewOffset + byteOffset);
        
        int[] result = new int[count];
        
        // Component type: 5121=ubyte, 5123=ushort, 5125=uint
        for (int i = 0; i < count; i++) {
            if (componentType == 5121) { // UNSIGNED_BYTE
                result[i] = buffer.get() & 0xFF;
            } else if (componentType == 5123) { // UNSIGNED_SHORT
                result[i] = buffer.getShort() & 0xFFFF;
            } else if (componentType == 5125) { // UNSIGNED_INT
                result[i] = buffer.getInt();
            } else {
                throw new IOException("Unsupported index component type: " + componentType);
            }
        }
        
        return result;
    }
    
    /**
     * Extracts color data and converts to RGB integers.
     * Handles different glTF color formats:
     * - FLOAT (5126): 4 bytes per component
     * - UNSIGNED_BYTE (5121): 1 byte per component, normalized 0-255 → 0.0-1.0
     * - UNSIGNED_SHORT (5123): 2 bytes per component, normalized
     * And both VEC3 (RGB) and VEC4 (RGBA) types.
     */
    private static int[] extractColorArray(JsonObject gltf, byte[] binData, int accessorIndex) throws IOException {
        JsonObject accessor = gltf.getAsJsonArray("accessors").get(accessorIndex).getAsJsonObject();
        int bufferViewIndex = accessor.get("bufferView").getAsInt();
        int count = accessor.get("count").getAsInt();
        int componentType = accessor.get("componentType").getAsInt();
        String type = accessor.get("type").getAsString();

        int byteOffset = accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0;

        JsonObject bufferView = gltf.getAsJsonArray("bufferViews").get(bufferViewIndex).getAsJsonObject();
        int bufferViewOffset = bufferView.has("byteOffset") ? bufferView.get("byteOffset").getAsInt() : 0;
        int byteStride = bufferView.has("byteStride") ? bufferView.get("byteStride").getAsInt() : 0;

        int componentsPerVertex = getComponentCount(type); // 3 for VEC3, 4 for VEC4

        // Calculate element size for stride
        int componentSize = switch (componentType) {
            case 5126 -> 4; // FLOAT
            case 5121 -> 1; // UNSIGNED_BYTE
            case 5123 -> 2; // UNSIGNED_SHORT
            default -> throw new IOException("Unsupported color component type: " + componentType);
        };
        if (byteStride == 0) byteStride = componentsPerVertex * componentSize;

        int baseOffset = bufferViewOffset + byteOffset;

        ByteBuffer buffer = ByteBuffer.wrap(binData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int[] colors = new int[count];

        for (int i = 0; i < count; i++) {
            buffer.position(baseOffset + i * byteStride);
            float r, g, b;

            if (componentType == 5126) { // FLOAT
                r = buffer.getFloat();
                g = buffer.getFloat();
                b = buffer.getFloat();
                if (componentsPerVertex == 4) buffer.getFloat(); // skip alpha
            } else if (componentType == 5121) { // UNSIGNED_BYTE (normalized)
                r = (buffer.get() & 0xFF) / 255.0f;
                g = (buffer.get() & 0xFF) / 255.0f;
                b = (buffer.get() & 0xFF) / 255.0f;
                if (componentsPerVertex == 4) buffer.get(); // skip alpha
            } else if (componentType == 5123) { // UNSIGNED_SHORT (normalized)
                r = (buffer.getShort() & 0xFFFF) / 65535.0f;
                g = (buffer.getShort() & 0xFFFF) / 65535.0f;
                b = (buffer.getShort() & 0xFFFF) / 65535.0f;
                if (componentsPerVertex == 4) buffer.getShort(); // skip alpha
            } else {
                // Already validated in switch above, can't reach here
                throw new IOException("Unsupported color component type: " + componentType);
            }
            int ri = Math.min(255, Math.max(0, (int) (r * 255)));
            int gi = Math.min(255, Math.max(0, (int) (g * 255)));
            int bi = Math.min(255, Math.max(0, (int) (b * 255)));
            colors[i] = (ri << 16) | (gi << 8) | bi;
        }
        
        return colors;
    }
    
    /**
     * Gets the number of components for a glTF type
     */
    private static int getComponentCount(String type) {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT2" -> 4;
            case "MAT3" -> 9;
            case "MAT4" -> 16;
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
    }
}
