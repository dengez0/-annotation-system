package com.simplelabel.controller;

import com.simplelabel.config.ApiException;
import com.simplelabel.service.AdminAccessService;
import com.simplelabel.service.AnnotationService;
import com.simplelabel.service.ProjectAccessService;
import com.simplelabel.service.WorkflowState;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.nio.file.Files;

@Controller
public class PageController {
    private final AnnotationService annotations;
    private final AdminAccessService adminAccess;
    private final ProjectAccessService projectAccess;
    private final int modelDetectionPort;

    public PageController(AnnotationService annotations, AdminAccessService adminAccess,
                          ProjectAccessService projectAccess,
                          @Value("${simplelabel.model-detection-port}") int modelDetectionPort) {
        this.annotations = annotations;
        this.adminAccess = adminAccess;
        this.projectAccess = projectAccess;
        this.modelDetectionPort = modelDetectionPort;
    }

    @GetMapping("/")
    String home() {
        return "home";
    }

    @GetMapping("/annotation")
    String index(Model model, HttpServletRequest request) throws IOException {
        model.addAttribute("mainFolders", annotations.listMainFolders(adminAccess.isAllowed(request)));
        model.addAttribute("dataProcessing", false);
        addAdminModel(model, request);
        return "index";
    }

    @GetMapping("/data-processing")
    String dataProcessing(Model model, HttpServletRequest request) {
        projectAccess.requireView(WorkflowState.COMPLETED.directory(), request);
        addAdminModel(model, request);
        return "data_processing";
    }

    @GetMapping("/model-detection")
    String modelDetection(HttpServletRequest request) {
        String host = request.getServerName();
        return "redirect:http://" + host + ":" + modelDetectionPort + "/";
    }

    @GetMapping("/annotate/{main}/{sub}")
    String annotate(@PathVariable String main, @PathVariable String sub, Model model,
                    HttpServletRequest request) {
        projectAccess.requireView(main, request);
        if (!Files.isDirectory(annotations.projectPath(main, sub))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        }
        model.addAttribute("main_folder", main);
        model.addAttribute("subfolder", sub);
        model.addAttribute("workflowState", WorkflowState.fromDirectory(main).map(WorkflowState::id).orElse("legacy"));
        model.addAttribute("readOnly", WorkflowState.fromDirectory(main).isEmpty());
        addAdminModel(model, request);
        return "annotate";
    }

    @GetMapping("/work-logs")
    String workLogs(HttpServletRequest request, HttpServletResponse response) {
        if (!adminAccess.isAllowed(request)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return "invalid_access";
        }
        return "work_logs";
    }

    private void addAdminModel(Model model, HttpServletRequest request) {
        model.addAttribute("isAdmin", adminAccess.isAllowed(request));
        model.addAttribute("adminDeviceTokenEnabled", adminAccess.hasConfiguredDeviceTokens());
    }
}
