package com.simplelabel.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplelabel.config.ApiException;
import com.simplelabel.config.AppPaths;
import com.simplelabel.service.AnnotationService;
import com.simplelabel.service.ProjectAccessService;
import com.simplelabel.service.WorkflowService;
import com.simplelabel.service.WorkflowState;
import com.simplelabel.service.WorkLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
public class AnnotationController {
    private final AnnotationService annotations;
    private final WorkLogService workLog;
    private final AppPaths paths;
    private final WorkflowService workflow;
    private final ProjectAccessService access;

    public AnnotationController(AnnotationService annotations, WorkLogService workLog, AppPaths paths,
                                WorkflowService workflow, ProjectAccessService access) {
        this.annotations = annotations;
        this.workLog = workLog;
        this.paths = paths;
        this.workflow = workflow;
        this.access = access;
    }

    @GetMapping("/api/projects")
    public List<String> projects(HttpServletRequest request) throws IOException {
        return annotations.listProjects().stream()
                .filter(name -> !WorkflowState.COMPLETED.directory().equals(name) || isAdmin(request)).toList();
    }

    @GetMapping("/api/subfolders/{main}")
    public List<Map<String, Object>> subfolders(@PathVariable String main, HttpServletRequest request) throws IOException {
        access.requireView(main, request);
        return annotations.listSubfolders(main);
    }

    @GetMapping("/api/images/{main}/{sub}")
    public List<Map<String, Object>> images(@PathVariable String main, @PathVariable String sub,
                                             HttpServletRequest request) throws IOException {
        access.requireView(main, request);
        return annotations.listImages(main, sub);
    }

    @GetMapping("/api/labels/{main}/{sub}")
    public List<String> labels(@PathVariable String main, @PathVariable String sub,
                               HttpServletRequest request) throws IOException {
        access.requireView(main, request);
        return annotations.listLabels(main, sub);
    }

    @GetMapping("/api/label-colors/{main}/{sub}")
    public Map<String, String> colors(@PathVariable String main, @PathVariable String sub,
                                      HttpServletRequest request) throws IOException {
        access.requireView(main, request);
        return annotations.getLabelColors(main, sub);
    }

    @PutMapping("/api/label-colors/{main}/{sub}")
    public Map<String, Object> updateColor(@PathVariable String main, @PathVariable String sub,
                                           @RequestBody Map<String, String> body,
                                           HttpServletRequest request) throws IOException {
        access.requireModify(main, request);
        return Map.of("status", "success", "colors",
                annotations.saveLabelColor(main, sub, body.get("label"), body.get("color")));
    }

    @PostMapping("/api/save/{main}/{sub}")
    public Map<String, Object> save(@PathVariable String main, @PathVariable String sub,
                                    @RequestBody JsonNode body, HttpServletRequest request) throws IOException {
        String filename = body.path("filename").asText("");
        JsonNode json = body.get("json");
        if (filename.isBlank() || json == null || json.isNull()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data");
        }
        access.requireModify(main, request);
        WorkflowService.SaveResult saved = workflow.saveWithAutomaticStart(main, sub,
                actualMain -> annotations.saveAnnotation(actualMain, sub, filename, json));
        int boxes = json.path("shapes").isArray() ? json.path("shapes").size() : 0;
        workLog.write(saved.stateChanged() ? "START_ANNOTATION" : "SAVE_ANNOTATION",
                request.getRemoteAddr(), saved.main(), sub, filename, null, boxes, null);
        return Map.of("status", "success", "state_changed", saved.stateChanged(),
                "current_main", saved.main(), "redirect_url", "/annotate/" + saved.main() + "/" + sub);
    }

    @GetMapping("/api/annotations/{main}/{sub}/{filename}")
    public JsonNode annotation(@PathVariable String main, @PathVariable String sub,
                               @PathVariable String filename, HttpServletRequest request) throws IOException {
        access.requireView(main, request);
        JsonNode result = annotations.readAnnotation(main, sub, filename);
        if (result == null) throw new ApiException(HttpStatus.NOT_FOUND, "Annotation not found");
        return result;
    }

    @GetMapping("/data/{main}/{sub}/{*filename}")
    public ResponseEntity<Resource> data(@PathVariable String main, @PathVariable String sub,
                                         @PathVariable String filename,
                                         HttpServletRequest request) throws IOException {
        access.requireView(main, request);
        AnnotationService.validateComponent(main);
        AnnotationService.validateComponent(sub);
        String relative = filename.startsWith("/") ? filename.substring(1) : filename;
        if (!AnnotationService.isImage(relative) || !relative.equals(AnnotationService.fileName(relative))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File not found");
        }
        Path project = annotations.projectPath(main, sub);
        Path file = AppPaths.safeResolve(project, relative);
        if (!Files.isRegularFile(file)) throw new ApiException(HttpStatus.NOT_FOUND, "File not found");
        MediaType type = MediaType.APPLICATION_OCTET_STREAM;
        String detected = Files.probeContentType(file);
        if (detected != null) type = MediaType.parseMediaType(detected);
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(file));
    }

    private boolean isAdmin(HttpServletRequest request) {
        try { access.requireView(WorkflowState.COMPLETED.directory(), request); return true; }
        catch (ApiException exception) { return false; }
    }
}
