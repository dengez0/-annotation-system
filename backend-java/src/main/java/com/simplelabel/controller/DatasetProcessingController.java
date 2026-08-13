package com.simplelabel.controller;

import com.simplelabel.service.DatasetProcessingService;
import com.simplelabel.service.WorkLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
public class DatasetProcessingController {
    private final DatasetProcessingService processing;
    private final WorkLogService workLog;

    public DatasetProcessingController(DatasetProcessingService processing, WorkLogService workLog) {
        this.processing = processing; this.workLog = workLog;
    }

    @GetMapping("/api/data_processing/labels/{main}/{sub}")
    Map<String, Object> labels(@PathVariable String main, @PathVariable String sub) throws IOException {
        return Map.of("labels", processing.labels(main, sub));
    }

    @PostMapping("/api/data_processing/process/{main}/{sub}")
    Map<String, Object> process(@PathVariable String main, @PathVariable String sub,
                                @RequestBody Map<String, Object> body, HttpServletRequest request) throws IOException {
        Object raw = body.get("labels");
        List<String> labels = raw instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
        String taskId = processing.start(main, sub, labels);
        workLog.write("PROCESS_DATASET_START", request.getRemoteAddr(), main, sub, "processed", null, null, null);
        return Map.of("status", "started", "task_id", taskId);
    }
}
