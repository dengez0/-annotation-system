package com.simplelabel.controller;

import com.simplelabel.config.ApiException;
import com.simplelabel.service.AdminAccessService;
import com.simplelabel.service.AdminSessionCookieService;
import com.simplelabel.service.AdminTokenStore;
import com.simplelabel.service.WorkLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class AdminTokenController {
    private final AdminTokenStore tokens;
    private final AdminAccessService access;
    private final AdminSessionCookieService cookies;
    private final WorkLogService workLog;

    public AdminTokenController(AdminTokenStore tokens, AdminAccessService access,
                                AdminSessionCookieService cookies, WorkLogService workLog) {
        this.tokens = tokens;
        this.access = access;
        this.cookies = cookies;
        this.workLog = workLog;
    }

    @GetMapping("/admin/tokens")
    String page(Model model, HttpServletRequest request, HttpServletResponse response) {
        String current = requireAdmin(request);
        String csrf = cookies.ensureCsrf(access.csrfToken(request), request, response);
        model.addAttribute("devices", devicePayloads(current));
        model.addAttribute("currentDevice", current);
        model.addAttribute("csrfToken", csrf);
        return "admin_tokens";
    }

    @GetMapping("/api/admin/tokens")
    @ResponseBody
    Map<String, Object> list(HttpServletRequest request) {
        String current = requireAdmin(request);
        return Map.of("devices", devicePayloads(current));
    }

    @PostMapping("/api/admin/tokens")
    @ResponseBody
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                @RequestHeader(value = "X-SimpleLabel-CSRF", required = false) String csrf,
                                                HttpServletRequest request,
                                                HttpServletResponse response) throws IOException {
        boolean localBootstrap = access.isLocalBootstrapRequest(request);
        String current = requireAdmin(request);
        requireCsrf(request, csrf);
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
        AdminTokenStore.CreatedToken created;
        try {
            created = tokens.create(name);
        } catch (AdminTokenStore.DuplicateDeviceException exception) {
            throw new ApiException(HttpStatus.CONFLICT, exception.getMessage());
        }
        workLog.write("ADMIN_TOKEN_ADD", request.getRemoteAddr(), null, null,
                created.name(), 1, null, "actor=" + current);
        if (localBootstrap) {
            cookies.activate(created.token(), request, response);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", created.name());
        result.put("token", created.token());
        result.put("created_at", created.createdAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @DeleteMapping("/api/admin/tokens/{name}")
    @ResponseBody
    ResponseEntity<Void> delete(@PathVariable String name,
                                @RequestHeader(value = "X-SimpleLabel-CSRF", required = false) String csrf,
                                HttpServletRequest request) throws IOException {
        String current = requireAdmin(request);
        requireCsrf(request, csrf);
        AdminTokenStore.DeleteResult result = tokens.delete(name, current);
        switch (result) {
            case NOT_FOUND -> throw new ApiException(HttpStatus.NOT_FOUND, "Administrator device not found");
            case CURRENT_DEVICE -> throw new ApiException(HttpStatus.CONFLICT, "The current device cannot delete itself");
            case LAST_DEVICE -> throw new ApiException(HttpStatus.CONFLICT, "The final administrator device cannot be deleted");
            case DELETED -> workLog.write("ADMIN_TOKEN_DELETE", request.getRemoteAddr(), null, null,
                    name, 1, null, "actor=" + current);
        }
        return ResponseEntity.noContent().build();
    }

    private List<Map<String, Object>> devicePayloads(String current) {
        return tokens.list().stream().map(device -> Map.<String, Object>of(
                "name", device.name(),
                "created_at", device.createdAt(),
                "current", device.name().equals(current))).toList();
    }

    private String requireAdmin(HttpServletRequest request) {
        return access.deviceName(request)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Administrator token required"));
    }

    private void requireCsrf(HttpServletRequest request, String csrf) {
        if (!access.isValidCsrf(request, csrf)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Invalid administrator CSRF token");
        }
    }
}
