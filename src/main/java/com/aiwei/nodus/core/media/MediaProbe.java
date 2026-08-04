package com.aiwei.nodus.core.media;

record MediaProbe(
        String container,
        String videoCodec,
        String audioCodec,
        Long durationSeconds,
        Integer width,
        Integer height) {
}
