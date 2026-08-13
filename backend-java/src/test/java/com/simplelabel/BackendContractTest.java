package com.simplelabel;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "simplelabel.root=target/test-runtime",
        "simplelabel.admin-ip=127.0.0.1"
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
    }

    @Test
    void corePagesAndProjectApiRemainAvailable() throws Exception {
        mvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
        mvc.perform(get("/"))
                .andExpect(status().isOk());
        mvc.perform(get("/annotation"))
                .andExpect(status().isOk());
        mvc.perform(get("/data-processing"))
                .andExpect(status().isOk());
        mvc.perform(get("/model-detection"))
                .andExpect(status().isOk());
        mvc.perform(get("/static/smoke.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
        mvc.perform(get("/work-logs").with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                }))
                .andExpect(status().isOk());
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
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }
}
