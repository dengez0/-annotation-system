package com.simplelabel.controller;

import com.simplelabel.config.ApiException;
import com.simplelabel.service.AdminAccessService;
import com.simplelabel.service.AnnotationService;
import com.simplelabel.service.FileService;
import com.simplelabel.service.WorkLogService;
import com.simplelabel.service.ProjectAccessService;
import com.simplelabel.service.WorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
public class FileController {
    private final FileService files;
    private final AnnotationService annotations;
    private final WorkLogService workLog;
    private final AdminAccessService admin;
    private final ProjectAccessService access;
    private final WorkflowService workflow;

    public FileController(FileService files, AnnotationService annotations, WorkLogService workLog,
                          AdminAccessService admin, ProjectAccessService access, WorkflowService workflow) {
        this.files = files; this.annotations = annotations; this.workLog = workLog; this.admin = admin;
        this.access = access;
        this.workflow = workflow;
    }

    @PostMapping(path = "/api/annotation-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> uploadAnnotation(@RequestParam("files[]") List<MultipartFile> uploads,
                                         @RequestParam String task_name,
                                         @RequestParam String upload_id,
                                         @RequestParam(defaultValue = "[]") String file_paths,
                                         @RequestParam(defaultValue = "false") boolean complete,
                                         HttpServletRequest request) throws IOException {
        Map<String, Object> result = files.uploadAnnotation(uploads, task_name, upload_id, file_paths, complete);
        workLog.write(complete ? "UPLOAD_TASK" : "UPLOAD_CHUNK", request.getRemoteAddr(),
                String.valueOf(result.get("main_folder")), task_name, "files",
                ((Number) result.get("count")).intValue(), null, null);
        return result;
    }

    @DeleteMapping("/api/annotation-upload/{uploadId}")
    Map<String, Object> cancelAnnotationUpload(@PathVariable String uploadId) throws IOException {
        files.cancelAnnotationUpload(uploadId);
        return Map.of("status", "success");
    }

