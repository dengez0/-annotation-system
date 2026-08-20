package com.simplelabel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplelabel.config.AppPaths;
import com.simplelabel.task.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetProcessingServiceTest {
    @TempDir
    Path temporary;
    private TaskService tasks;

    @AfterEach
    void closeTasks() {
        if (tasks != null) tasks.close();
    }

    @Test
    void orderedPipelineCreatesCompleteCopyWithoutChangingSource() throws Exception {
        Fixture fixture = fixture();
        Path source = fixture.paths.data().resolve("main/sub");
        Path image = source.resolve("sample.jpg");
        Path json = source.resolve("sample.json");
        Files.createDirectories(source);
        BufferedImage original = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 20, 20); graphics.dispose();
        ImageIO.write(original, "jpg", image.toFile());
        Files.writeString(json, """
                {"version":"5.2.1","imagePath":"sample.jpg","imageWidth":20,"imageHeight":20,
                 "shapes":[
                   {"label":"person","points":[[10,10],[18,18]],"shape_type":"rectangle"},
                   {"label":"mask","points":[[0,0],[6,0],[6,6],[0,6]],"shape_type":"polygon"}
                 ]}
                """);
        Files.writeString(source.resolve("notes.md"), "keep me");
        String imageHash = hash(image), jsonHash = hash(json);

        List<Map<String, Object>> operations = List.of(
                Map.of("type", "image_repair"),
                Map.of("type", "json_labels", "rules", List.of(Map.of("from", "person", "to", "worker"))),
                Map.of("type", "mask_blackout"),
                Map.of("type", "file_rename", "mode", "sequence", "prefix", "frame_", "start", 7, "padding", 3),
                Map.of("type", "yolo_export", "labels", List.of("worker")));
        String taskId = fixture.processing.start("main", "sub", operations, "192.168.1.10");
        Map<String, Object> task = await(taskId);

        assertEquals("completed", task.get("status"), String.valueOf(task.get("error")));
        String resultId = String.valueOf(task.get("result_id"));
        Path dataset = fixture.paths.processed().resolve("main/sub").resolve(resultId).resolve("dataset");
        Path outputImage = dataset.resolve("frame_007.jpg");
        Path outputJson = dataset.resolve("frame_007.json");
        assertTrue(Files.isRegularFile(outputImage));
        assertTrue(Files.isRegularFile(outputJson));
        assertTrue(Files.isRegularFile(dataset.resolve("frame_007.txt")));
        assertEquals("worker\n", Files.readString(dataset.resolve("classes.txt")));
        assertEquals("keep me", Files.readString(dataset.resolve("notes.md")));
        JsonNode outputAnnotation = fixture.mapper.readTree(outputJson.toFile());
        assertEquals("frame_007.jpg", outputAnnotation.path("imagePath").asText());
        assertEquals(List.of("worker", "mask"), outputAnnotation.path("shapes").findValuesAsText("label"));
        BufferedImage processed = ImageIO.read(outputImage.toFile());
        assertTrue(new Color(processed.getRGB(2, 2)).getRed() < 20, "mask region should be black");
        assertEquals(imageHash, hash(image));
        assertEquals(jsonHash, hash(json));
        assertTrue(Files.isRegularFile(dataset.getParent().resolve("report.json")));

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        fixture.processing.writeResultZip("main", "sub", resultId, archive);
        Set<String> entries = zipEntries(archive.toByteArray());
        assertTrue(entries.contains(resultId + "/dataset/frame_007.jpg"));
        assertTrue(entries.contains(resultId + "/dataset/frame_007.json"));
        assertTrue(entries.contains(resultId + "/dataset/frame_007.txt"));
        assertTrue(entries.contains(resultId + "/dataset/classes.txt"));
    }

    @Test
    void deletingResultNeverDeletesSource() throws Exception {
        Fixture fixture = fixture();
        Path source = fixture.paths.data().resolve("main/sub");
        Files.createDirectories(source);
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", source.resolve("a.png").toFile());
        String taskId = fixture.processing.start("main", "sub", List.of(Map.of("type", "image_repair")), "127.0.0.1");
        Map<String, Object> task = await(taskId);
        String resultId = String.valueOf(task.get("result_id"));

        fixture.processing.deleteResult("main", "sub", resultId);

        assertTrue(Files.isRegularFile(source.resolve("a.png")));
        assertFalse(Files.exists(fixture.paths.processed().resolve("main/sub").resolve(resultId)));
    }

    @Test
    void annotationSourceCanBeDownloadedAndDeletedWithoutProcessing() throws Exception {
        Fixture fixture = fixture();
        Path source = fixture.paths.data().resolve("已完成/batch-01");
        Files.createDirectories(source.resolve("nested"));
        Files.writeString(source.resolve("sample.jpg"), "image");
        Files.writeString(source.resolve("sample.json"), "{}");
        Files.writeString(source.resolve("nested/sample.txt"), "0 0.5 0.5 1 1");
        Path sibling = fixture.paths.data().resolve("已完成/batch-02/keep.txt");
        Files.createDirectories(sibling.getParent());
        Files.writeString(sibling, "keep");

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        fixture.processing.requireSourceProjectExists("已完成", "batch-01");
        fixture.processing.writeSourceZip("已完成", "batch-01", archive);

        assertEquals(Set.of(
                "batch-01/sample.jpg",
                "batch-01/sample.json",
                "batch-01/nested/sample.txt"), zipEntries(archive.toByteArray()));

        Map<String, Object> deleted = fixture.processing.deleteSourceProject("已完成", "batch-01");
        assertEquals(3, deleted.get("deleted_files"));
        assertFalse(Files.exists(source));
        assertTrue(Files.isRegularFile(sibling));
    }

    private Fixture fixture() throws Exception {
        Path root = temporary.resolve("runtime");
        AppPaths paths = new AppPaths(root.toString());
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("models"));
        Files.createDirectories(root.resolve("logs"));
        Files.createDirectories(root.resolve("processed"));
        ObjectMapper mapper = new ObjectMapper();
        tasks = new TaskService();
        AnnotationService annotations = new AnnotationService(paths, mapper);
        WorkLogService logs = new WorkLogService(paths, Clock.system(ZoneId.of("Asia/Shanghai")));
        return new Fixture(paths, mapper, new DatasetProcessingService(paths, annotations, tasks, mapper, logs));
    }

    private Map<String, Object> await(String taskId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            Map<String, Object> task = tasks.get(taskId);
            assertNotNull(task);
            if (List.of("completed", "failed", "cancelled").contains(task.get("status"))) return task;
            Thread.sleep(25);
        }
        throw new AssertionError("Task did not finish in time");
    }

    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static Set<String> zipEntries(byte[] archive) throws Exception {
        Set<String> result = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                result.add(entry.getName());
            }
        }
        return result;
    }

    private record Fixture(AppPaths paths, ObjectMapper mapper, DatasetProcessingService processing) { }
}
