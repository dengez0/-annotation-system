package com.simplelabel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplelabel.config.AppPaths;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Service
public class AdminTokenStore {
    private static final int FORMAT_VERSION = 1;
    private static final String TOKEN_CIPHERTEXT_FIELD = "token_ciphertext";
    private static final int AES_KEY_BYTES = 32;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final Pattern DEVICE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern TOKEN_ENTRY = Pattern.compile("[A-Za-z0-9._-]{1,64}=[0-9a-fA-F]{64}");
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path directory;
    private final Path registryPath;
    private final Path encryptionKeyPath;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String bootstrapHashes;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, StoredDevice> devices = new LinkedHashMap<>();
    private SecretKey encryptionKey;

    public AdminTokenStore(AppPaths paths, ObjectMapper mapper, Clock clock,
                           @Value("${simplelabel.admin-token-hashes:}") String bootstrapHashes) {
        this.directory = paths.admin();
        this.registryPath = directory.resolve("admin_tokens.json");
        this.encryptionKeyPath = directory.resolve("admin_token_encryption.key");
        this.mapper = mapper;
        this.clock = clock;
        this.bootstrapHashes = bootstrapHashes == null ? "" : bootstrapHashes.trim();
    }

    @PostConstruct
    synchronized void initialize() throws IOException {
        Files.createDirectories(directory);
        setPermissions(directory, DIRECTORY_PERMISSIONS);
        if (Files.isRegularFile(registryPath)) {
            load();
        } else if (!bootstrapHashes.isBlank()) {
            importBootstrap();
            persist();
        }
    }

    public synchronized boolean hasDevices() {
        return !devices.isEmpty();
    }

