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
    private static final String MOVE_ORIGINS_FILE = ".move_origins.json";
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

    public Map<String, Object> uploadAnnotation(List<MultipartFile> files, String taskName,
                                                 String uploadId, String filePathsRaw,
                                                 boolean complete) throws IOException {
        String task = safeComponent(taskName);
        String session = safeComponent(uploadId);
        Path sessionRoot = paths.safeDataPath(".uploads", session);
        Path staging = sessionRoot.resolve(task).normalize();
        if (!staging.startsWith(sessionRoot)) throw new IllegalArgumentException("Invalid upload session");
        if (!Files.exists(staging)) {
            for (WorkflowState state : WorkflowState.values()) {
                if (Files.exists(paths.safeDataPath(state.directory(), task))) {
                    throw new ApiException(HttpStatus.CONFLICT, "Task already exists in " + state.directory());
                }
            }
            Files.createDirectories(staging.resolve("label"));
        }
        List<String> relativePaths;
        try { relativePaths = mapper.readValue(filePathsRaw == null ? "[]" : filePathsRaw, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid upload paths"); }
        int count = 0;
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            if (file.isEmpty() || file.getOriginalFilename() == null) continue;
            String relative = index < relativePaths.size() ? normalize(relativePaths.get(index)) : file.getOriginalFilename();
            List<String> components = splitRelative(relative);
            String name = safeComponent(components.get(components.size() - 1));
            Path destination = name.toLowerCase(Locale.ROOT).endsWith(".json")
                    ? staging.resolve("label").resolve(name) : staging.resolve(name);
            if (Files.exists(destination)) throw new ApiException(HttpStatus.CONFLICT, "Duplicate uploaded filename: " + name);
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), ".upload-", ".tmp");
            try { file.transferTo(temporary); Files.move(temporary, destination); }
            finally { Files.deleteIfExists(temporary); }
            count++;
        }
        if (complete) {
            long images;
            try (Stream<Path> stream = Files.list(staging)) {
                images = stream.filter(Files::isRegularFile)
                        .filter(path -> AnnotationService.isImage(path.getFileName().toString())).count();
            }
            if (images == 0) throw new IllegalArgumentException("Uploaded task contains no supported images");
            Path destination = paths.safeDataPath(WorkflowState.UNLABELED.directory(), task);
            if (Files.exists(destination)) throw new ApiException(HttpStatus.CONFLICT, "Task already exists in 未标注");
            moveNewDirectory(staging, destination);
            Files.deleteIfExists(sessionRoot);
        }
        return Map.of("status", "success", "count", count, "task", task,
                "complete", complete, "main_folder", WorkflowState.UNLABELED.directory());
    }

    public void cancelAnnotationUpload(String uploadId) throws IOException {
        deleteDirectory(paths.safeDataPath(".uploads", safeComponent(uploadId)));
    }

    public Map<String, Object> rename(String oldName, String newName, String level, String main) throws IOException {
        oldName = safeComponent(oldName); newName = safeComponent(newName);
        Path oldPath, newPath;
        if ("sub".equals(level)) {
            main = safeComponent(main);
            if (WorkflowState.fromDirectory(main).isEmpty()) throw new ApiException(HttpStatus.FORBIDDEN, "Legacy projects are read-only");
            for (WorkflowState state : WorkflowState.values()) {
                if (!state.directory().equals(main) && Files.exists(paths.safeDataPath(state.directory(), newName))) {
                    throw new ApiException(HttpStatus.CONFLICT, "Task name already exists in " + state.directory());
                }
            }
            oldPath = paths.safeDataPath(main, oldName); newPath = paths.safeDataPath(main, newName);
        } else {
            throw new ApiException(HttpStatus.FORBIDDEN, "Workflow folders cannot be renamed and legacy projects are read-only");
        }
        if (!Files.exists(oldPath)) throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        if (Files.exists(newPath)) throw new ApiException(HttpStatus.BAD_REQUEST, "New name already exists");
        Files.move(oldPath, newPath);
        return Map.of("status", "success");
    }

    public Map<String, Object> deleteProject(String main, String sub, String level) throws IOException {
        if (!"sub".equals(level) || WorkflowState.fromDirectory(main).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Workflow folders cannot be deleted and legacy projects are read-only");
        }
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
                    Files.deleteIfExists(annotations.annotationPath(main, sub, name));
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

    public synchronized Map<String, Object> moveToCompleted(String main, String sub, List<String> filenames) throws IOException {
        WorkflowState state = WorkflowState.fromDirectory(main)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                        "Move is only available in annotating and review tasks"));
        if (state != WorkflowState.ANNOTATING && state != WorkflowState.REVIEW) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Move is only available in annotating and review tasks");
        }
        String safeSub = safeComponent(sub);
        Path source = paths.safeDataPath(main, safeSub);
        if (!Files.isDirectory(source)) throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        Path destination = paths.safeDataPath("moved image", safeSub);
        Files.createDirectories(destination);
        Map<String, String> origins = readMoveOrigins(destination);
        int moved = 0;
        List<Map<String, String>> skipped = new ArrayList<>(), errors = new ArrayList<>();
        for (String raw : filenames) {
            String name;
            try { name = AnnotationService.fileName(raw); }
            catch (Exception exception) { errors.add(issue(raw, "Invalid filename")); continue; }
            Path sourceImage = source.resolve(name);
            Path sourceJson = annotations.annotationPath(main, safeSub, name);
            Path destinationImage = destination.resolve(name);
            Path destinationJson = destination.resolve(AnnotationService.stem(name) + ".json");
            if (!Files.isRegularFile(sourceImage)) { errors.add(issue(name, "Source image not found")); continue; }
            if (Files.exists(destinationImage) || Files.exists(destinationJson)) {
                skipped.add(issue(name, "Destination already contains the image or annotation")); continue;
            }
            try {
                movePair(sourceImage, sourceJson, destinationImage, destinationJson);
                String previousOrigin = origins.put(name, main);
                try {
                    writeMoveOrigins(destination, origins);
                } catch (IOException exception) {
                    restorePair(destinationImage, destinationJson, sourceImage, sourceJson);
                    if (previousOrigin == null) origins.remove(name); else origins.put(name, previousOrigin);
                    errors.add(issue(name, "Move record failed: " + exception.getMessage()));
                    continue;
                }
                moved++;
            } catch (IOException exception) {
                errors.add(issue(name, "Move failed: " + exception.getMessage()));
            }
        }
        Map<String, Object> result = transferResult(moved, skipped, errors);
        result.put("destination", "moved image/" + safeSub);
        return result;
    }

    public synchronized Map<String, Object> restoreFromCompleted(String sub, List<String> filenames) throws IOException {
        String safeSub = safeComponent(sub);
        Path source = paths.safeDataPath("moved image", safeSub);
        if (!Files.isDirectory(source)) throw new ApiException(HttpStatus.NOT_FOUND, "Moved project not found");
        Map<String, String> origins = readMoveOrigins(source);
        int moved = 0;
        Set<String> destinations = new LinkedHashSet<>();
        List<Map<String, String>> skipped = new ArrayList<>(), errors = new ArrayList<>();
        for (String raw : filenames) {
            String name;
            try { name = AnnotationService.fileName(raw); }
            catch (Exception exception) { errors.add(issue(raw, "Invalid filename")); continue; }
            String destinationMain = resolveRestoreMain(origins.get(name), safeSub);
            Path destination = paths.safeDataPath(destinationMain, safeSub);
            Path destinationLabels = destination.resolve("label");
            Path sourceImage = source.resolve(name);
            Path sourceJson = source.resolve(AnnotationService.stem(name) + ".json");
            Path destinationImage = destination.resolve(name);
            Path destinationJson = destinationLabels.resolve(sourceJson.getFileName());
            if (!Files.isRegularFile(sourceImage)) { errors.add(issue(name, "Source image not found")); continue; }
            if (Files.exists(destinationImage) || Files.exists(destinationJson)) {
                skipped.add(issue(name, "Destination already contains the image or annotation")); continue;
            }
            Files.createDirectories(destinationLabels);
            try {
                movePair(sourceImage, sourceJson, destinationImage, destinationJson);
                String previousOrigin = origins.remove(name);
                try {
                    writeMoveOrigins(source, origins);
                } catch (IOException exception) {
                    restorePair(destinationImage, destinationJson, sourceImage, sourceJson);
                    if (previousOrigin != null) origins.put(name, previousOrigin);
                    errors.add(issue(name, "Restore record failed: " + exception.getMessage()));
                    continue;
                }
                moved++;
                destinations.add(destinationMain + "/" + safeSub);
            } catch (IOException exception) {
                errors.add(issue(name, "Restore failed: " + exception.getMessage()));
            }
        }
        Map<String, Object> result = transferResult(moved, skipped, errors);
        result.put("destination", destinations.size() == 1 ? destinations.iterator().next() : "原任务阶段/" + safeSub);
        return result;
    }

    private Map<String, String> readMoveOrigins(Path movedProject) throws IOException {
        Path record = movedProject.resolve(MOVE_ORIGINS_FILE);
        if (!Files.isRegularFile(record)) return new LinkedHashMap<>();
        return new LinkedHashMap<>(mapper.readValue(record.toFile(), new TypeReference<Map<String, String>>() {}));
    }

    private void writeMoveOrigins(Path movedProject, Map<String, String> origins) throws IOException {
        Path record = movedProject.resolve(MOVE_ORIGINS_FILE);
        if (origins.isEmpty()) {
            Files.deleteIfExists(record);
            return;
        }
        AnnotationService.atomicWrite(record,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(origins));
    }

    private String resolveRestoreMain(String recordedMain, String sub) {
        boolean validRecorded = WorkflowState.ANNOTATING.directory().equals(recordedMain)
                || WorkflowState.REVIEW.directory().equals(recordedMain);
        if (validRecorded && Files.isDirectory(paths.safeDataPath(recordedMain, sub))) return recordedMain;
        for (WorkflowState state : List.of(WorkflowState.ANNOTATING, WorkflowState.REVIEW)) {
            if (Files.isDirectory(paths.safeDataPath(state.directory(), sub))) return state.directory();
        }
        return validRecorded ? recordedMain : WorkflowState.ANNOTATING.directory();
    }

    private static void movePair(Path sourceImage, Path sourceJson,
                                 Path destinationImage, Path destinationJson) throws IOException {
        Files.move(sourceImage, destinationImage);
        if (!Files.isRegularFile(sourceJson)) return;
        try {
            Files.move(sourceJson, destinationJson);
        } catch (IOException exception) {
            try { Files.move(destinationImage, sourceImage); } catch (IOException ignored) { }
            throw exception;
        }
    }

    private static void restorePair(Path movedImage, Path movedJson,
                                    Path originalImage, Path originalJson) {
        try {
            if (Files.isRegularFile(movedJson)) {
                Files.createDirectories(originalJson.getParent());
                Files.move(movedJson, originalJson);
            }
            if (Files.isRegularFile(movedImage)) {
                Files.createDirectories(originalImage.getParent());
                Files.move(movedImage, originalImage);
            }
        } catch (IOException ignored) { }
    }

    private static Map<String, Object> transferResult(int moved, List<Map<String, String>> skipped,
                                                       List<Map<String, String>> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("moved", moved);
        result.put("skipped", skipped);
        result.put("errors", errors);
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
                Path json = annotations.annotationPath(main, sub, name);
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
                    Path jsonPath = annotations.annotationPath(main, sub, imageName);
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
