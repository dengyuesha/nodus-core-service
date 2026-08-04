package com.aiwei.nodus.core.media;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MediaVerificationScheduler {

    private final MediaService service;

    public MediaVerificationScheduler(MediaService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${nodus.core.media.verification-interval:2s}")
    public void verifyNext() {
        MediaDownloadTask task = service.claimVerification();
        if (task != null) {
            service.process(task);
        }
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void syncJellyfin() {
        service.syncJellyfin();
    }
}
