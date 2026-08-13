package com.simplelabel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplelabel.config.ApiException;
import com.simplelabel.config.AppPaths;
import com.simplelabel.task.TaskService;
import com.simplelabel.worker.YoloWorkerClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Service
public class AutoLabelService {
    private final AppPaths paths;
    private final AnnotationService annotations;
    private final TaskService tasks;
    private final YoloWorkerClient worker;
    private final ObjectMapper mapper;

    public AutoLabelService(AppPaths paths, AnnotationService annotations, TaskService tasks,
                            YoloWorkerClient worker, ObjectMapper mapper) {
        this.paths = paths; this.annotations = annotations; this.tasks = tasks;
        this.worker = worker; this.mapper = mapper;
    }

    public String start(String main, String sub, String modelName, double confidence,
                        String backend, String customRepo) {
        AnnotationService.fileName(modelName);
        if (!Files.isRegularFile(paths.safeModelPath(modelName))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Model not found");
        }
        Path project = annotations.projectPath(main, sub);
        if (!Files.isDirectory(project)) throw new ApiException(HttpStatus.NOT_FOUND, "Project path not found");
        if (confidence < 0 || confidence > 1) throw new IllegalArgumentException("Confidence must be between 0 and 1");
        String taskId = tasks.create(backend);
        tasks.submit(() -> run(taskId, project, modelName, confidence, backend, customRepo));
        return taskId;
    }

    private void run(String taskId, Path project, String modelName, double confidence,
                     String backend, String customRepo) {
        TaskService.TaskState task = tasks.state(taskId);
        if (task == null) return;
        try {
            List<Path> images;
            try (Stream<Path> stream = Files.list(project)) {
                images = stream.filter(Files::isRegularFile)
                        .filter(path -> AnnotationService.isImage(path.getFileName().toString()))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            }
            task.total(images.size());
            int saved = 0;
            for (int index = 0; index < images.size(); index++) {
                if (task.cancelled()) break;
                Path image = images.get(index);
                JsonNode response = worker.infer(modelName, backend, customRepo, image, confidence);
                task.backend(response.path("backend").asText(backend));
                JsonNode shapes = response.path("shapes");
                if (shapes.isArray() && !shapes.isEmpty()) {
                    int width = response.path("width").asInt(0), height = response.path("height").asInt(0);
                    if (width <= 0 || height <= 0) {
                        BufferedImage buffered = ImageIO.read(image.toFile());
                        if (buffered != null) { width = buffered.getWidth(); height = buffered.getHeight(); }
                    }
                    ObjectNode payload = mapper.createObjectNode();
                    payload.put("version", "5.2.1"); payload.set("flags", mapper.createObjectNode());
                    payload.set("shapes", shapes); payload.put("imagePath", image.getFileName().toString());
                    payload.putNull("imageData"); payload.put("imageHeight", height); payload.put("imageWidth", width);
                    AnnotationService.atomicWrite(project.resolve(AnnotationService.stem(image.getFileName().toString()) + ".json"),
                            mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload));
                    saved++;
                }
                task.progress(index + 1, saved);
            }
            task.complete();
        } catch (Exception exception) {
            task.fail(exception);
        }
    }
}
