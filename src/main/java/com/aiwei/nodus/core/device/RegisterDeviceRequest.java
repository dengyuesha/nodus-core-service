package com.aiwei.nodus.core.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterDeviceRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._:@-]{1,128}")
        String deviceId,

        @Pattern(regexp = "[A-Za-z0-9._:@-]{1,128}")
        String householdId,

        @Size(max = 256)
        String displayName) {
}
