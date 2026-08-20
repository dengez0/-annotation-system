package com.simplelabel.service;

import com.simplelabel.config.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProjectAccessService {
    private final AdminAccessService admin;

    public ProjectAccessService(AdminAccessService admin) { this.admin = admin; }

    public void requireView(String main, HttpServletRequest request) {
        if (WorkflowState.COMPLETED.directory().equals(main) && !admin.isAllowed(request)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Completed tasks require administrator access");
        }
    }

    public void requireModify(String main, HttpServletRequest request) {
        WorkflowState state = WorkflowState.fromDirectory(main)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Legacy projects are read-only"));
        if (state == WorkflowState.COMPLETED && !admin.isAllowed(request)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Completed tasks require administrator access");
        }
    }
}
