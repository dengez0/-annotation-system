package com.simplelabel.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplelabel.config.AppPaths;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

@Service
public class AnnotationService {
    public static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".bmp");
    private static final String LABEL_COLORS_FILE = ".label_colors.json";
    private static final Set<String> LABEL_COLORS = Set.of(
            "#8B4513", "#000080", "#006400", "#FF4444", "#44FF44",
            "#4488FF", "#FFDD44", "#FF44FF", "#44FFFF", "#FF8844");
    private final AppPaths paths;
    private final ObjectMapper mapper;

    public AnnotationService(AppPaths paths, ObjectMapper mapper) {
        this.paths = paths;
        this.mapper = mapper;
    }

    public Path projectPath(String main, String sub) {
        validateComponent(main);
        validateComponent(sub);
        return paths.safeDataPath(main, sub);
    }

    public List<Map<String, Object>> listMainFolders(boolean includeCompleted) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkflowState state : WorkflowState.values()) {
            if (state == WorkflowState.COMPLETED && !includeCompleted) continue;
            result.add(mainFolder(state.directory(), false, state.id()));
        }
        for (Path main : directories(paths.data())) {
            String name = main.getFileName().toString();
            if (name.startsWith(".") || WorkflowState.fromDirectory(name).isPresent()) continue;
            result.add(mainFolder(name, true, "legacy"));
        }
        return result;
    }

    private Map<String, Object> mainFolder(String name, boolean legacy, String state) throws IOException {
            List<Map<String, Object>> subfolders = listSubfolders(name);
            int total = subfolders.stream().mapToInt(item -> ((Number) item.get("count")).intValue()).sum();
            int annotated = subfolders.stream().mapToInt(item -> ((Number) item.get("annotated_count")).intValue()).sum();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("subfolders", subfolders);
            item.put("total_images", total);
            item.put("total_annotated", annotated);
            item.put("sub_count", subfolders.size());
            item.put("legacy", legacy);
            item.put("workflow_state", state);
            return item;
    }

    public List<String> listProjects() throws IOException {
        return directories(paths.data()).stream().map(path -> path.getFileName().toString())
                .filter(name -> !name.startsWith(".")).sorted().toList();
    }

    public List<Map<String, Object>> listSubfolders(String main) throws IOException {
        validateComponent(main);
        Path mainPath = paths.safeDataPath(main);
        if (!Files.isDirectory(mainPath)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Path sub : directories(mainPath)) {
            int count = countImages(sub);
            result.add(Map.of("name", sub.getFileName().toString(), "count", count,
                    "annotated_count", countAnnotatedImages(main, sub)));
        }
        return result;
    }

    public List<Map<String, Object>> listImages(String main, String sub) throws IOException {
        Path project = projectPath(main, sub);
        if (!Files.isDirectory(project)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Path path : files(project)) {
            String name = path.getFileName().toString();
            if (!isImage(name)) continue;
            String stem = stem(name);
            result.add(Map.of("name", name, "processed", Files.exists(annotationPath(main, sub, name))));
        }
        return result;
    }

    public List<String> listLabels(String main, String sub) throws IOException {
        Path project = projectPath(main, sub);
        if (!Files.isDirectory(project)) return List.of();
        Set<String> labels = new TreeSet<>();
        for (Path path : files(annotationDirectory(main, sub))) {
            if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) continue;
            try {
                JsonNode root = mapper.readTree(path.toFile());
                for (JsonNode shape : root.path("shapes")) {
                    String label = shape.path("label").asText("");
                    if (!label.isBlank()) labels.add(label);
                }
            } catch (Exception ignored) { }
        }
        return new ArrayList<>(labels);
    }

    public Map<String, String> getLabelColors(String main, String sub) throws IOException {
        Path file = annotationDirectory(main, sub).resolve(LABEL_COLORS_FILE);
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            Map<String, String> input = mapper.readValue(file.toFile(), new TypeReference<>() {});
            Map<String, String> result = new TreeMap<>();
            input.forEach((label, color) -> {
                if (label != null && color != null && LABEL_COLORS.contains(color.toUpperCase(Locale.ROOT))) {
                    result.put(label, color.toUpperCase(Locale.ROOT));
                }
            });
            return result;
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    public Map<String, String> saveLabelColor(String main, String sub, String label, String color) throws IOException {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("Label is required");
        String normalized = color == null ? "" : color.toUpperCase(Locale.ROOT);
        if (!LABEL_COLORS.contains(normalized)) throw new IllegalArgumentException("Unsupported label color");
        Map<String, String> colors = getLabelColors(main, sub);
        colors.put(label, normalized);
        atomicWrite(annotationDirectory(main, sub).resolve(LABEL_COLORS_FILE), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(colors));
        return colors;
    }

    public void saveAnnotation(String main, String sub, String filename, JsonNode json) throws IOException {
        String safeName = fileName(filename);
        atomicWrite(annotationDirectory(main, sub).resolve(stem(safeName) + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(json));
    }

    public JsonNode readAnnotation(String main, String sub, String filename) throws IOException {
        Path file = annotationPath(main, sub, filename);
        if (!Files.isRegularFile(file)) return null;
        return mapper.readTree(file.toFile());
    }

    public Path annotationPath(String main, String sub, String imageOrJsonName) {
        String safeName = fileName(imageOrJsonName);
        String jsonName = safeName.toLowerCase(Locale.ROOT).endsWith(".json")
                ? safeName : stem(safeName) + ".json";
        return annotationDirectory(main, sub).resolve(jsonName);
    }

    public Path annotationDirectory(String main, String sub) {
        Path project = projectPath(main, sub);
        Optional<WorkflowState> state = WorkflowState.fromDirectory(main);
        if (state.isPresent() && state.get() != WorkflowState.COMPLETED) return project.resolve("label");
        return project;
    }

    public Map<String, Object> createEmptyJsons(String main, String sub) throws IOException {
        Path project = projectPath(main, sub);
        if (!Files.isDirectory(project)) return null;
        int created = 0, skipped = 0, errors = 0;
        for (Path image : files(project)) {
            String name = image.getFileName().toString();
            if (!isImage(name)) continue;
            Path jsonPath = annotationPath(main, sub, name);
            if (Files.exists(jsonPath)) { skipped++; continue; }
            try {
                BufferedImage buffered = ImageIO.read(image.toFile());
                if (buffered == null) { errors++; continue; }
                ObjectNode payload = mapper.createObjectNode();
                payload.put("version", "5.2.1");
                payload.set("flags", mapper.createObjectNode());
                payload.set("shapes", mapper.createArrayNode());
                payload.put("imagePath", name);
                payload.putNull("imageData");
                payload.put("imageHeight", buffered.getHeight());
                payload.put("imageWidth", buffered.getWidth());
                atomicWrite(jsonPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload));
                created++;
            } catch (Exception exception) { errors++; }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success"); result.put("created", created);
        result.put("skipped", skipped); result.put("errors", errors);
        return result;
    }

    public static void atomicWrite(Path destination, byte[] content) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".simplelabel-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    public int countImages(String main, String sub) throws IOException {
        return countImages(projectPath(main, sub));
    }

    public int countAnnotatedImages(String main, String sub) throws IOException {
        return countAnnotatedImages(main, projectPath(main, sub));
    }

    private int countAnnotatedImages(String main, Path folder) throws IOException {
        int annotated = 0;
        for (Path path : files(folder)) {
            String name = path.getFileName().toString();
            if (isImage(name) && Files.isRegularFile(annotationPath(main, folder.getFileName().toString(), name))) annotated++;
        }
        return annotated;
    }

    public static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String fileName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Invalid filename");
        Path path = Path.of(value);
        if (path.getNameCount() != 1 || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Invalid filename");
        }
        return value;
    }

    public static void validateComponent(String value) {
        fileName(value);
    }

    private static int countImages(Path directory) throws IOException {
        return (int) files(directory).stream().filter(path -> isImage(path.getFileName().toString())).count();
    }

    private static List<Path> directories(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }

    private static List<Path> files(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isRegularFile).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }
}
