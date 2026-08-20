package com.simplelabel.controller;

import com.simplelabel.service.AnnotationService;
import com.simplelabel.service.WorkflowService;
import com.simplelabel.service.WorkflowState;
import com.simplelabel.service.WorkLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class WorkflowController {
    private final WorkflowService workflow;
    private final AnnotationService annotations;
    private final WorkLogService workLog;

    public WorkflowController(WorkflowService workflow, AnnotationService annotations, WorkLogService workLog) {
        this.workflow = workflow; this.annotations = annotations; this.workLog = workLog;
    }

    @PostMapping("/api/workflow/tasks/{task}/transition")
    Map<String, Object> transition(@PathVariable String task, @RequestBody Map<String, Object> body,
                                   HttpServletRequest request) throws IOException {
        WorkflowState from = WorkflowState.fromId(String.valueOf(body.get("from")));
        WorkflowState to = WorkflowState.fromId(String.valueOf(body.get("to")));
        boolean allowIncomplete = Boolean.TRUE.equals(body.get("allow_incomplete"));
        int images = annotations.countImages(from.directory(), task);
        int annotated = annotations.countAnnotatedImages(from.directory(), task);
        WorkflowService.TransitionResult moved = workflow.transition(task, from, to, allowIncomplete, images, annotated);
        workLog.write(to == WorkflowState.REVIEW ? "SUBMIT_REVIEW" : "COMPLETE_REVIEW",
                request.getRemoteAddr(), to.directory(), task, "folder", images, null, from.directory());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success"); result.put("from", moved.from()); result.put("to", moved.to());
        result.put("destination", moved.directory()); result.put("images", images); result.put("annotated", annotated);
        return result;
    }
}
