package com.bss.document.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The S3-PROTOCOL store: AWS S3, MinIO, Cloudflare R2, GCS-interop — one
 * adapter, because they all speak the same wire (SigV4, path-style).
 * No SDK: ~a hundred lines of plain HTTP and HMAC, the house style of
 * every other adapter. Keys are tenant-prefixed; the bucket is ensured
 * on first use. Azure speaks its own dialect — see AzureBlobContentStore.
 */
@Component
@ConditionalOnProperty(name = "bss.content.store", havingValue = "s3")
public class S3ContentStore implements ContentStore {

    private static final Logger log = LoggerFactory.getLogger(S3ContentStore.class);
    private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final HttpClient http = HttpClient.newHttpClient();
    private final String endpoint;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private volatile boolean bucketEnsured;

    public S3ContentStore(
            @Value("${bss.content.s3.endpoint}") String endpoint,
            @Value("${bss.content.s3.bucket:bss-content}") String bucket,
            @Value("${bss.content.s3.region:us-east-1}") String region,
            @Value("${bss.content.s3.access-key}") String accessKey,
            @Value("${bss.content.s3.secret-key}") String secretKey) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.bucket = bucket;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        log.info("content store: S3 protocol at {} bucket {}", endpoint, bucket);
    }

    @Override
    public String put(String tenantId, String documentId, String contentType, byte[] bytes) {
        ensureBucket();
        String key = tenantId + "/" + documentId;
        int status = send("PUT", "/" + bucket + "/" + key, bytes, contentType);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("S3 put failed: HTTP " + status);
        }
        return "s3:" + key;
    }

    @Override
    public byte[] get(String tenantId, String storageKey) {
        String key = storageKey.startsWith("s3:") ? storageKey.substring(3) : storageKey;
        if (!key.startsWith(tenantId + "/")) {
            throw new IllegalStateException("storage key outside the tenant's prefix");
        }
        HttpResponse<byte[]> response = sendForBody("GET", "/" + bucket + "/" + key);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("S3 get failed: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private void ensureBucket() {
        if (bucketEnsured) {
            return;
        }
        int status = send("PUT", "/" + bucket, new byte[0], null);
        if (status == 200 || status == 409) { // created, or already ours
            bucketEnsured = true;
        } else {
            throw new IllegalStateException("S3 bucket ensure failed: HTTP " + status);
        }
    }

    private int send(String method, String path, byte[] body, String contentType) {
        try {
            return request(method, path, body, contentType).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("S3 " + method + " " + path + ": " + e.getMessage(), e);
        }
    }

    private HttpResponse<byte[]> sendForBody(String method, String path) {
        try {
            return request(method, path, new byte[0], null);
        } catch (Exception e) {
            throw new IllegalStateException("S3 " + method + " " + path + ": " + e.getMessage(), e);
        }
    }

    /** AWS Signature V4, the whole ceremony, no SDK. */
    private HttpResponse<byte[]> request(String method, String path, byte[] body, String contentType)
            throws Exception {
        URI uri = URI.create(endpoint + path);
        String host = uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String amzDate = AMZ.format(now);
        String date = amzDate.substring(0, 8);
        String payloadHash = hex(sha256(body));

        String canonicalHeaders = "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = method + "\n" + path + "\n\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + payloadHash;
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + hex(sha256(canonicalRequest.getBytes()));
        byte[] signingKey = hmac(hmac(hmac(hmac(("AWS4" + secretKey).getBytes(), date),
                region), "s3"), "aws4_request");
        String signature = hex(hmac(signingKey, stringToSign));

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                        + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes());
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
