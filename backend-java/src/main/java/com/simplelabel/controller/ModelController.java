package com.simplelabel.controller;

import com.simplelabel.service.ModelService;
import com.simplelabel.service.WorkLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
public class ModelController {
    private final ModelService models;
    private final WorkLogService workLog;

    public ModelController(ModelService models, WorkLogService workLog) { this.models = models; this.workLog = workLog; }

    @GetMapping("/api/models")
    List<String> list() throws IOException { return models.list(); }

    @PostMapping("/api/upload_model")
    Map<String, Object> upload(@RequestParam("model_file") MultipartFile file,
                               @RequestParam(required = false) String custom_name,
                               HttpServletRequest request) throws IOException {
        String name = models.save(file, custom_name);
        workLog.write("UPLOAD_MODEL", request.getRemoteAddr(), null, null, name, 1, null, null);
        return Map.of("status", "success");
    }

    @DeleteMapping("/api/delete_model/{modelName}")
    Map<String, Object> delete(@PathVariable String modelName, HttpServletRequest request) throws IOException {
        models.delete(modelName);
        workLog.write("DELETE_MODEL", request.getRemoteAddr(), null, null, modelName, 1, null, null);
        return Map.of("status", "success");
    }
}
