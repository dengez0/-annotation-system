package com.simplelabel.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
public class AdminSessionCookieService {
    private final Duration cookieLifetime;
    private final boolean secureCookie;
    private final SecureRandom random = new SecureRandom();

    public AdminSessionCookieService(@Value("${simplelabel.admin-cookie-days:365}") int cookieDays,
                                     @Value("${simplelabel.admin-cookie-secure:false}") boolean secureCookie) {
        if (cookieDays < 1 || cookieDays > 3650) {
            throw new IllegalArgumentException("SIMPLELABEL_ADMIN_COOKIE_DAYS must be between 1 and 3650");
        }
        this.cookieLifetime = Duration.ofDays(cookieDays);
        this.secureCookie = secureCookie;
    }

    public String activate(String token, HttpServletRequest request, HttpServletResponse response) {
        String csrf = randomToken();
        add(response, cookie(AdminAccessService.ADMIN_COOKIE_NAME, token, true, request, cookieLifetime));
        add(response, cookie(AdminAccessService.CSRF_COOKIE_NAME, csrf, false, request, cookieLifetime));
        return csrf;
    }

    public void clear(HttpServletRequest request, HttpServletResponse response) {
        add(response, cookie(AdminAccessService.ADMIN_COOKIE_NAME, "", true, request, Duration.ZERO));
        add(response, cookie(AdminAccessService.CSRF_COOKIE_NAME, "", false, request, Duration.ZERO));
    }

    public String ensureCsrf(String existing, HttpServletRequest request, HttpServletResponse response) {
        if (existing != null && !existing.isBlank()) return existing;
        String csrf = randomToken();
        add(response, cookie(AdminAccessService.CSRF_COOKIE_NAME, csrf, false, request, cookieLifetime));
        return csrf;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseCookie cookie(String name, String value, boolean httpOnly,
                                  HttpServletRequest request, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(secureCookie || request.isSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private static void add(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
