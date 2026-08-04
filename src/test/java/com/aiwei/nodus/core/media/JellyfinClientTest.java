package com.aiwei.nodus.core.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class JellyfinClientTest {

    @Test
    void usesMediaTitleWithoutExtensionForJellyfinSearch() {
        assertThat(JellyfinClient.searchTerm(
                Path.of("/media/Movies/Nodus P4 Acceptance (2026).mp4")))
                .isEqualTo("Nodus P4 Acceptance (2026)");
    }

    @Test
    void preservesFilenameWhenThereIsNoExtension() {
        assertThat(JellyfinClient.searchTerm(Path.of("/media/Movies/README")))
                .isEqualTo("README");
    }
}
