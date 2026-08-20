package com.simplelabel.controller;

import com.simplelabel.service.AdminAccessService;
import com.simplelabel.service.AdminSessionCookieService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminDeviceController {
    private final AdminAccessService adminAccess;
    private final AdminSessionCookieService cookies;

    public AdminDeviceController(AdminAccessService adminAccess, AdminSessionCookieService cookies) {
        this.adminAccess = adminAccess;
        this.cookies = cookies;
    }

    @GetMapping("/admin/activate")
    String activationPage(Model model, HttpServletRequest request, HttpServletResponse response) {
        if (!adminAccess.hasConfiguredDeviceTokens()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return "invalid_access";
        }
        model.addAttribute("alreadyActive", adminAccess.isAllowed(request));
        return "admin_activate";
    }

    @PostMapping("/admin/activate")
    String activate(@RequestParam String token, Model model,
                    HttpServletRequest request, HttpServletResponse response) {
        if (!adminAccess.hasConfiguredDeviceTokens()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return "invalid_access";
        }
        String normalized = token == null ? "" : token.trim();
        if (!adminAccess.isValidDeviceToken(normalized)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            model.addAttribute("error", "设备令牌无效或已被撤销。");
            model.addAttribute("alreadyActive", adminAccess.isAllowed(request));
            return "admin_activate";
        }
        cookies.activate(normalized, request, response);
        return "redirect:/admin/tokens";
    }

    @PostMapping("/admin/deactivate")
    String deactivate(HttpServletRequest request, HttpServletResponse response) {
        cookies.clear(request, response);
        return "redirect:/annotation";
    }
}
