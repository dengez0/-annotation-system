package com.simplelabel.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPathsTest {
    @TempDir
    Path temporary;

    @Test
    void supportsIndependentAbsoluteRuntimeDirectories() {
        Path root = temporary.resolve("application");
        Path data = temporary.resolve("runtime/data");
        Path models = temporary.resolve("runtime/models");
        Path logs = temporary.resolve("runtime/logs");
        Path processed = temporary.resolve("runtime/processed");
        Path staticResources = temporary.resolve("release/static");
        AppPaths paths = new AppPaths(root.toString(), data.toString(), models.toString(),
                logs.toString(), processed.toString(), staticResources.toString());

        assertEquals(data.toAbsolutePath().normalize(), paths.data());
        assertEquals(models.toAbsolutePath().normalize(), paths.models());
        assertEquals(logs.toAbsolutePath().normalize(), paths.logs());
        assertEquals(processed.toAbsolutePath().normalize(), paths.processed());
        assertEquals(staticResources.toAbsolutePath().normalize(), paths.staticResources());
    }

    @Test
    void retainsRootRelativeDefaultsForLocalRuns() {
        AppPaths paths = new AppPaths(temporary.toString());
        assertEquals(temporary.resolve("data").toAbsolutePath().normalize(), paths.data());
        assertEquals(temporary.resolve("static").toAbsolutePath().normalize(), paths.staticResources());
    }
}
