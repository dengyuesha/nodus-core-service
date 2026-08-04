package com.aiwei.nodus.core.media;

import java.nio.file.Path;
import java.util.UUID;

record MediaAssetFile(UUID id, Path path, String jellyfinItemId, String jellyfinImageTag) {
}
