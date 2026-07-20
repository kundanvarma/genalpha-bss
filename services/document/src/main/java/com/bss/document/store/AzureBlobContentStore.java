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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * The AZURE dialect: Blob Storage speaks its own wire (SharedKey, not
 * SigV4), so an AKS deployment stores content in real Azure Blob instead
 * of pretending to be S3. Same seam, one more adapter — the
 * vendor-neutrality claim is only true if Azure is a first-class citizen.
 * Dev/CI stand-in: Azurite, Microsoft's own emulator.
 */
@Component
@ConditionalOnProperty(name = "bss.content.store", havingValue = "azure-blob")
public class AzureBlobContentStore implements ContentStore {

    private static final Logger log = LoggerFactory.getLogger(AzureBlobContentStore.class);
    private static final DateTimeFormatter RFC1123 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.ENGLISH);
    private static final String API_VERSION = "2021-08-06";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String endpoint;
    private final String account;
    private final byte[] accountKey;
    private final String container;
    private volatile boolean containerEnsured;

    public AzureBlobContentStore(
            @Value("${bss.content.azure.endpoint}") String endpoint,
            @Value("${bss.content.azure.account}") String account,
            @Value("${bss.content.azure.key}") String key,
            @Value("${bss.content.azure.container:bss-content}") String container) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.account = account;
        this.accountKey = Base64.getDecoder().decode(key);
        this.container = container;
        log.info("content store: Azure Blob at {} container {}", endpoint, container);
    }

    @Override
    public String put(String tenantId, String documentId, String contentType, byte[] bytes) {
        ensureContainer();
        String blob = tenantId + "/" + documentId;
        int status = send("PUT", "/" + container + "/" + blob, "", bytes, contentType, true);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Azure put failed: HTTP " + status);
        }
        return "azure:" + blob;
    }

    @Override
    public byte[] get(String tenantId, String storageKey) {
        String blob = storageKey.startsWith("azure:") ? storageKey.substring(6) : storageKey;
        if (!blob.startsWith(tenantId + "/")) {
            throw new IllegalStateException("storage key outside the tenant's prefix");
        }
        HttpResponse<byte[]> response = requestSafe("GET", "/" + container + "/" + blob, "",
                new byte[0], null, false);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Azure get failed: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private void ensureContainer() {
        if (containerEnsured) {
            return;
        }
        int status = send("PUT", "/" + container, "restype:container", new byte[0], null, false);
        if (status == 201 || status == 409) { // created, or already there
            containerEnsured = true;
        } else {
            throw new IllegalStateException("Azure container ensure failed: HTTP " + status);
        }
    }

    private int send(String method, String path, String query, byte[] body, String contentType,
            boolean blockBlob) {
        return requestSafe(method, path, query, body, contentType, blockBlob).statusCode();
    }

    private HttpResponse<byte[]> requestSafe(String method, String path, String canonicalQuery,
            byte[] body, String contentType, boolean blockBlob) {
        try {
            return request(method, path, canonicalQuery, body, contentType, blockBlob);
        } catch (Exception e) {
            throw new IllegalStateException("Azure " + method + " " + path + ": " + e.getMessage(), e);
        }
    }

    /** Azure SharedKey signing — the Blob dialect's whole ceremony. */
    private HttpResponse<byte[]> request(String method, String path, String canonicalQuery,
            byte[] body, String contentType, boolean blockBlob) throws Exception {
        String date = RFC1123.format(ZonedDateTime.now(ZoneOffset.UTC));
        StringBuilder canonicalHeaders = new StringBuilder();
        if (blockBlob) {
            canonicalHeaders.append("x-ms-blob-type:BlockBlob\n");
        }
        canonicalHeaders.append("x-ms-date:").append(date).append("\n")
                .append("x-ms-version:").append(API_VERSION).append("\n");
        // canonical resource: /account + the FULL request path (Azurite is
        // path-style, so the account appears twice — by the spec, not by
        // accident) + any query as \nname:value
        String fullPath = URI.create(endpoint + path).getPath();
        String canonicalResource = "/" + account + fullPath
                + (canonicalQuery.isEmpty() ? "" : "\n" + canonicalQuery);
        String contentLength = body.length == 0 ? "" : String.valueOf(body.length);
        String stringToSign = method + "\n\n\n" + contentLength + "\n\n"
                + (contentType == null ? "" : contentType) + "\n\n\n\n\n\n\n"
                + canonicalHeaders + canonicalResource;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(accountKey, "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(
                mac.doFinal(stringToSign.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        String url = endpoint + path + (canonicalQuery.isEmpty() ? ""
                : "?" + canonicalQuery.replace(':', '='));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                .header("x-ms-date", date)
                .header("x-ms-version", API_VERSION)
                .header("Authorization", "SharedKey " + account + ":" + signature);
        if (blockBlob) {
            builder.header("x-ms-blob-type", "BlockBlob");
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
