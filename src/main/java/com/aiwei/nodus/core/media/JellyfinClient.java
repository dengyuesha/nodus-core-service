package com.aiwei.nodus.core.media;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class JellyfinClient {

    private final MediaProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public JellyfinClient(MediaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(properties.jellyfinTimeout()).build();
    }

    public boolean configured() {
        return !properties.jellyfinApiKey().isBlank();
    }

    public boolean refreshLibrary() {
        if (!configured()) {
            return false;
        }
        try {
            HttpRequest request = request("/Library/Refresh").POST(HttpRequest.BodyPublishers.noBody()).build();
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() / 100 == 2;
        } catch (Exception error) {
            return false;
        }
    }

    public Optional<JellyfinItem> findByPath(Path path) {
        if (!configured()) {
            return Optional.empty();
        }
        try {
            String query = "/Items?Recursive=true&Fields=Path&Limit=50&SearchTerm="
                    + URLEncoder.encode(searchTerm(path), StandardCharsets.UTF_8);
            HttpResponse<String> response = httpClient.send(request(query).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            for (JsonNode item : objectMapper.readTree(response.body()).path("Items")) {
                if (path.toString().equals(item.path("Path").asText())) {
                    return Optional.of(new JellyfinItem(
                            item.path("Id").asText(), item.path("ImageTags").path("Primary").asText(null)));
                }
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    static String searchTerm(Path path) {
        String filename = path.getFileName().toString();
        int extension = filename.lastIndexOf('.');
        return extension > 0 ? filename.substring(0, extension) : filename;
    }

    public Optional<ImagePayload> poster(String itemId) {
        if (!configured() || itemId == null || itemId.isBlank()) {
            return Optional.empty();
        }
        try {
            HttpResponse<byte[]> response = httpClient.send(
                    request("/Items/" + URLEncoder.encode(itemId, StandardCharsets.UTF_8) + "/Images/Primary")
                            .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            return Optional.of(new ImagePayload(
                    response.headers().firstValue("Content-Type").orElse("image/jpeg"), response.body()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private HttpRequest.Builder request(String pathname) {
        return HttpRequest.newBuilder(URI.create(properties.jellyfinBaseUrl() + pathname))
                .timeout(properties.jellyfinTimeout())
                .header("Accept", "application/json")
                .header("X-Emby-Token", properties.jellyfinApiKey());
    }

    public record JellyfinItem(String id, String primaryImageTag) {
    }

    public record ImagePayload(String contentType, byte[] data) {
    }
}
