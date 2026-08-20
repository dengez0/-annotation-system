package com.simplelabel.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplelabel.config.ApiException;
import com.simplelabel.config.AppPaths;
import com.simplelabel.task.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class DatasetProcessingService {
    private static final String MANAGED_SOURCE_MAIN = "已完成";
    private static final Set<String> OPERATION_TYPES = Set.of(
            "image_repair", "json_labels", "mask_blackout", "file_rename", "yolo_export");
    private static final DateTimeFormatter RESULT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());
    private static final long SPACE_MARGIN = 1024L * 1024L * 1024L;

    private final AppPaths paths;
    private final AnnotationService annotations;
    private final TaskService tasks;
    private final ObjectMapper mapper;
    private final WorkLogService workLog;

    public DatasetProcessingService(AppPaths paths, AnnotationService annotations, TaskService tasks,
                                    ObjectMapper mapper, WorkLogService workLog) {
        this.paths = paths;
        this.annotations = annotations;
        this.tasks = tasks;
        this.mapper = mapper;
        this.workLog = workLog;
        ImageIO.setUseCache(false);
    }

    public List<Map<String, Object>> projects() throws IOException {
        return annotations.listMainFolders(true).stream()
                .filter(item -> MANAGED_SOURCE_MAIN.equals(item.get("name"))).toList();
    }

    public Map<String, Object> inspect(String main, String sub) throws IOException {
        Path source = requireProject(main, sub);
        Scan scan = scan(source);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("main", main);
        result.put("sub", sub);
        result.put("files", scan.files().size());
        result.put("images", scan.images().size());
        result.put("json_files", scan.jsonFiles().size());
        result.put("bytes", scan.bytes());
        result.put("estimated_required_bytes", requiredSpace(scan.bytes()));
        result.put("available_bytes", Files.getFileStore(paths.processed()).getUsableSpace());
        result.put("labels", labelsFor(source));
        result.put("sample_images", scan.images().stream().limit(8)
                .map(path -> source.relativize(path).toString().replace('\\', '/')).toList());
        return result;
    }

    public List<String> labels(String main, String sub) throws IOException {
        return labelsFor(requireProject(main, sub));
    }

    /** Compatibility entry point for the former repair + mask + YOLO pipeline. */
    public String start(String main, String sub, List<String> order) throws IOException {
        List<Map<String, Object>> operations = List.of(
                Map.of("type", "image_repair"),
                Map.of("type", "mask_blackout"),
                Map.of("type", "yolo_export", "labels", order == null ? List.of() : order));
        return start(main, sub, operations, "local");
    }

    public String start(String main, String sub, List<Map<String, Object>> requested, String clientIp) throws IOException {
        Path source = requireProject(main, sub);
        List<Map<String, Object>> operations = normalizeOperations(requested);
        Scan scan = scan(source);
        long required = requiredSpace(scan.bytes());
        FileStore store = Files.getFileStore(paths.processed());
        if (store.getUsableSpace() < required) {
            throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE,
                    "Insufficient disk space: required " + required + " bytes, available " + store.getUsableSpace());
        }

        String taskId = tasks.create("data_processing");
        String resultId = RESULT_TIME.format(Instant.now()) + "-" + taskId.substring(0, 8);
        TaskService.TaskState state = tasks.state(taskId);
        state.stage("queued");
        state.operation(operations.get(0).get("type").toString());
        tasks.submit(() -> run(taskId, resultId, source, main, sub, operations, clientIp, scan));
        return taskId;
    }

    public List<Map<String, Object>> results(String main, String sub) throws IOException {
        AnnotationService.validateComponent(main);
        AnnotationService.validateComponent(sub);
        Path parent = AppPaths.safeResolve(paths.processed(), main, sub);
        if (!Files.isDirectory(parent)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(parent)) {
            for (Path directory : stream.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                String id = directory.getFileName().toString();
                if (!validResultId(id)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("result_id", id);
                item.put("main", main);
                item.put("sub", sub);
                Path report = directory.resolve("report.json");
                if (Files.isRegularFile(report)) {
                    try {
                        JsonNode node = mapper.readTree(report.toFile());
                        item.put("completed_at", node.path("completed_at").asText(""));
                        item.put("summary", mapper.convertValue(node.path("summary"), Map.class));
                        item.put("operations", mapper.convertValue(node.path("operations"), List.class));
                    } catch (Exception exception) {
                        item.put("report_error", exception.getMessage());
                    }
                }
                result.add(item);
            }
        }
        return result;
    }

    public void writeResultZip(String main, String sub, String resultId, OutputStream output) throws IOException {
        Path result = requireResult(main, sub, resultId);
        writeDirectoryZip(result, resultId, output, "results");
    }

    public void requireResultExists(String main, String sub, String resultId) {
        requireResult(main, sub, resultId);
    }

    public Map<String, Object> deleteResult(String main, String sub, String resultId) throws IOException {
        Path result = requireResult(main, sub, resultId);
        long bytes;
        try (Stream<Path> stream = Files.walk(result)) {
            bytes = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(path -> safeSize(path)).sum();
        }
        deleteTree(result);
        return Map.of("status", "success", "result_id", resultId, "deleted_bytes", bytes);
    }

    public void requireSourceProjectExists(String main, String sub) throws IOException {
        scan(requireManagedSourceProject(main, sub));
    }

    public void writeSourceZip(String main, String sub, OutputStream output) throws IOException {
        Path source = requireManagedSourceProject(main, sub);
        writeDirectoryZip(source, sub, output, "source datasets");
    }

    public Map<String, Object> deleteSourceProject(String main, String sub) throws IOException {
        Path source = requireManagedSourceProject(main, sub);
        Scan scan = scan(source);
        deleteTree(source);
        return Map.of(
                "status", "success",
                "main", main,
                "sub", sub,
                "deleted_files", scan.files().size(),
                "deleted_bytes", scan.bytes());
    }

    private void run(String taskId, String resultId, Path source, String main, String sub,
                     List<Map<String, Object>> operations, String clientIp, Scan sourceScan) {
        TaskService.TaskState task = tasks.state(taskId);
        Path staging = null;
        String operationNames = operations.stream().map(item -> String.valueOf(item.get("type"))).toList().toString();
        workLog.write("PROCESS_DATASET_START", clientIp, main, sub, operationNames, null, null, resultId);
        try {
            Path parent = AppPaths.safeResolve(paths.processed(), main, sub);
            Files.createDirectories(parent);
            staging = Files.createTempDirectory(parent, "." + resultId + "-");
            Path dataset = staging.resolve("dataset");
            List<Map<String, String>> failures = new ArrayList<>();
            Map<String, Integer> summary = new LinkedHashMap<>();
            int totalWork = sourceScan.files().size();
            for (Map<String, Object> operation : operations) {
                totalWork += workUnits(sourceScan, String.valueOf(operation.get("type")));
            }
            Progress progress = new Progress(task, Math.max(totalWork, 1));

            task.stage("copying");
            task.operation("copy_source");
            copyTree(source, dataset, progress);

            for (Map<String, Object> operation : operations) {
                checkCancelled(task);
                String type = String.valueOf(operation.get("type"));
                task.operation(type);
                task.stage("processing");
                int changed = switch (type) {
                    case "image_repair" -> repairImages(dataset, failures, progress);
                    case "json_labels" -> modifyJsonLabels(dataset, operation, failures, progress);
                    case "mask_blackout" -> blackoutMasks(dataset, failures, progress);
                    case "file_rename" -> renameFiles(dataset, operation, failures, progress);
                    case "yolo_export" -> exportYolo(dataset, operation, failures, progress);
                    default -> throw new IllegalArgumentException("Unsupported operation: " + type);
                };
                summary.put(type, changed);
            }
            checkCancelled(task);

            ObjectNode report = mapper.createObjectNode();
            report.put("result_id", resultId);
            report.put("source", "data/" + main + "/" + sub);
            report.put("source_bytes", sourceScan.bytes());
            report.put("completed_at", Instant.now().toString());
            report.set("operations", mapper.valueToTree(operations));
            report.set("summary", mapper.valueToTree(summary));
            report.set("failed", mapper.valueToTree(failures));
            mapper.writerWithDefaultPrettyPrinter().writeValue(staging.resolve("report.json").toFile(), report);

            Path finalDirectory = parent.resolve(resultId);
            moveDirectory(staging, finalDirectory);
            staging = null;
            task.result(resultId, Map.of("counts", summary, "failures", failures.size(),
                    "main", main, "sub", sub));
            task.stage("completed");
            task.complete();
            workLog.write("PROCESS_DATASET_COMPLETE", clientIp, main, sub, operationNames,
                    sourceScan.images().size(), null, "processed/" + main + "/" + sub + "/" + resultId);
        } catch (TaskCancelled exception) {
            workLog.write("PROCESS_DATASET_CANCEL", clientIp, main, sub, operationNames, null, null, resultId);
        } catch (Exception exception) {
            task.fail(exception);
            workLog.write("PROCESS_DATASET_FAILED", clientIp, main, sub,
                    operationNames + ": " + exception.getMessage(), null, null, resultId);
        } finally {
            if (staging != null) {
                try { deleteTree(staging); } catch (IOException ignored) { }
            }
        }
    }

    private int repairImages(Path dataset, List<Map<String, String>> failures, Progress progress) throws IOException {
        int changed = 0;
        for (Path image : imageFiles(dataset)) {
            checkCancelled(progress.task());
            try {
                int orientation = orientation(image);
                BufferedImage normalized = orient(toRgb(readImage(image)), orientation);
                writeImageAtomic(image, normalized);
                Path json = sibling(image, ".json");
                if (orientation != 1 && Files.isRegularFile(json)) {
                    transformAnnotation(json, image.getFileName().toString(), orientation,
                            orientation >= 5 && orientation <= 8 ? normalized.getHeight() : normalized.getWidth(),
                            orientation >= 5 && orientation <= 8 ? normalized.getWidth() : normalized.getHeight(),
                            normalized.getWidth(), normalized.getHeight());
                }
                changed++;
            } catch (Exception exception) {
                failure(failures, dataset, image, "image_repair", exception);
            }
            progress.bump(changed);
        }
        return changed;
    }

    private int modifyJsonLabels(Path dataset, Map<String, Object> operation,
                                 List<Map<String, String>> failures, Progress progress) throws IOException {
        Map<String, LabelRule> rules = labelRules(operation.get("rules"));
        int changed = 0;
        for (Path json : jsonFiles(dataset)) {
            checkCancelled(progress.task());
            try {
                JsonNode parsed = mapper.readTree(json.toFile());
                if (!(parsed instanceof ObjectNode root) || !(root.path("shapes") instanceof ArrayNode shapes)) {
                    throw new IOException("Invalid LabelMe JSON structure");
                }
                ArrayNode output = mapper.createArrayNode();
                boolean modified = false;
                for (JsonNode item : shapes) {
                    if (!(item instanceof ObjectNode shape)) { output.add(item); continue; }
                    String label = shape.path("label").asText("");
                    LabelRule rule = rules.get(label);
                    if (rule == null) { output.add(shape); continue; }
                    modified = true;
                    if (!rule.delete()) {
                        shape.put("label", rule.to());
                        output.add(shape);
                    }
                }
                if (modified) {
                    root.set("shapes", output);
                    AnnotationService.atomicWrite(json, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
                    changed++;
                }
            } catch (Exception exception) {
                failure(failures, dataset, json, "json_labels", exception);
            }
            progress.bump(changed);
        }
        return changed;
    }

    private int blackoutMasks(Path dataset, List<Map<String, String>> failures, Progress progress) throws IOException {
        int changed = 0;
        for (Path image : imageFiles(dataset)) {
            checkCancelled(progress.task());
            Path json = sibling(image, ".json");
            try {
                if (!Files.isRegularFile(json)) { progress.bump(changed); continue; }
                JsonNode root = mapper.readTree(json.toFile());
                BufferedImage canvas = toRgb(readImage(image));
                Graphics2D graphics = canvas.createGraphics();
                graphics.setColor(Color.BLACK);
                boolean masked = false;
                try {
                    for (JsonNode shape : root.path("shapes")) {
                        if (!"mask".equals(shape.path("label").asText(""))) continue;
                        List<double[]> points = points(shape.path("points"));
                        if (points.size() >= 3) {
                            Path2D polygon = new Path2D.Double();
                            polygon.moveTo(points.get(0)[0], points.get(0)[1]);
                            for (int i = 1; i < points.size(); i++) polygon.lineTo(points.get(i)[0], points.get(i)[1]);
                            polygon.closePath();
                            graphics.fill(polygon);
                            masked = true;
                        } else if (points.size() >= 2) {
                            double x = Math.min(points.get(0)[0], points.get(1)[0]);
                            double y = Math.min(points.get(0)[1], points.get(1)[1]);
                            int width = (int) Math.ceil(Math.abs(points.get(0)[0] - points.get(1)[0]));
                            int height = (int) Math.ceil(Math.abs(points.get(0)[1] - points.get(1)[1]));
                            graphics.fillRect((int) Math.floor(x), (int) Math.floor(y), width, height);
                            masked = true;
                        }
                    }
                } finally {
                    graphics.dispose();
                }
                if (masked) {
                    writeImageAtomic(image, canvas);
                    changed++;
                }
            } catch (Exception exception) {
                failure(failures, dataset, image, "mask_blackout", exception);
            }
            progress.bump(changed);
        }
        return changed;
    }

    private int renameFiles(Path dataset, Map<String, Object> operation,
                            List<Map<String, String>> failures, Progress progress) throws IOException {
        List<Path> images = imageFiles(dataset);
        String mode = text(operation, "mode", "sequence");
        Map<Path, Path> moves = new LinkedHashMap<>();
        int index = integer(operation, "start", 1);
        int padding = integer(operation, "padding", 6);
        if (index < 0 || padding < 1 || padding > 12) throw new IllegalArgumentException("Invalid rename numbering");
        for (Path image : images) {
            String oldName = image.getFileName().toString();
            String oldStem = AnnotationService.stem(oldName);
            String extension = oldName.substring(oldStem.length());
            String newStem;
            if ("sequence".equals(mode)) {
                newStem = text(operation, "prefix", "image_")
                        + String.format(Locale.ROOT, "%0" + padding + "d", index++)
                        + text(operation, "suffix", "");
            } else if ("pattern".equals(mode)) {
                String find = text(operation, "find", "");
                String replaced = find.isEmpty() ? oldStem : oldStem.replace(find, text(operation, "replace", ""));
                newStem = text(operation, "prefix", "") + replaced + text(operation, "suffix", "");
            } else {
                throw new IllegalArgumentException("Rename mode must be sequence or pattern");
            }
            AnnotationService.fileName(newStem + extension);
            addMove(moves, image, image.resolveSibling(newStem + extension));
            for (String sidecar : List.of(".json", ".txt")) {
                Path source = image.resolveSibling(oldStem + sidecar);
                if (Files.isRegularFile(source)) addMove(moves, source, source.resolveSibling(newStem + sidecar));
            }
        }
        validateMoves(moves);
        Map<Path, Path> temporaryMoves = new LinkedHashMap<>();
        for (Map.Entry<Path, Path> move : moves.entrySet()) {
            checkCancelled(progress.task());
            if (move.getKey().equals(move.getValue())) continue;
            Path temporary = move.getKey().resolveSibling(".simplelabel-rename-" + UUID.randomUUID());
            Files.move(move.getKey(), temporary);
            temporaryMoves.put(temporary, move.getValue());
        }
        for (Map.Entry<Path, Path> move : temporaryMoves.entrySet()) Files.move(move.getKey(), move.getValue());
        for (int processed = 1; processed <= images.size(); processed++) progress.bump(processed);
        for (Path image : imageFiles(dataset)) {
            Path json = sibling(image, ".json");
            if (!Files.isRegularFile(json)) continue;
            try {
                JsonNode parsed = mapper.readTree(json.toFile());
                if (parsed instanceof ObjectNode root) {
                    root.put("imagePath", image.getFileName().toString());
                    AnnotationService.atomicWrite(json, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
                }
            } catch (Exception exception) {
                failure(failures, dataset, json, "file_rename", exception);
            }
        }
        return (int) images.stream().filter(image -> {
            Path target = moves.get(image);
            return target != null && !target.equals(image);
        }).count();
    }

    private int exportYolo(Path dataset, Map<String, Object> operation,
                           List<Map<String, String>> failures, Progress progress) throws IOException {
        List<String> labels = stringList(operation.get("labels"));
        List<String> expected = labelsFor(dataset);
        if (labels.size() != new LinkedHashSet<>(labels).size() || !new HashSet<>(labels).equals(new HashSet<>(expected))) {
            throw new IllegalArgumentException("Select every non-mask label exactly once before YOLO conversion");
        }
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (int i = 0; i < labels.size(); i++) ids.put(labels.get(i), i);
        AnnotationService.atomicWrite(dataset.resolve("classes.txt"),
                (String.join("\n", labels) + (labels.isEmpty() ? "" : "\n")).getBytes(StandardCharsets.UTF_8));
        int changed = 0;
        for (Path image : imageFiles(dataset)) {
            checkCancelled(progress.task());
            try {
                BufferedImage buffered = readImage(image);
                Path json = sibling(image, ".json");
                List<String> lines = Files.isRegularFile(json)
                        ? yoloLines(mapper.readTree(json.toFile()), ids, buffered.getWidth(), buffered.getHeight())
                        : List.of();
                AnnotationService.atomicWrite(sibling(image, ".txt"),
                        (String.join("\n", lines) + (lines.isEmpty() ? "" : "\n")).getBytes(StandardCharsets.UTF_8));
                changed++;
            } catch (Exception exception) {
                failure(failures, dataset, image, "yolo_export", exception);
            }
            progress.bump(changed);
        }
        return changed;
    }

    private List<Map<String, Object>> normalizeOperations(List<Map<String, Object>> requested) {
        if (requested == null || requested.isEmpty()) throw new IllegalArgumentException("Select at least one operation");
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> input : requested) {
            if (input == null) throw new IllegalArgumentException("Invalid operation");
            String type = text(input, "type", "");
            if (!OPERATION_TYPES.contains(type)) throw new IllegalArgumentException("Unsupported operation: " + type);
            if (!seen.add(type)) throw new IllegalArgumentException("Each operation can only be selected once");
            Map<String, Object> copy = new LinkedHashMap<>(input);
            copy.put("type", type);
            if ("json_labels".equals(type)) labelRules(copy.get("rules"));
            if ("file_rename".equals(type)) {
                String mode = text(copy, "mode", "sequence");
                if (!Set.of("sequence", "pattern").contains(mode)) throw new IllegalArgumentException("Invalid rename mode");
            }
            if ("yolo_export".equals(type)) stringList(copy.get("labels"));
            result.add(copy);
        }
        return List.copyOf(result);
    }

    private Map<String, LabelRule> labelRules(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("JSON label rules are required");
        }
        Map<String, LabelRule> result = new LinkedHashMap<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Invalid JSON label rule");
            String from = map.get("from") == null ? "" : String.valueOf(map.get("from")).trim();
            boolean delete = Boolean.TRUE.equals(map.get("delete")) || "delete".equals(String.valueOf(map.get("action")));
            String to = map.get("to") == null ? "" : String.valueOf(map.get("to")).trim();
            if (from.isEmpty() || (!delete && to.isEmpty())) throw new IllegalArgumentException("Label rules require from and to values");
            if (result.putIfAbsent(from, new LabelRule(to, delete)) != null) {
                throw new IllegalArgumentException("Duplicate label rule: " + from);
            }
        }
        return result;
    }

    private List<String> labelsFor(Path source) throws IOException {
        Set<String> result = new TreeSet<>();
        for (Path json : jsonFiles(source)) {
            try {
                for (JsonNode shape : mapper.readTree(json.toFile()).path("shapes")) {
                    String label = shape.path("label").asText("");
                    if (!label.isBlank() && !"mask".equals(label)) result.add(label);
                }
            } catch (Exception ignored) { }
        }
        return new ArrayList<>(result);
    }

    private Path requireProject(String main, String sub) {
        Path source = annotations.projectPath(main, sub);
        if (!Files.isDirectory(source)) throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        return source;
    }

    private Path requireManagedSourceProject(String main, String sub) {
        if (!MANAGED_SOURCE_MAIN.equals(main)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Data processing is only available for completed tasks");
        }
        return requireProject(main, sub);
    }

    private Path requireResult(String main, String sub, String resultId) {
        AnnotationService.validateComponent(main);
        AnnotationService.validateComponent(sub);
        if (!validResultId(resultId)) throw new IllegalArgumentException("Invalid result identifier");
        Path result = AppPaths.safeResolve(paths.processed(), main, sub, resultId);
        if (!Files.isDirectory(result)) throw new ApiException(HttpStatus.NOT_FOUND, "Result not found");
        return result;
    }

    private static boolean validResultId(String value) {
        return value != null && value.matches("\\d{8}-\\d{6}-[0-9a-f]{8}");
    }

    private static long requiredSpace(long bytes) {
        return (long) Math.ceil(bytes * 1.25d) + SPACE_MARGIN;
    }

    private static Scan scan(Path root) throws IOException {
        List<Path> files = new ArrayList<>(), images = new ArrayList<>(), json = new ArrayList<>();
        long bytes = 0;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not supported: " + root.relativize(path));
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                files.add(path);
                bytes += Files.size(path);
                String name = path.getFileName().toString();
                if (AnnotationService.isImage(name)) images.add(path);
                if (name.toLowerCase(Locale.ROOT).endsWith(".json")) json.add(path);
            }
        }
        return new Scan(List.copyOf(files), List.copyOf(images), List.copyOf(json), bytes);
    }

    private static int workUnits(Scan scan, String operation) {
        return switch (operation) {
            case "json_labels" -> scan.jsonFiles().size();
            default -> scan.images().size();
        };
    }

    private static void copyTree(Path source, Path destination, Progress progress) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                checkCancelled(progress.task());
                if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not supported");
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                    progress.bump(0);
                }
            }
        }
    }

    private static List<Path> imageFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> AnnotationService.isImage(path.getFileName().toString())).sorted().toList();
        }
    }

    private static List<Path> jsonFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .filter(path -> !".label_colors.json".equals(path.getFileName().toString()))
                    .sorted().toList();
        }
    }

    private static BufferedImage readImage(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) throw new IOException("Cannot read image");
        return image;
    }

    private static BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) return source;
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return result;
    }

    private static int orientation(Path image) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(image.toFile());
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            return directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)
                    ? directory.getInt(ExifIFD0Directory.TAG_ORIENTATION) : 1;
        } catch (Exception ignored) {
            return 1;
        }
    }

    private static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation < 2 || orientation > 8) return source;
        int width = source.getWidth(), height = source.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage output = new BufferedImage(swap ? height : width, swap ? width : height,
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dx = x, dy = y;
                switch (orientation) {
                    case 2 -> dx = width - 1 - x;
                    case 3 -> { dx = width - 1 - x; dy = height - 1 - y; }
                    case 4 -> dy = height - 1 - y;
                    case 5 -> { dx = y; dy = x; }
                    case 6 -> { dx = height - 1 - y; dy = x; }
                    case 7 -> { dx = height - 1 - y; dy = width - 1 - x; }
                    case 8 -> { dx = y; dy = width - 1 - x; }
                    default -> { }
                }
                output.setRGB(dx, dy, source.getRGB(x, y));
            }
        }
        return output;
    }

    private void transformAnnotation(Path json, String imageName, int orientation,
                                     int oldWidth, int oldHeight, int newWidth, int newHeight) throws IOException {
        JsonNode parsed = mapper.readTree(json.toFile());
        if (!(parsed instanceof ObjectNode root)) return;
        for (JsonNode shape : root.path("shapes")) {
            if (!(shape instanceof ObjectNode object) || !(object.path("points") instanceof ArrayNode points)) continue;
            for (JsonNode point : points) {
                if (!(point instanceof ArrayNode coordinates) || coordinates.size() < 2) continue;
                double x = coordinates.get(0).asDouble(), y = coordinates.get(1).asDouble();
                double nx = x, ny = y;
                switch (orientation) {
                    case 2 -> nx = oldWidth - x;
                    case 3 -> { nx = oldWidth - x; ny = oldHeight - y; }
                    case 4 -> ny = oldHeight - y;
                    case 5 -> { nx = y; ny = x; }
                    case 6 -> { nx = oldHeight - y; ny = x; }
                    case 7 -> { nx = oldHeight - y; ny = oldWidth - x; }
                    case 8 -> { nx = y; ny = oldWidth - x; }
                    default -> { }
                }
                coordinates.set(0, mapper.getNodeFactory().numberNode(nx));
                coordinates.set(1, mapper.getNodeFactory().numberNode(ny));
            }
        }
        root.put("imagePath", imageName);
        root.put("imageWidth", newWidth);
        root.put("imageHeight", newHeight);
        AnnotationService.atomicWrite(json, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    }

    private static void writeImageAtomic(Path destination, BufferedImage image) throws IOException {
        String format = extension(destination.getFileName().toString());
        Path temporary = Files.createTempFile(destination.getParent(), ".simplelabel-image-", "." + format);
        try {
            if (!ImageIO.write(image, format, temporary.toFile())) throw new IOException("Unsupported image format: " + format);
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        String value = dot < 0 ? "jpg" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return "jpeg".equals(value) ? "jpg" : value;
    }

    private static Path sibling(Path file, String extension) {
        return file.resolveSibling(AnnotationService.stem(file.getFileName().toString()) + extension);
    }

    private static List<double[]> points(JsonNode node) {
        List<double[]> result = new ArrayList<>();
        for (JsonNode point : node) {
            if (point.isArray() && point.size() >= 2 && point.get(0).isNumber() && point.get(1).isNumber()) {
                result.add(new double[]{point.get(0).asDouble(), point.get(1).asDouble()});
            }
        }
        return result;
    }

    private static List<String> yoloLines(JsonNode data, Map<String, Integer> ids, int width, int height) {
        List<String> lines = new ArrayList<>();
        for (JsonNode shape : data.path("shapes")) {
            String label = shape.path("label").asText("");
            if ("mask".equals(label) || !ids.containsKey(label)) continue;
            List<double[]> points = points(shape.path("points"));
            if (points.size() < 2) continue;
            double minX = points.stream().mapToDouble(point -> point[0]).min().orElse(0);
            double maxX = points.stream().mapToDouble(point -> point[0]).max().orElse(0);
            double minY = points.stream().mapToDouble(point -> point[1]).min().orElse(0);
            double maxY = points.stream().mapToDouble(point -> point[1]).max().orElse(0);
            minX = Math.max(0, minX); minY = Math.max(0, minY);
            maxX = Math.min(width, maxX); maxY = Math.min(height, maxY);
            if (maxX <= minX || maxY <= minY) continue;
            lines.add(String.format(Locale.ROOT, "%d %.6f %.6f %.6f %.6f", ids.get(label),
                    ((minX + maxX) / 2) / width, ((minY + maxY) / 2) / height,
                    (maxX - minX) / width, (maxY - minY) / height));
        }
        return lines;
    }

    private static void addMove(Map<Path, Path> moves, Path source, Path destination) {
        moves.put(source.toAbsolutePath().normalize(), destination.toAbsolutePath().normalize());
    }

    private static void validateMoves(Map<Path, Path> moves) throws IOException {
        Set<String> targets = new HashSet<>();
        Set<Path> sources = moves.keySet();
        for (Map.Entry<Path, Path> move : moves.entrySet()) {
            String portableTarget = move.getValue().toString().toLowerCase(Locale.ROOT);
            if (!targets.add(portableTarget)) throw new IllegalArgumentException("Rename produces duplicate files: " + move.getValue().getFileName());
            if (!move.getKey().equals(move.getValue()) && Files.exists(move.getValue()) && !sources.contains(move.getValue())) {
                throw new IllegalArgumentException("Rename target already exists: " + move.getValue().getFileName());
            }
        }
    }

    private static void moveDirectory(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, destination); }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            IOException[] failure = new IOException[1];
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException exception) { if (failure[0] == null) failure[0] = exception; }
            });
            if (failure[0] != null) throw failure[0];
        }
    }

    private static void writeDirectoryZip(Path root, String archiveRoot, OutputStream output,
                                          String description) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8);
             Stream<Path> stream = Files.walk(root)) {
            for (Path file : stream.sorted().toList()) {
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("Symbolic links are not supported in " + description);
                }
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = root.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(archiveRoot + "/" + relative));
                Files.copy(file, zip);
                zip.closeEntry();
            }
            zip.finish();
        }
    }

    private static long safeSize(Path path) {
        try { return Files.size(path); } catch (IOException ignored) { return 0; }
    }

    private static void failure(List<Map<String, String>> failures, Path root, Path file,
                                String operation, Exception exception) {
        failures.add(Map.of("operation", operation,
                "file", root.relativize(file).toString().replace('\\', '/'),
                "error", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
    }

    private static String text(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int integer(Map<?, ?> map, String key, int fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid number: " + key); }
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("Labels must be an array of strings");
        List<String> result = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Labels must be non-empty strings");
            result.add(text);
        }
        return result;
    }

    private static void checkCancelled(TaskService.TaskState task) {
        if (task.cancelled()) throw new TaskCancelled();
    }

    private record Scan(List<Path> files, List<Path> images, List<Path> jsonFiles, long bytes) { }
    private record LabelRule(String to, boolean delete) { }
    private static final class TaskCancelled extends RuntimeException { }
    private static final class Progress {
        private final TaskService.TaskState task;
        private int value;
        private int processed;
        Progress(TaskService.TaskState task, int total) { this.task = task; task.total(total); }
        TaskService.TaskState task() { return task; }
        void bump(int processedValue) { value++; processed = Math.max(processed, processedValue); task.progress(value, processed); }
    }
}
