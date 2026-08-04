package com.aiwei.nodus.core.media;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateMediaDownloadRequest(
        @NotBlank @Size(max = 300) String title,
        @NotBlank @Pattern(regexp = "MOVIE|TV") String mediaType,
        @Min(1888) @Max(2200) Integer releaseYear,
        @Min(0) @Max(999) Integer seasonNumber,
        @Min(1) @Max(9999) Integer episodeNumber,
        @Size(max = 300) String episodeTitle,
        @NotBlank @Size(max = 64) String sourceProvider,
        @Size(max = 256) String sourceShareId,
        @NotBlank @Size(max = 4000) String sourceUrl,
        @NotBlank @Size(max = 512) String originalFilename,
        @Min(0) Long expectedSizeBytes) {
}
