package com.simplelabel.controller;

import com.simplelabel.config.ApiException;
import com.simplelabel.service.AdminAccessService;
import com.simplelabel.service.WorkLogReadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
public class WorkLogController {
    private final WorkLogReadService logs;
    private final AdminAccessService admin;

    public WorkLogController(WorkLogReadService logs, AdminAccessService admin) {
        this.logs = logs; this.admin = admin;
    }

    @GetMapping("/api/work-logs/overview")
    Map<String, Object> overview(@RequestParam(defaultValue = "today") String range,
                                 @RequestParam(required = false) String date,
                                 @RequestParam(required = false) String ip,
                                 @RequestParam(required = false) String project,
                                 @RequestParam(required = false) String action,
                                 HttpServletRequest request) throws IOException {
        requireAdmin(request); return logs.overview(range, date, ip, project, action);
    }

    @GetMapping("/api/work-logs/ip/{workerIp}")
    Map<String, Object> detail(@PathVariable String workerIp,
                               @RequestParam(defaultValue = "today") String range,
                               @RequestParam(required = false) String date,
                               @RequestParam(required = false) String project,
                               @RequestParam(required = false) String action,
                               HttpServletRequest request) throws IOException {
        requireAdmin(request); return logs.detail(workerIp, range, date, project, action, 200);
    }

    private void requireAdmin(HttpServletRequest request) {
        if (!admin.isAllowed(request.getRemoteAddr())) throw new ApiException(HttpStatus.FORBIDDEN, "Invalid access");
    }
}
