package com.simplelabel.controller;

import com.simplelabel.config.ApiException;
import com.simplelabel.service.AdminAccessService;
import com.simplelabel.service.DatasetProcessingService;
import com.simplelabel.service.WorkLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class DatasetProcessingController {
    private final DatasetProcessingService processing;
    private final WorkLogService workLog;
    private final AdminAccessService admin;

    public DatasetProcessingController(DatasetProcessingService processing, WorkLogService workLog,
                                       AdminAccessService admin) {
        this.processing = processing;
        this.workLog = workLog;
        this.admin = admin;
    }

    @GetMapping("/api/data-processing/projects")
    List<Map<String, Object>> projects(HttpServletRequest request) throws IOException {
        requireAdmin(request);
        return processing.projects();
    }

    @GetMapping("/api/data-processing/inspect/{main}/{sub}")
    Map<String, Object> inspect(@PathVariable String main, @PathVariable String sub,
                                HttpServletRequest request) throws IOException {
        requireAdmin(request);
        return processing.inspect(main, sub);
    }

    @PostMapping("/api/data-processing/tasks")
    Map<String, Object> start(@RequestBody Map<String, Object> body, HttpServletRequest request) throws IOException {
        requireAdmin(request);
        String main = requiredText(body, "main");
        String sub = requiredText(body, "sub");
        List<Map<String, Object>> operations = operationList(body.get("operations"));
        String taskId = processing.start(main, sub, operations, request.getRemoteAddr());
        return Map.of("status", "started", "task_id", taskId);
    }

    @GetMapping("/api/data-processing/results/{main}/{sub}")
    List<Map<String, Object>> results(@PathVariable String main, @PathVariable String sub,
                                      HttpServletRequest request) throws IOException {
        requireAdmin(request);
        return processing.results(main, sub);
    }

    @GetMapping("/api/data-processing/source/{main}/{sub}/download")
    ResponseEntity<StreamingResponseBody> downloadSource(@PathVariable String main, @PathVariable String sub,
                                                          HttpServletRequest request) throws IOException {
        requireAdmin(request);
        processing.requireSourceProjectExists(main, sub);
        StreamingResponseBody body = output -> processing.writeSourceZip(main, sub, output);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(sub + "-source.zip", StandardCharsets.UTF_8).build();
        workLog.write("SOURCE_DATASET_DOWNLOAD", request.getRemoteAddr(), main, sub,
                "zip", null, null, null);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    @DeleteMapping("/api/data-processing/source/{main}/{sub}")
    Map<String, Object> deleteSource(@PathVariable String main, @PathVariable String sub,
                                     HttpServletRequest request) throws IOException {
        requireAdmin(request);
        Map<String, Object> result = processing.deleteSourceProject(main, sub);
        workLog.write("SOURCE_DATASET_DELETE", request.getRemoteAddr(), main, sub,
                "folder", ((Number) result.get("deleted_files")).intValue(), null, null);
        return result;
    }

    @GetMapping("/api/data-processing/results/{main}/{sub}/{resultId}/download")
    ResponseEntity<StreamingResponseBody> download(@PathVariable String main, @PathVariable String sub,
                                                    @PathVariable String resultId,
                                                    HttpServletRequest request) {
        requireAdmin(request);
        processing.requireResultExists(main, sub, resultId);
        StreamingResponseBody body = output -> processing.writeResultZip(main, sub, resultId, output);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(sub + "-" + resultId + ".zip", StandardCharsets.UTF_8).build();
        workLog.write("PROCESS_RESULT_DOWNLOAD", request.getRemoteAddr(), main, sub,
                resultId, null, null, null);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    @DeleteMapping("/api/data-processing/results/{main}/{sub}/{resultId}")
    Map<String, Object> delete(@PathVariable String main, @PathVariable String sub,
                               @PathVariable String resultId, HttpServletRequest request) throws IOException {
        requireAdmin(request);
        Map<String, Object> result = processing.deleteResult(main, sub, resultId);
        workLog.write("PROCESS_RESULT_DELETE", request.getRemoteAddr(), main, sub,
                resultId, null, null, null);
        return result;
    }

    @GetMapping("/api/data_processing/labels/{main}/{sub}")
    Map<String, Object> legacyLabels(@PathVariable String main, @PathVariable String sub,
                                     HttpServletRequest request) throws IOException {
        requireAdmin(request);
        return Map.of("labels", processing.labels(main, sub));
    }

    @PostMapping("/api/data_processing/process/{main}/{sub}")
    Map<String, Object> legacyProcess(@PathVariable String main, @PathVariable String sub,
                                      @RequestBody Map<String, Object> body,
                                      HttpServletRequest request) throws IOException {
        requireAdmin(request);
        List<String> labels = stringList(body.get("labels"));
        List<Map<String, Object>> operations = List.of(
                Map.of("type", "image_repair"),
                Map.of("type", "mask_blackout"),
                Map.of("type", "yolo_export", "labels", labels));
        String taskId = processing.start(main, sub, operations, request.getRemoteAddr());
        return Map.of("status", "started", "task_id", taskId);
    }

    private void requireAdmin(HttpServletRequest request) {
        if (!admin.isAllowed(request)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Administrator access required");
        }
    }

    private static String requiredText(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return String.valueOf(value);
    }

    private static List<Map<String, Object>> operationList(Object raw) {
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("operations must be an array");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> input)) throw new IllegalArgumentException("Invalid operation");
            Map<String, Object> operation = new LinkedHashMap<>();
            input.forEach((key, item) -> operation.put(String.valueOf(key), item));
            result.add(operation);
        }
        return result;
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("Labels must be an array of strings");
        if (list.stream().anyMatch(value -> !(value instanceof String))) {
            throw new IllegalArgumentException("Labels must be an array of strings");
        }
        return list.stream().map(String.class::cast).toList();
    }
}
