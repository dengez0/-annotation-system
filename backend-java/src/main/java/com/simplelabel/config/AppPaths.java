package com.simplelabel.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class AppPaths {
    private final Path root;
    private final Path data;
    private final Path models;
    private final Path logs;
    private final Path admin;
    private final Path processed;
    private final Path staticResources;

    @Autowired
    public AppPaths(@Value("${simplelabel.root}") String root,
                    @Value("${simplelabel.data-dir}") String data,
                    @Value("${simplelabel.models-dir}") String models,
                    @Value("${simplelabel.logs-dir}") String logs,
                    @Value("${simplelabel.admin-dir}") String admin,
                    @Value("${simplelabel.processed-dir}") String processed,
                    @Value("${simplelabel.static-dir}") String staticResources) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.data = configuredPath(data);
        this.models = configuredPath(models);
        this.logs = configuredPath(logs);
        this.admin = configuredPath(admin);
        this.processed = configuredPath(processed);
        this.staticResources = configuredPath(staticResources);
    }

    public AppPaths(String root) {
        this(root, "data", "models", "logs", "admin", "processed", "static");
    }

    @PostConstruct
    void createRuntimeDirectories() throws IOException {
        Files.createDirectories(data);
        Files.createDirectories(models);
        Files.createDirectories(logs);
        Files.createDirectories(admin);
        Files.createDirectories(processed);
    }

    public Path root() { return root; }
    public Path data() { return data; }
    public Path models() { return models; }
    public Path logs() { return logs; }
    public Path admin() { return admin; }
    public Path processed() { return processed; }
    public Path staticResources() { return staticResources; }

    public Path safeDataPath(String... components) {
        return safeResolve(data, components);
    }

    public Path safeModelPath(String name) {
        return safeResolve(models, name);
    }

    private Path configuredPath(String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
    }

    public static Path safeResolve(Path root, String... components) {
        Path result = root;
        for (String component : components) {
            if (component == null || component.isBlank() || component.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid path component");
            }
            result = result.resolve(component);
        }
        result = result.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!result.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Path escapes configured root");
        }
        return result;
    }
}
