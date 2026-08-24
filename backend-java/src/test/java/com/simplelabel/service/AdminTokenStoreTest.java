package com.simplelabel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplelabel.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

class AdminTokenStoreTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-17T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final String TOKEN = "simplelabel-bootstrap-token-0123456789abcdef";

    @TempDir
    Path root;

    @Test
    void importsOncePersistsAndNeverStoresPlaintext() throws Exception {
        AdminTokenStore first = store("admin-pc-1=" + hash(TOKEN));
        AdminTokenStore.CreatedToken created = first.create("admin-pc-2");
        String registry = Files.readString(first.registryPath());

        assertThat(first.authenticate(TOKEN)).contains("admin-pc-1");
        assertThat(first.authenticate(created.token())).contains("admin-pc-2");
        assertThat(registry).doesNotContain(TOKEN).doesNotContain(created.token());
        assertThat(registry).contains("token_ciphertext");
        assertThat(first.list()).extracting(AdminTokenStore.Device::revealable)
                .containsExactly(false, true);
        assertThat(first.reveal(created.name())).isEqualTo(created.token());

        AdminTokenStore reloaded = store("");
        assertThat(reloaded.list()).extracting(AdminTokenStore.Device::name)
                .containsExactly("admin-pc-1", "admin-pc-2");
        assertThat(reloaded.authenticate(created.token())).contains("admin-pc-2");
        assertThat(reloaded.reveal(created.name())).isEqualTo(created.token());
        assertThatThrownBy(() -> reloaded.reveal("admin-pc-1"))
                .isInstanceOf(AdminTokenStore.TokenRevealUnavailableException.class);
    }

    @Test
    void deletionGuardsCurrentAndFinalDeviceAndRevokesOtherDevice() throws Exception {
        AdminTokenStore store = store("admin-pc-1=" + hash(TOKEN));
        assertThat(store.delete("admin-pc-1", "admin-pc-1"))
                .isEqualTo(AdminTokenStore.DeleteResult.CURRENT_DEVICE);

        AdminTokenStore.CreatedToken second = store.create("admin-pc-2");
        assertThat(store.delete("admin-pc-2", "admin-pc-1"))
                .isEqualTo(AdminTokenStore.DeleteResult.DELETED);
        assertThat(store.authenticate(second.token())).isEmpty();
        assertThat(store.delete("admin-pc-1", "different-device"))
                .isEqualTo(AdminTokenStore.DeleteResult.LAST_DEVICE);
    }

    @Test
    void existingRegistryIsAuthoritativeOverBootstrapEnvironment() throws Exception {
        AdminTokenStore first = store("admin-pc-1=" + hash(TOKEN));
        first.create("admin-pc-2");

        AdminTokenStore reloaded = store("resurrected=" + "a".repeat(64));

        assertThat(reloaded.list()).extracting(AdminTokenStore.Device::name)
                .containsExactly("admin-pc-1", "admin-pc-2")
                .doesNotContain("resurrected");
    }

    @Test
    void reissueRevokesPriorSecretAndMakesLegacyEntryRevealable() throws Exception {
        AdminTokenStore store = store("admin-pc-1=" + hash(TOKEN));
        AdminTokenStore.CreatedToken replacement = store.reissue("admin-pc-1");

        assertThat(store.authenticate(TOKEN)).isEmpty();
        assertThat(store.authenticate(replacement.token())).contains("admin-pc-1");
        assertThat(store.reveal("admin-pc-1")).isEqualTo(replacement.token());
        assertThat(store.list()).extracting(AdminTokenStore.Device::revealable).containsExactly(true);

        AdminTokenStore reloaded = store("");
        assertThat(reloaded.authenticate(replacement.token())).contains("admin-pc-1");
        assertThat(reloaded.reveal("admin-pc-1")).isEqualTo(replacement.token());
    }

    private AdminTokenStore store(String bootstrap) throws Exception {
        AdminTokenStore store = new AdminTokenStore(new AppPaths(root.toString()), new ObjectMapper(), CLOCK, bootstrap);
        store.initialize();
        return store;
    }

    private static String hash(String token) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }
}
