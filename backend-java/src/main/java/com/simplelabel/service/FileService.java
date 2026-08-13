package com.simplelabel.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplelabel.config.ApiException;
import com.simplelabel.config.AppPaths;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class FileService {
    private final AppPaths paths;
    private final ObjectMapper mapper;
    private final AnnotationService annotations;

    public FileService(AppPaths paths, ObjectMapper mapper, AnnotationService annotations) {
        this.paths = paths;
        this.mapper = mapper;
        this.annotations = annotations;
    }

    public Map<String, Object> upload(List<MultipartFile> files, String main, String sub,
                                      String filePathsRaw, String manifestRaw) throws IOException {
        List<String> relativePaths;
        if (manifestRaw != null && !manifestRaw.isBlank()) {
            Map<String, Object> manifest;
            try {
                byte[] decoded = Base64.getDecoder().decode(manifestRaw);
                manifest = mapper.readValue(new String(decoded, StandardCharsets.UTF_8), new TypeReference<>() {});
            } catch (Exception exception) {
                throw new IllegalArgumentException("Invalid UTF-8 upload manifest");
            }
            main = normalize(String.valueOf(manifest.getOrDefault("main_folder", "")));
            sub = normalize(String.valueOf(manifest.getOrDefault("subfolder", "")));
            Object rawPaths = manifest.get("paths");
            if (!(rawPaths instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) {
                throw new IllegalArgumentException("Invalid upload paths");
            }
            relativePaths = list.stream().map(String.class::cast).map(FileService::normalize).toList();
            if (relativePaths.size() != files.size()) {
                throw new IllegalArgumentException("Upload manifest does not match the file batch");
            }
        } else {
            try {
                relativePaths = mapper.readValue(filePathsRaw == null ? "[]" : filePathsRaw, new TypeReference<>() {});
            } catch (Exception ignored) {
                relativePaths = List.of();
            }
        }
        main = safeComponent(main == null || main.isBlank() ? "New_Project" : main);
        sub = safeComponent(sub == null || sub.isBlank() ? "default" : sub);
        Path target = paths.safeDataPath(main, sub);
        Files.createDirectories(target);
        int count = 0;
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            if (file.isEmpty() || file.getOriginalFilename() == null) continue;
            String relative = index < relativePaths.size() ? relativePaths.get(index) : file.getOriginalFilename();
            List<String> components = splitRelative(relative);
            if (components.size() > 1) components = components.subList(1, components.size());
            if (components.isEmpty()) components = List.of(safeComponent(file.getOriginalFilename()));
            Path destination = target;
            for (String component : components) destination = destination.resolve(safeComponent(component));
            destination = destination.normalize();
            if (!destination.startsWith(target)) throw new IllegalArgumentException("Invalid relative upload path");
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), ".upload-", ".tmp");
            try {
                file.transferTo(temporary);
                moveReplacing(temporary, destination);
            } finally {
                Files.deleteIfExists(temporary);
            }
            count++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success"); result.put("count", count);
        result.put("main_folder", main); result.put("subfolder", sub);
        return result;
    }

    public Map<String, Object> rename(String oldName, String newName, String level, String main) throws IOException {
        oldName = safeComponent(oldName); newName = safeComponent(newName);
        Path oldPath, newPath;
        if ("sub".equals(level)) {
            main = safeComponent(main);
            oldPath = paths.safeDataPath(main, oldName); newPath = paths.safeDataPath(main, newName);
        } else {
            oldPath = paths.safeDataPath(oldName); newPath = paths.safeDataPath(newName);
        }
        if (!Files.exists(oldPath)) throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        if (Files.exists(newPath)) throw new ApiException(HttpStatus.BAD_REQUEST, "New name already exists");
        Files.move(oldPath, newPath);
        return Map.of("status", "success");
    }

    public Map<String, Object> deleteProject(String main, String sub, String level) throws IOException {
        Path target = "sub".equals(level)
                ? paths.safeDataPath(safeComponent(main), safeComponent(sub))
                : paths.safeDataPath(safeComponent(main));
        if (!Files.exists(target)) throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        try (Stream<Path> walk = Files.walk(target)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
        return Map.of("status", "success");
    }

    public Map<String, Object> deleteFiles(String main, String sub, List<String> filenames) throws IOException {
        Path project = annotations.projectPath(main, sub);
        if (!Files.isDirectory(project)) throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        int deleted = 0;
        List<String> errors = new ArrayList<>();
        for (String raw : filenames) {
            String name;
            try { name = AnnotationService.fileName(raw); } catch (Exception exception) { errors.add(raw); continue; }
            Path image = project.resolve(name);
            try {
                if (Files.deleteIfExists(image)) {
                    Files.deleteIfExists(project.resolve(AnnotationService.stem(name) + ".json"));
                    deleted++;
                }
            } catch (IOException exception) { errors.add(name); }
        }
        return result("deleted", deleted, errors);
    }

    public Map<String, Object> moveFiles(String main, String sub, List<String> filenames,
                                         String destinationFolder, String destinationMain,
                                         String destinationSub) throws IOException {
        Path source = annotations.projectPath(main, sub);
        if (!Files.isDirectory(source)) throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        Path destination;
        if (notBlank(destinationMain) && notBlank(destinationSub)) {
            destination = paths.safeDataPath(safeComponent(destinationMain), safeComponent(destinationSub));
        } else if (notBlank(destinationFolder)) {
            destination = Path.of(destinationFolder).toAbsolutePath().normalize();
            if (!destination.startsWith(paths.data())) throw new IllegalArgumentException("Destination must be inside data directory");
        } else throw new IllegalArgumentException("No destination folder specified");
        Files.createDirectories(destination);
        return movePairs(source, destination, filenames, false);
    }

    public Map<String, Object> moveToCompleted(String main, String sub, List<String> filenames) throws IOException {
        if (!"annotation flies".equals(main)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Move is only available from annotation flies");
        }
        String safeSub = safeComponent(sub);
        Path source = paths.safeDataPath("annotation flies", safeSub);
        if (!Files.isDirectory(source)) throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        Path destination = paths.safeDataPath("moved image", safeSub);
        Files.createDirectories(destination);
        Map<String, Object> result = movePairs(source, destination, filenames, true);
        result.put("destination", "moved image/" + safeSub);
        return result;
    }

    public Map<String, Object> restoreFromCompleted(String sub, List<String> filenames) throws IOException {
        String safeSub = safeComponent(sub);
        Path source = paths.safeDataPath("moved image", safeSub);
        if (!Files.isDirectory(source)) throw new ApiException(HttpStatus.NOT_FOUND, "Moved project not found");
        Path destination = paths.safeDataPath("annotation flies", safeSub);
        Files.createDirectories(destination);
        Map<String, Object> result = movePairs(source, destination, filenames, true);
        result.put("destination", "annotation flies/" + safeSub);
        return result;
    }

    public Map<String, Object> copyToPaste(String main, String sub, List<String> filenames) throws IOException {
        Path source = annotations.projectPath(main, sub);
        if (!Files.isDirectory(source)) throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        Path destination = paths.safeDataPath("paste image");
        Files.createDirectories(destination);
        int copied = 0;
        List<String> errors = new ArrayList<>();
        for (String raw : filenames) {
            try {
                String name = AnnotationService.fileName(raw);
                Path image = source.resolve(name);
                if (!Files.isRegularFile(image)) continue;
                Files.copy(image, destination.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                Path json = source.resolve(AnnotationService.stem(name) + ".json");
                if (Files.isRegularFile(json)) Files.copy(json, destination.resolve(json.getFileName()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            } catch (Exception exception) { errors.add(raw); }
        }
        return result("copied", copied, errors);
    }

    public Map<String, Object> exportYolo(String main, String sub, List<String> labels) throws IOException {
        Path project = annotations.projectPath(main, sub);
        if (!Files.isDirectory(project)) throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        validateYoloLabelOrder(labels, annotations.listLabels(main, sub));
        Path labelsRoot = paths.root().resolve("labels");
        Files.createDirectories(labelsRoot);
        Path labelsDir = AppPaths.safeResolve(labelsRoot, sub + "_labels");
        if (Files.exists(labelsDir)) {
            throw new ApiException(HttpStatus.CONFLICT, "Export destination already exists: labels/" + sub + "_labels");
        }
        Path stagingDir = Files.createTempDirectory(labelsRoot, "." + sub + "_labels-");
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (int i = 0; i < labels.size(); i++) ids.put(labels.get(i), i);
        int exported = 0, skipped = 0, errors = 0;
        boolean completed = false;
        try {
            try (Stream<Path> stream = Files.list(project)) {
                for (Path image : stream.filter(Files::isRegularFile).toList()) {
                    String imageName = image.getFileName().toString();
                    if (!AnnotationService.isImage(imageName)) continue;
                    Path jsonPath = project.resolve(AnnotationService.stem(imageName) + ".json");
                    if (!Files.isRegularFile(jsonPath)) { skipped++; continue; }
                    try {
                        JsonNode json = mapper.readTree(jsonPath.toFile());
                        double width = json.path("imageWidth").asDouble(0), height = json.path("imageHeight").asDouble(0);
                        if (width <= 0 || height <= 0) {
                            BufferedImage buffered = ImageIO.read(image.toFile());
                            if (buffered == null) throw new IOException("Unreadable image");
                            width = buffered.getWidth(); height = buffered.getHeight();
                        }
                        List<String> lines = new ArrayList<>();
                        for (JsonNode shape : json.path("shapes")) {
                            Integer id = ids.get(shape.path("label").asText());
                            JsonNode points = shape.path("points");
                            if (id == null || !points.isArray() || points.size() < 2) continue;
                            double x1 = Double.POSITIVE_INFINITY, y1 = Double.POSITIVE_INFINITY;
                            double x2 = Double.NEGATIVE_INFINITY, y2 = Double.NEGATIVE_INFINITY;
                            for (JsonNode point : points) {
                                if (!point.isArray() || point.size() < 2) continue;
                                x1 = Math.min(x1, point.get(0).asDouble()); y1 = Math.min(y1, point.get(1).asDouble());
                                x2 = Math.max(x2, point.get(0).asDouble()); y2 = Math.max(y2, point.get(1).asDouble());
                            }
                            lines.add(id + " " + clamp(((x1 + x2) / 2) / width) + " " + clamp(((y1 + y2) / 2) / height)
                                    + " " + clamp((x2 - x1) / width) + " " + clamp((y2 - y1) / height));
                        }
                        Files.writeString(stagingDir.resolve(AnnotationService.stem(imageName) + ".txt"),
                                String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8);
                        exported++;
                    } catch (Exception exception) { errors++; }
                }
            }
            Files.writeString(stagingDir.resolve("classes.txt"), String.join("\n", labels) + "\n", StandardCharsets.UTF_8);
            moveNewDirectory(stagingDir, labelsDir);
            completed = true;
        } catch (FileAlreadyExistsException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Export destination already exists: labels/" + sub + "_labels");
        } finally {
            if (!completed) deleteDirectory(stagingDir);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success"); result.put("exported", exported); result.put("skipped", skipped);
        result.put("errors", errors); result.put("labels_dir", "labels/" + sub + "_labels"); result.put("class_count", labels.size());
        return result;
    }

    private static void moveNewDirectory(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, destination); }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void validateYoloLabelOrder(List<String> labels, List<String> projectLabels) {
        if (labels == null || labels.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No labels selected");
        }
        if (new LinkedHashSet<>(labels).size() != labels.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Duplicate labels are not allowed");
        }
        if (labels.size() != projectLabels.size() || !new HashSet<>(labels).equals(new HashSet<>(projectLabels))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Label list is incomplete or out of date. Reload and select every project label.");
        }
    }

    public void writeZip(String main, String sub, OutputStream output) throws IOException {
        Path project = annotations.projectPath(main, sub);
        if (!Files.isDirectory(project)) throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8);
             Stream<Path> walk = Files.walk(project)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(project.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zip); zip.closeEntry();
            }
        }
    }

    private Map<String, Object> movePairs(Path source, Path destination, List<String> filenames, boolean skipConflicts) throws IOException {
        int moved = 0;
        List<Map<String, String>> skipped = new ArrayList<>(), errors = new ArrayList<>();
        for (String raw : filenames) {
            String name;
            try { name = AnnotationService.fileName(raw); }
            catch (Exception exception) { errors.add(issue(raw, "Invalid filename")); continue; }
            Path image = source.resolve(name), json = source.resolve(AnnotationService.stem(name) + ".json");
            Path destinationImage = destination.resolve(name), destinationJson = destination.resolve(json.getFileName());
            if (!Files.isRegularFile(image)) { errors.add(issue(name, "Source image not found")); continue; }
            if (skipConflicts && (Files.exists(destinationImage) || Files.exists(destinationJson))) {
                skipped.add(issue(name, "Destination already contains the image or annotation")); continue;
            }
            try {
                if (skipConflicts) Files.move(image, destinationImage); else Files.move(image, destinationImage, StandardCopyOption.REPLACE_EXISTING);
                if (Files.isRegularFile(json)) {
                    try {
                        if (skipConflicts) Files.move(json, destinationJson); else Files.move(json, destinationJson, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException exception) {
                        try { Files.move(destinationImage, image); } catch (IOException ignored) { }
                        errors.add(issue(name, "Annotation move failed: " + exception.getMessage())); continue;
                    }
                }
                moved++;
            } catch (IOException exception) { errors.add(issue(name, "Image move failed: " + exception.getMessage())); }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success"); result.put("moved", moved);
        result.put("skipped", skipped); result.put("errors", errors);
        return result;
    }

    private static Map<String, Object> result(String field, int count, List<String> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success"); result.put(field, count); result.put("errors", errors);
        return result;
    }

    private static Map<String, String> issue(String name, String reason) { return Map.of("name", name, "reason", reason); }
    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFC); }
    private static String safeComponent(String value) { String normalized = normalize(value); AnnotationService.validateComponent(normalized); return normalized; }
    private static List<String> splitRelative(String value) {
        String normalized = normalize(value == null ? "" : value).replace('\\', '/');
        List<String> parts = Arrays.stream(normalized.split("/", -1)).toList();
        if (parts.isEmpty() || parts.stream().anyMatch(item -> item.isBlank() || item.equals(".") || item.equals(".."))) {
            throw new IllegalArgumentException("Invalid relative upload path");
        }
        return parts;
    }
    private static void moveReplacing(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING); }
    }
}
