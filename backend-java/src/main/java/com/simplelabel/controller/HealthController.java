package com.simplelabel.controller;

import com.simplelabel.config.AppPaths;
import com.simplelabel.worker.YoloWorkerClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {
    private final AppPaths paths;
    private final YoloWorkerClient worker;
    private final Clock clock;

    public HealthController(AppPaths paths, YoloWorkerClient worker, Clock clock) {
        this.paths = paths;
        this.worker = worker;
        this.clock = clock;
    }

    @GetMapping("/internal/health")
    ResponseEntity<Map<String, Object>> health() {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("data", usable(paths.data()));
        checks.put("models", usable(paths.models()));
        checks.put("logs", usable(paths.logs()));
        checks.put("processed", usable(paths.processed()));
        checks.put("static", Files.isDirectory(paths.staticResources()) && Files.isReadable(paths.staticResources()));
        checks.put("worker", worker.healthy());
        boolean healthy = checks.values().stream().allMatch(Boolean::booleanValue);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", healthy ? "ok" : "degraded");
        response.put("service", "simplelabel-java");
        response.put("version", getClass().getPackage().getImplementationVersion() == null
                ? "development" : getClass().getPackage().getImplementationVersion());
        response.put("time_zone", clock.getZone().getId());
        response.put("current_time", OffsetDateTime.now(clock).toString());
        response.put("checks", checks);
        return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    private static boolean usable(Path path) {
        return Files.isDirectory(path) && Files.isReadable(path) && Files.isWritable(path);
    }
}
