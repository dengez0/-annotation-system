package com.simplelabel.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class YoloWorkerClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String token;

    public YoloWorkerClient(ObjectMapper mapper,
                            @Value("${simplelabel.yolo-worker-url}") String baseUrl,
                            @Value("${simplelabel.yolo-worker-token}") String token) {
        this.mapper = mapper; this.baseUrl = baseUrl.replaceAll("/$", ""); this.token = token;
    }

    public JsonNode infer(String modelName, String backend, String customRepo,
                          Path image, double confidence) throws IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model_name", modelName); payload.put("backend", backend);
        payload.put("custom_repo", customRepo); payload.put("image_path", image.toAbsolutePath().toString());
        payload.put("confidence", confidence);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/yolo/infer"))
                .timeout(Duration.ofMinutes(15))
                .header("Content-Type", "application/json")
                .header("X-SimpleLabel-Worker-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = mapper.readTree(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(body.path("error").asText("YOLO worker returned HTTP " + response.statusCode()));
        }
        return body;
    }

    public boolean healthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception ignored) { return false; }
    }
}
