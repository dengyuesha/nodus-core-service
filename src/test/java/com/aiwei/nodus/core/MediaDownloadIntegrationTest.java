package com.aiwei.nodus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class MediaDownloadIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void createsTracksAndListsBrowserDownload() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "Big Buck Bunny",
                "mediaType", "MOVIE",
                "releaseYear", 2008,
                "sourceProvider", "quark",
                "sourceShareId", "public-domain-fixture",
                "sourceUrl", "https://pan.quark.cn/s/example",
                "originalFilename", "Big Buck Bunny (2008).mp4",
                "expectedSizeBytes", 1024));
        MvcResult created = perform(post("/api/v1/media-downloads"), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DOWNLOADING"))
                .andExpect(jsonPath("$.stagingPath").isNotEmpty())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).path("downloadId").asText();

        perform(post("/api/v1/media-downloads/" + id + "/progress"),
                "{\"downloadedBytes\":512,\"totalBytes\":1024}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadedBytes").value(512));
        perform(get("/api/v1/media-downloads"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Big Buck Bunny"));
        perform(get("/api/v1/media/storage"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumFreeBytes").value(1048576));
    }

    @Test
    void rejectsUnknownShareHostBeforeCreatingTask() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "Unsafe",
                "mediaType", "MOVIE",
                "sourceProvider", "unknown",
                "sourceUrl", "https://example.com/file",
                "originalFilename", "unsafe.mp4"));
        perform(post("/api/v1/media-downloads"), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MEDIA_SHARE_URL_UNSUPPORTED"));
    }

    @Test
    void recordsInterruptedBrowserDownloadAsFailed() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "Interrupted fixture",
                "mediaType", "MOVIE",
                "sourceProvider", "quark",
                "sourceUrl", "https://pan.quark.cn/s/interrupted",
                "originalFilename", "interrupted.mp4"));
        MvcResult created = perform(post("/api/v1/media-downloads"), body)
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).path("downloadId").asText();

        perform(post("/api/v1/media-downloads/" + id + "/failed"),
                "{\"code\":\"MEDIA_DOWNLOAD_INTERRUPTED\",\"message\":\"network interrupted\"}")
                .andExpect(status().isNoContent());
        perform(get("/api/v1/media-downloads"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].failureCode").value("MEDIA_DOWNLOAD_INTERRUPTED"));
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            MockHttpServletRequestBuilder builder, String body) throws Exception {
        builder.header("X-Nodus-Api-Key", "test-api-key")
                .header("X-Tenant-Id", "test")
                .header("X-User-Id", "media-user")
                .header("X-Device-Id", "test-device")
                .header("X-Session-Id", "media-user")
                .header("X-Source-Client", "integration-test");
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(builder);
    }
}
