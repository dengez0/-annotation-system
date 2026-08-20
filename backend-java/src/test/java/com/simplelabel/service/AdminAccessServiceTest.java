package com.simplelabel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplelabel.config.AppPaths;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAccessServiceTest {
    private static final String TOKEN = "simplelabel-test-device-token-0123456789abcdef";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-17T08:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @TempDir
    Path root;

    @Test
    void emptyRegistryAllowsOnlyLoopbackToBootstrapTheFirstDevice() throws Exception {
        AdminAccessService service = service("");

        MockHttpServletRequest loopbackV4 = new MockHttpServletRequest();
        loopbackV4.setRemoteAddr("127.0.0.1");
        assertThat(service.isAllowed(loopbackV4)).isTrue();
        assertThat(service.deviceName(loopbackV4)).contains(AdminAccessService.LOCAL_BOOTSTRAP_DEVICE);

        MockHttpServletRequest loopbackV6 = new MockHttpServletRequest();
        loopbackV6.setRemoteAddr("::1");
        assertThat(service.isAllowed(loopbackV6)).isTrue();

        MockHttpServletRequest lanClient = new MockHttpServletRequest();
        lanClient.setRemoteAddr("192.168.77.250");
        assertThat(service.isAllowed(lanClient)).isFalse();
    }

    @Test
    void everyAddressRequiresAValidTokenCookie() throws Exception {
        AdminAccessService service = service("admin-laptop=" + hash(TOKEN));
        for (String address : new String[]{"127.0.0.1", "::1", "192.168.77.109", "192.168.77.250"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(address);
            assertThat(service.isAllowed(request)).isFalse();
            request.setCookies(new Cookie(AdminAccessService.ADMIN_COOKIE_NAME, TOKEN));
            assertThat(service.isAllowed(request)).isTrue();
        }
    }

    @Test
    void invalidAndRevokedTokensAreRejectedImmediately() throws Exception {
        AdminTokenStore store = store("admin-laptop=" + hash(TOKEN) + ",backup=" + hash("backup-token-value-0123456789abcdef"));
        AdminAccessService service = new AdminAccessService(store);
        MockHttpServletRequest request = requestWithToken(TOKEN);

        assertThat(service.isAllowed(request)).isTrue();
        assertThat(store.delete("admin-laptop", "backup")).isEqualTo(AdminTokenStore.DeleteResult.DELETED);
        assertThat(service.isAllowed(request)).isFalse();
        assertThat(service.isValidDeviceToken(TOKEN + "-wrong")).isFalse();
    }

    @Test
    void rejectsMalformedOrDuplicateBootstrapConfiguration() {
        assertThatThrownBy(() -> store("missing-label")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store("pc=short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store("pc=" + "a".repeat(64) + ",pc=" + "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void csrfRequiresMatchingDoubleSubmitCookie() throws Exception {
        AdminAccessService service = service("admin-laptop=" + hash(TOKEN));
        MockHttpServletRequest request = requestWithToken(TOKEN);
        request.setCookies(
                new Cookie(AdminAccessService.ADMIN_COOKIE_NAME, TOKEN),
                new Cookie(AdminAccessService.CSRF_COOKIE_NAME, "csrf-value"));

        assertThat(service.isValidCsrf(request, "csrf-value")).isTrue();
        assertThat(service.isValidCsrf(request, "wrong")).isFalse();
    }

    private AdminAccessService service(String bootstrap) throws Exception {
        return new AdminAccessService(store(bootstrap));
    }

    private AdminTokenStore store(String bootstrap) throws Exception {
        AdminTokenStore store = new AdminTokenStore(new AppPaths(root.toString()), new ObjectMapper(), CLOCK, bootstrap);
        store.initialize();
        return store;
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.77.250");
        request.setCookies(new Cookie(AdminAccessService.ADMIN_COOKIE_NAME, token));
        return request;
    }

    private static String hash(String token) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }
}