    public synchronized Optional<String> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        byte[] candidate = sha256(token.trim());
        String matched = null;
        for (StoredDevice device : devices.values()) {
            if (MessageDigest.isEqual(device.hash(), candidate)) matched = device.name();
        }
        return Optional.ofNullable(matched);
    }

    public synchronized List<Device> list() {
        return devices.values().stream().map(device -> new Device(device.name(), device.createdAt(),
                device.tokenCiphertext() != null && !device.tokenCiphertext().isBlank())).toList();
    }

    public synchronized boolean contains(String name) {
        return devices.containsKey(name);
    }

    public synchronized CreatedToken create(String name) throws IOException {
        validateDeviceName(name);
        if (devices.containsKey(name)) throw new DuplicateDeviceException(name);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = "slt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String createdAt = OffsetDateTime.now(clock).toString();
        devices.put(name, new StoredDevice(name, sha256(token), createdAt, encrypt(token)));
        try {
            persist();
        } catch (IOException exception) {
            devices.remove(name);
            throw exception;
        }
        return new CreatedToken(name, token, createdAt);
    }

    /**
     * Replaces a device secret without changing the device identity.  This is also
     * the safe migration path for legacy entries whose original plaintext was
     * deliberately never stored.
     */
    public synchronized CreatedToken reissue(String name) throws IOException {
        StoredDevice previous = devices.get(name);
        if (previous == null) throw new UnknownDeviceException(name);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = "slt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        StoredDevice replacement = new StoredDevice(name, sha256(token), previous.createdAt(), encrypt(token));
        devices.put(name, replacement);
        try {
            persist();
        } catch (IOException exception) {
            devices.put(name, previous);
            throw exception;
        }
        return new CreatedToken(name, token, previous.createdAt());
    }

    /**
     * Legacy tokens created before encrypted storage was introduced remain deliberately
     * non-recoverable because their plaintext was never saved.
     */
    public synchronized String reveal(String name) throws IOException {
        StoredDevice device = devices.get(name);
        if (device == null) throw new UnknownDeviceException(name);
        if (device.tokenCiphertext() == null || device.tokenCiphertext().isBlank()) {
            throw new TokenRevealUnavailableException(name);
        }
        String token = decrypt(device.tokenCiphertext());
        if (!MessageDigest.isEqual(device.hash(), sha256(token))) {
            throw new IOException("Stored administrator token does not match its verification hash");
        }
        return token;
    }

    public synchronized DeleteResult delete(String name, String currentDevice) throws IOException {
        if (!devices.containsKey(name)) return DeleteResult.NOT_FOUND;
        if (name.equals(currentDevice)) return DeleteResult.CURRENT_DEVICE;
        if (devices.size() == 1) return DeleteResult.LAST_DEVICE;
        StoredDevice removed = devices.remove(name);
        try {
            persist();
        } catch (IOException exception) {
            devices.put(name, removed);
            throw exception;
        }
        return DeleteResult.DELETED;
    }

    Path registryPath() {
        return registryPath;
    }

    private void importBootstrap() {
        for (String item : bootstrapHashes.split(",", -1)) {
            String value = item.trim();
            if (!TOKEN_ENTRY.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "SIMPLELABEL_ADMIN_TOKEN_HASHES entries must use device-name=64-character-sha256");
            }
            int separator = value.indexOf('=');
            String name = value.substring(0, separator);
            if (devices.containsKey(name)) throw new IllegalArgumentException("Duplicate administrator device name: " + name);
            devices.put(name, new StoredDevice(name,
                    HexFormat.of().parseHex(value.substring(separator + 1)), OffsetDateTime.now(clock).toString(), null));
        }
    }

    private void load() throws IOException {
        JsonNode root = mapper.readTree(registryPath.toFile());
        if (root == null || root.path("version").asInt(-1) != FORMAT_VERSION || !root.path("devices").isArray()) {
            throw new IOException("Invalid administrator token registry format");
        }
        Map<String, StoredDevice> loaded = new LinkedHashMap<>();
        for (JsonNode node : root.path("devices")) {
            String name = node.path("name").asText("");
            String hash = node.path("sha256").asText("");
            String createdAt = node.path("created_at").asText("");
            String ciphertext = node.path(TOKEN_CIPHERTEXT_FIELD).asText("");
            try {
                validateDeviceName(name);
                if (!HASH.matcher(hash).matches()) throw new IllegalArgumentException("Invalid administrator token hash");
                OffsetDateTime.parse(createdAt);
                if (!ciphertext.isBlank()) Base64.getUrlDecoder().decode(ciphertext);
            } catch (RuntimeException exception) {
                throw new IOException("Invalid administrator token registry entry", exception);
            }
            if (loaded.putIfAbsent(name, new StoredDevice(name, HexFormat.of().parseHex(hash), createdAt,
                    ciphertext.isBlank() ? null : ciphertext)) != null) {
                throw new IOException("Duplicate administrator device in token registry: " + name);
            }
        }
        devices.clear();
        devices.putAll(loaded);
        setPermissions(registryPath, FILE_PERMISSIONS);
    }

    private void persist() throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", FORMAT_VERSION);
        ArrayNode entries = root.putArray("devices");
        for (StoredDevice device : devices.values()) {
            ObjectNode entry = entries.addObject();
            entry.put("name", device.name());
            entry.put("sha256", HexFormat.of().formatHex(device.hash()));
            entry.put("created_at", device.createdAt());
            if (device.tokenCiphertext() != null && !device.tokenCiphertext().isBlank()) {
                entry.put(TOKEN_CIPHERTEXT_FIELD, device.tokenCiphertext());
            }
        }
        Path temporary = Files.createTempFile(directory, ".admin_tokens-", ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            setPermissions(temporary, FILE_PERMISSIONS);
            try {
                Files.move(temporary, registryPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, registryPath, StandardCopyOption.REPLACE_EXISTING);
            }
            setPermissions(registryPath, FILE_PERMISSIONS);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateDeviceName(String name) {
        if (name == null || !DEVICE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Device name must contain only letters, digits, dot, underscore, or hyphen (1-64 characters)");
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String encrypt(String token) throws IOException {
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (Exception exception) {
            throw new IOException("Unable to encrypt administrator token", exception);
        }
    }

    private String decrypt(String ciphertext) throws IOException {
        try {
            byte[] payload = Base64.getUrlDecoder().decode(ciphertext);
            if (payload.length <= GCM_NONCE_BYTES) throw new IOException("Invalid encrypted administrator token");
            byte[] nonce = java.util.Arrays.copyOfRange(payload, 0, GCM_NONCE_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(payload, GCM_NONCE_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Unable to decrypt administrator token", exception);
        }
    }

    private SecretKey encryptionKey() throws IOException {
        if (encryptionKey != null) return encryptionKey;
        if (!Files.isRegularFile(encryptionKeyPath)) {
            byte[] generated = new byte[AES_KEY_BYTES];
            random.nextBytes(generated);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(generated);
            try {
                Files.writeString(encryptionKeyPath, encoded, StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                setPermissions(encryptionKeyPath, FILE_PERMISSIONS);
            } catch (FileAlreadyExistsException ignored) {
                // Another application instance initialized this registry first.
            }
        }
        try {
            byte[] keyBytes = Base64.getUrlDecoder().decode(
                    Files.readString(encryptionKeyPath, StandardCharsets.US_ASCII).trim());
            if (keyBytes.length != AES_KEY_BYTES) throw new IOException("Invalid administrator token encryption key");
            setPermissions(encryptionKeyPath, FILE_PERMISSIONS);
            encryptionKey = new SecretKeySpec(keyBytes, "AES");
            return encryptionKey;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid administrator token encryption key", exception);
        }
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows and non-POSIX development filesystems do not expose POSIX permissions.
        }
    }

    private record StoredDevice(String name, byte[] hash, String createdAt, String tokenCiphertext) { }
    public record Device(String name, String createdAt, boolean revealable) { }
    public record CreatedToken(String name, String token, String createdAt) { }
    public enum DeleteResult { DELETED, NOT_FOUND, CURRENT_DEVICE, LAST_DEVICE }

    public static final class DuplicateDeviceException extends IllegalStateException {
        public DuplicateDeviceException(String name) { super("Administrator device already exists: " + name); }
    }

    public static final class UnknownDeviceException extends IllegalArgumentException {
        public UnknownDeviceException(String name) { super("Administrator device not found: " + name); }
    }

    public static final class TokenRevealUnavailableException extends IllegalStateException {
        public TokenRevealUnavailableException(String name) {
            super("Administrator token for " + name
                    + " was created before encrypted storage and cannot be recovered. Reissue it to reveal it later.");
        }
    }
}
