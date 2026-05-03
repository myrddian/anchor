package io.aeyer.anchor.client.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.anchor.client.exceptions.AnchorClientException;
import java.io.IOException;
import java.time.Duration;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Thin OkHttp + Jackson wrapper for SDK calls. Keeps base URL, default
 * timeouts, and (de)serialisation in one place so the public client surface
 * stays focused on document semantics.
 */
public final class HttpTransport {

    private static final MediaType JSON = MediaType.get("application/json");

    private final String baseUrl;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public HttpTransport(String baseUrl, Duration timeout, ObjectMapper mapper) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.mapper = mapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .writeTimeout(timeout)
                .readTimeout(timeout)
                .retryOnConnectionFailure(true)
                .build();
    }

    public OkHttpClient httpClient() { return http; }

    public ObjectMapper mapper() { return mapper; }

    public String baseUrl() { return baseUrl; }

    public <T> T get(String path, Class<T> type) {
        return parse(execute(new Request.Builder().url(baseUrl + path).get().build(), path), type);
    }

    public <T> T get(String path, TypeReference<T> type) {
        return parse(execute(new Request.Builder().url(baseUrl + path).get().build(), path), type);
    }

    public <T> T postJson(String path, Object body, Class<T> type) {
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(toJson(body), JSON))
                .build();
        return parse(execute(request, path), type);
    }

    public void delete(String path) {
        Request request = new Request.Builder().url(baseUrl + path).delete().build();
        execute(request, path);
    }

    private String execute(Request request, String path) {
        try (Response response = http.newCall(request).execute()) {
            String body = bodyOrEmpty(response);
            if (!response.isSuccessful()) {
                throw new AnchorClientException(
                        "Anchor server " + path + " failed: HTTP " + response.code() + " " + body);
            }
            return body;
        } catch (IOException e) {
            throw new AnchorClientException("Anchor server " + path + " I/O error: " + e.getMessage(), e);
        }
    }

    private String bodyOrEmpty(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    private <T> T parse(String body, Class<T> type) {
        if (body == null || body.isEmpty()) return null;
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new AnchorClientException("Could not parse response as " + type.getSimpleName(), e);
        }
    }

    private <T> T parse(String body, TypeReference<T> type) {
        if (body == null || body.isEmpty()) return null;
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new AnchorClientException("Could not parse response", e);
        }
    }

    public JsonNode parseTree(String body) {
        try { return mapper.readTree(body); }
        catch (IOException e) { throw new AnchorClientException("Bad JSON", e); }
    }

    private byte[] toJson(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (IOException e) { throw new AnchorClientException("Could not serialise request body", e); }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
