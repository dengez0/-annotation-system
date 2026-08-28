package com.simplelabel;

import com.simplelabel.service.AdminAccessService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "simplelabel.root=target/test-runtime",
        "simplelabel.admin-dir=admin-contract",
        "simplelabel.yolo-worker-url=http://127.0.0.1:1",
        "simplelabel.admin-token-hashes=test-laptop=89bd1b174e1150d9c838c13d81e348d203517a1416b2f37b7f22642472cfbaf8"
})
@AutoConfigureMockMvc
class BackendContractTest {
    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void createExternalStaticFixture() throws Exception {
        Path staticDirectory = Path.of("target", "test-runtime", "static");
        Files.createDirectories(staticDirectory);
        Files.writeString(staticDirectory.resolve("smoke.css"), "body { color: #fff; }");
        Path annotating = Path.of("target", "test-runtime", "data", "标注中", "template-task");
        Path completed = Path.of("target", "test-runtime", "data", "已完成", "private-task");
        Files.createDirectories(annotating);
        Files.createDirectories(completed);
        Files.writeString(annotating.resolve("frame.jpg"), "image");
        Files.writeString(completed.resolve("frame.jpg"), "image");
    }

    @Test
    void corePagesAndProjectApiRemainAvailable() throws Exception {
        mvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("数据标注")));
        mvc.perform(get("/annotation"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("未标注")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("标注中")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("待检查")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("data-folder=\"已完成\""))));
        mvc.perform(get("/annotate/标注中/template-task"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Done → 待检查")));
        mvc.perform(get("/data-processing")
                        .cookie(new Cookie(AdminAccessService.ADMIN_COOKIE_NAME,
                                "simplelabel-test-device-token-0123456789abcdef")))
                .andExpect(status().isOk());
        mvc.perform(get("/model-detection").with(request -> {
                    request.setServerPort(29090);
                    return request;
                }))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:8000/?home_port=29090"));
        mvc.perform(get("/static/smoke.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
        mvc.perform(get("/work-logs").with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                }))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportsAndLogsRejectRequestsWithoutAdministratorToken() throws Exception {
        mvc.perform(get("/annotate/已完成/private-task"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/annotate/已完成/private-task")
                        .cookie(new Cookie(AdminAccessService.ADMIN_COOKIE_NAME,
                                "simplelabel-test-device-token-0123456789abcdef")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/subfolders/已完成"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/subfolders/已完成")
                        .cookie(new Cookie(AdminAccessService.ADMIN_COOKIE_NAME,
                                "simplelabel-test-device-token-0123456789abcdef")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/export/main/sub").with(request -> {
                    request.setRemoteAddr("192.168.77.110");
                    return request;
                }))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/export_yolo/main/sub")
                        .contentType("application/json").content("{\"labels\":[\"person\"]}")
                        .with(request -> {
                            request.setRemoteAddr("192.168.77.110");
                            return request;
                        }))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/work-logs/overview").with(request -> {
                    request.setRemoteAddr("192.168.77.110");
                    return request;
                }))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/data-processing/source/annotation%20files/sub/download").with(request -> {
                    request.setRemoteAddr("192.168.77.110");
                    return request;
                }))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/data-processing/source/annotation%20files/sub").with(request -> {
                    request.setRemoteAddr("192.168.77.110");
                    return request;
                }))
                .andExpect(status().isForbidden());
    }

    @Test
    void deviceTokenActivationGrantsAdministratorAccessAcrossAddresses() throws Exception {
        String token = "simplelabel-test-device-token-0123456789abcdef";
        mvc.perform(post("/admin/activate")
                        .param("token", token)
                        .with(request -> {
                            request.setRemoteAddr("192.168.77.250");
                            return request;
                        }))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/tokens"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString(AdminAccessService.ADMIN_COOKIE_NAME + "="),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"))));

        mvc.perform(get("/work-logs")
                        .cookie(new Cookie(AdminAccessService.ADMIN_COOKIE_NAME, token))
                        .with(request -> {
                            request.setRemoteAddr("192.168.77.251");
                            return request;
                        }))
                .andExpect(status().isOk());
    }

    @Test
    void tokenManagementRequiresCsrfAndCanCreateAndDeleteAnotherDevice() throws Exception {
        String token = "simplelabel-test-device-token-0123456789abcdef";
        Cookie admin = new Cookie(AdminAccessService.ADMIN_COOKIE_NAME, token);
        Cookie csrf = new Cookie(AdminAccessService.CSRF_COOKIE_NAME, "csrf-test-value");

        mvc.perform(get("/admin/tokens").cookie(admin, csrf))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("管理员令牌管理")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/static/css/style.css?v=8")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"button button-primary\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/static/css/base.css"))));

        mvc.perform(post("/api/admin/tokens")
                        .cookie(admin, csrf)
                        .contentType("application/json")
                        .content("{\"name\":\"contract-device\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/tokens")
                        .cookie(admin, csrf)
                        .header("X-SimpleLabel-CSRF", "csrf-test-value")
                        .contentType("application/json")
                        .content("{\"name\":\"contract-device\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("contract-device"))
                .andExpect(jsonPath("$.token", org.hamcrest.Matchers.startsWith("slt_")));

        mvc.perform(get("/api/admin/tokens/contract-device/secret")
                        .cookie(admin, csrf))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/tokens/contract-device/secret")
                        .cookie(admin, csrf)
                        .header("X-SimpleLabel-CSRF", "csrf-test-value"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.name").value("contract-device"))
                .andExpect(jsonPath("$.token", org.hamcrest.Matchers.startsWith("slt_")));

        mvc.perform(post("/api/admin/tokens/contract-device/reissue")
                        .cookie(admin, csrf)
                        .header("X-SimpleLabel-CSRF", "csrf-test-value"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.name").value("contract-device"))
                .andExpect(jsonPath("$.token", org.hamcrest.Matchers.startsWith("slt_")));

        mvc.perform(delete("/api/admin/tokens/contract-device")
                        .cookie(admin, csrf)
                        .header("X-SimpleLabel-CSRF", "csrf-test-value"))
                .andExpect(status().isNoContent());
    }

    @Test
    void llmAndSam3AutoLabelEndpointsDoNotExist() throws Exception {
        mvc.perform(post("/api/auto_label_llm/a/b")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/auto_label_sam3/a/b")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthReportsDegradedWhenWorkerIsUnavailable() throws Exception {
        mvc.perform(get("/internal/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.time_zone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.current_time").value(org.hamcrest.Matchers.endsWith("+08:00")));
    }
}
