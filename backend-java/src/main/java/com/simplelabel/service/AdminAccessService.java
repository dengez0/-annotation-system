package com.simplelabel.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

@Service
public class AdminAccessService {
    public static final String ADMIN_COOKIE_NAME = "simplelabel_admin_device";
    public static final String CSRF_COOKIE_NAME = "simplelabel_admin_csrf";
    public static final String LOCAL_BOOTSTRAP_DEVICE = "local-bootstrap";

    private final AdminTokenStore tokens;

    public AdminAccessService(AdminTokenStore tokens) {
        this.tokens = tokens;
    }

    public boolean isAllowed(HttpServletRequest request) {
        return deviceName(request).isPresent();
    }

    public Optional<String> deviceName(HttpServletRequest request) {
        Optional<String> authenticated = tokens.authenticate(cookieValue(request, ADMIN_COOKIE_NAME));
        if (authenticated.isPresent()) return authenticated;
        return isLocalBootstrapRequest(request) ? Optional.of(LOCAL_BOOTSTRAP_DEVICE) : Optional.empty();
    }

    /**
     * Allows only the host browser to create the very first administrator token.
     * As soon as one device exists, loopback requests require a token like every
     * other client.
     */
    public boolean isLocalBootstrapRequest(HttpServletRequest request) {
        if (tokens.hasDevices() || request == null) return false;
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isValidDeviceToken(String token) {
        return tokens.authenticate(token).isPresent();
    }

    public boolean hasConfiguredDeviceTokens() {
        return tokens.hasDevices();
    }

    public boolean isValidCsrf(HttpServletRequest request, String supplied) {
        String cookie = cookieValue(request, CSRF_COOKIE_NAME);
        if (cookie == null || supplied == null || cookie.length() != supplied.length()) return false;
        return MessageDigest.isEqual(cookie.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    public String csrfToken(HttpServletRequest request) {
        return cookieValue(request, CSRF_COOKIE_NAME);
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        if (request == null || request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
}
