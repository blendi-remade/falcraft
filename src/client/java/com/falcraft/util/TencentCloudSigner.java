package com.falcraft.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * 腾讯云API TC3-HMAC-SHA256签名工具类
 */
public class TencentCloudSigner {
    private static final String ALGORITHM = "TC3-HMAC-SHA256";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SHA256 = "SHA-256";
    
    private final String secretId;
    private final String secretKey;
    
    public TencentCloudSigner(String secretId, String secretKey) {
        this.secretId = secretId;
        this.secretKey = secretKey;
    }
    
    /**
     * 生成签名Authorization header
     * @param service 服务名称，如 "ai3d"
     * @param host 请求域名
     * @param action API接口名称
     * @param version API版本
     * @param region 地域参数，如 "ap-guangzhou"，可选
     * @param timestamp Unix时间戳（秒）
     * @param payload 请求体JSON字符串
     * @return Authorization header值
     */
    public String generateAuthorization(String service, String host, String action, 
                                         String version, String region, long timestamp, String payload) throws Exception {
        // 1. 生成日期字符串
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String date = sdf.format(new Date(timestamp * 1000));
        
        // 2. 拼接规范请求串
        String httpRequestMethod = "POST";
        String canonicalUri = "/";
        String canonicalQueryString = "";
        String canonicalHeaders = "content-type:application/json\n" +
                                  "host:" + host + "\n";
        String signedHeaders = "content-type;host";
        String hashedRequestPayload = sha256Hex(payload);
        
        String canonicalRequest = httpRequestMethod + "\n" +
                                  canonicalUri + "\n" +
                                  canonicalQueryString + "\n" +
                                  canonicalHeaders + "\n" +
                                  signedHeaders + "\n" +
                                  hashedRequestPayload;
        
        // 3. 拼接待签名字符串
        String credentialScope = date + "/" + service + "/tc3_request";
        String hashedCanonicalRequest = sha256Hex(canonicalRequest);
        String stringToSign = ALGORITHM + "\n" +
                              timestamp + "\n" +
                              credentialScope + "\n" +
                              hashedCanonicalRequest;
        
        // 4. 计算签名
        byte[] secretDate = hmacSHA256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmacSHA256(secretDate, service);
        byte[] secretSigning = hmacSHA256(secretService, "tc3_request");
        String signature = bytesToHex(hmacSHA256(secretSigning, stringToSign));
        
        // 5. 拼接Authorization
        return ALGORITHM + " " +
               "Credential=" + secretId + "/" + credentialScope + ", " +
               "SignedHeaders=" + signedHeaders + ", " +
               "Signature=" + signature;
    }
    
    /**
     * SHA256哈希并转为十六进制
     */
    private String sha256Hex(String data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(SHA256);
        byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hash);
    }
    
    /**
     * HMAC-SHA256加密
     */
    private byte[] hmacSHA256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec secretKeySpec = new SecretKeySpec(key, HMAC_SHA256);
        mac.init(secretKeySpec);
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