    @PostMapping("/api/rename_project")
    Map<String, Object> rename(@RequestBody Map<String, Object> body, HttpServletRequest request) throws IOException {
        String main = string(body, "main_folder"), oldName = string(body, "old_name");
        String newName = string(body, "new_name"), level = defaultString(body, "level", "main");
        if (oldName == null || newName == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Missing parameters");
        if ("sub".equals(level)) access.requireModify(main, request);
        Map<String, Object> result = files.rename(oldName, newName, level, main);
        workLog.write("RENAME_PROJECT", request.getRemoteAddr(), "sub".equals(level) ? main : oldName,
                "sub".equals(level) ? oldName : null, oldName + " -> " + newName, null, null, null);
        return result;
    }

    @PostMapping("/api/delete_project")
    Map<String, Object> deleteProject(@RequestBody Map<String, Object> body, HttpServletRequest request) throws IOException {
        String main = string(body, "main_folder"), sub = string(body, "subfolder");
        String level = defaultString(body, "level", "main");
        if (main == null || ("sub".equals(level) && sub == null)) throw new ApiException(HttpStatus.BAD_REQUEST, "Missing parameters");
        access.requireModify(main, request);
        Map<String, Object> result = files.deleteProject(main, sub, level);
        workLog.write("DELETE_PROJECT", request.getRemoteAddr(), main, sub, level, null, null, null);
        return result;
    }

    @PostMapping("/api/delete_files/{main}/{sub}")
    Map<String, Object> deleteFiles(@PathVariable String main, @PathVariable String sub,
                                    @RequestBody Map<String, Object> body, HttpServletRequest request) throws IOException {
        List<String> names = strings(body, "filenames"); requireFiles(names);
        access.requireModify(main, request);
        Map<String, Object> result = files.deleteFiles(main, sub, names);
        workLog.write("DELETE_FILES", request.getRemoteAddr(), main, sub, "files", number(result, "deleted"), null, null);
        return result;
    }

    Map<String, Object> moveFiles(@PathVariable String main, @PathVariable String sub,
                                  @RequestBody Map<String, Object> body, HttpServletRequest request) throws IOException {
        List<String> names = strings(body, "filenames"); requireFiles(names);
        access.requireModify(main, request);
        String destFolder = defaultString(body, "dest_folder", "");
        String destMain = defaultString(body, "dest_main_folder", "");
        String destSub = defaultString(body, "dest_subfolder", "");
        Map<String, Object> result = files.moveFiles(main, sub, names, destFolder, destMain, destSub);
        String destination = !destMain.isBlank() && !destSub.isBlank() ? destMain + "/" + destSub : destFolder;
        workLog.write("MOVE_FILES", request.getRemoteAddr(), main, sub, "files", number(result, "moved"), null, destination);
        return result;
    }

    @PostMapping("/api/move_to_completed/{main}/{sub}")
    Map<String, Object> moveCompleted(@PathVariable String main, @PathVariable String sub,
                                      @RequestBody Map<String, Object> body, HttpServletRequest request) throws IOException {
        List<String> names = strings(body, "filenames"); requireFiles(names);
        access.requireModify(main, request);
        Map<String, Object> result = files.moveToCompleted(main, sub, names);
        workLog.write("MOVE_FILES", request.getRemoteAddr(), main, sub, "files", number(result, "moved"), null,
                String.valueOf(result.get("destination")));
        return result;
    }

    @PostMapping("/api/restore_from_completed/{sub}")
    Map<String, Object> restore(@PathVariable String sub, @RequestBody Map<String, Object> body,
                                HttpServletRequest request) throws IOException {
        List<String> names = strings(body, "filenames"); requireFiles(names);
        Map<String, Object> result = files.restoreFromCompleted(sub, names);
        workLog.write("RESTORE_FILES", request.getRemoteAddr(), "moved image", sub, "files",
                number(result, "moved"), null, String.valueOf(result.get("destination")));
        return result;
    }

    @PostMapping("/api/copy_files/{main}/{sub}")
    Map<String, Object> copy(@PathVariable String main, @PathVariable String sub,
                             @RequestBody Map<String, Object> body, HttpServletRequest request) throws IOException {
        List<String> names = strings(body, "filenames"); requireFiles(names);
        access.requireModify(main, request);
        Map<String, Object> result = files.copyToPaste(main, sub, names);
        workLog.write("COPY_FILES", request.getRemoteAddr(), main, sub, "files", number(result, "copied"), null, "paste image");
        return result;
    }

    @PostMapping("/api/create_empty_jsons/{main}/{sub}")
    Map<String, Object> createEmpty(@PathVariable String main, @PathVariable String sub,
                                    HttpServletRequest request) throws IOException {
        access.requireModify(main, request);
        WorkflowService.SaveResult started = workflow.saveWithAutomaticStart(main, sub, ignored -> { });
        Map<String, Object> result = annotations.createEmptyJsons(started.main(), sub);
        if (result == null) throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        result.put("state_changed", started.stateChanged());
        result.put("redirect_url", "/annotate/" + started.main() + "/" + sub);
        workLog.write("CREATE_EMPTY_JSONS", request.getRemoteAddr(), started.main(), sub, "images", number(result, "created"), null, null);
        return result;
    }

    @PostMapping("/api/export_yolo/{main}/{sub}")
    Map<String, Object> exportYolo(@PathVariable String main, @PathVariable String sub,
                                   @RequestBody Map<String, Object> body, HttpServletRequest request) throws IOException {
        requireAdmin(request);
        Object rawLabels = body.get("labels");
        if (!(rawLabels instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof String))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Labels must be an array of strings");
        }
        List<String> labels = values.stream().map(String.class::cast).toList();
        if (labels.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "No labels selected");
        Map<String, Object> result = files.exportYolo(main, sub, labels);
        workLog.write("EXPORT_YOLO", request.getRemoteAddr(), main, sub, String.valueOf(result.get("labels_dir")), number(result, "exported"), null, null);
        return result;
    }

    @GetMapping("/api/export/{main}/{sub}")
    ResponseEntity<StreamingResponseBody> exportZip(@PathVariable String main, @PathVariable String sub,
                                                     HttpServletRequest request) {
        requireAdmin(request);
        if (!java.nio.file.Files.isDirectory(annotations.projectPath(main, sub))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        }
        workLog.write("EXPORT_PROJECT", request.getRemoteAddr(), main, sub, "zip", null, null, null);
        StreamingResponseBody stream = output -> files.writeZip(main, sub, output);
        ContentDisposition disposition = ContentDisposition.attachment().filename(sub + ".zip", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(stream);
    }

    private static String string(Map<String, Object> body, String key) { Object value = body.get(key); return value == null ? null : String.valueOf(value); }
    private static String defaultString(Map<String, Object> body, String key, String fallback) { String value = string(body, key); return value == null ? fallback : value; }
    private static int number(Map<String, Object> body, String key) { Object value = body.get(key); return value instanceof Number n ? n.intValue() : 0; }
    private static void requireFiles(List<String> values) { if (values.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "No files specified"); }
    private static List<String> strings(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private void requireAdmin(HttpServletRequest request) {
        if (!admin.isAllowed(request)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Administrator access required");
        }
    }
}
