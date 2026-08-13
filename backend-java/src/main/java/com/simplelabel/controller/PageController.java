package com.simplelabel.controller;

import com.simplelabel.config.ApiException;
import com.simplelabel.service.AdminAccessService;
import com.simplelabel.service.AnnotationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.nio.file.Files;

@Controller
public class PageController {
    private final AnnotationService annotations;
    private final AdminAccessService adminAccess;

    public PageController(AnnotationService annotations, AdminAccessService adminAccess) {
        this.annotations = annotations;
        this.adminAccess = adminAccess;
    }

    @GetMapping("/")
    String home() {
        return "home";
    }

    @GetMapping("/annotation")
    String index(Model model) throws IOException {
        model.addAttribute("mainFolders", annotations.listMainFolders());
        model.addAttribute("dataProcessing", false);
        return "index";
    }

    @GetMapping("/data-processing")
    String dataProcessing(Model model) throws IOException {
        model.addAttribute("mainFolders", annotations.listMainFolders());
        model.addAttribute("dataProcessing", true);
        return "index";
    }

    @GetMapping("/model-detection")
    String modelDetection() {
        return "model_detection";
    }

    @GetMapping("/annotate/{main}/{sub}")
    String annotate(@PathVariable String main, @PathVariable String sub, Model model) {
        if (!Files.isDirectory(annotations.projectPath(main, sub))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Project not found");
        }
        model.addAttribute("main_folder", main);
        model.addAttribute("subfolder", sub);
        return "annotate";
    }

    @GetMapping("/work-logs")
    String workLogs(HttpServletRequest request, HttpServletResponse response) {
        if (!adminAccess.isAllowed(request.getRemoteAddr())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return "invalid_access";
        }
        return "work_logs";
    }
}
