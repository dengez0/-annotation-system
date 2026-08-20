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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

@Service
public class AdminTokenStore {
    private static final int FORMAT_VERSION = 1;
    private static final Pattern DEVICE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern TOKEN_ENTRY = Pattern.compile("[A-Za-z0-9._-]{1,64}=[0-9a-fA-F]{64}");
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path directory;
    private final Path registryPath;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String bootstrapHashes;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, StoredDevice> devices = new LinkedHashMap<>();

    public AdminTokenStore(AppPaths paths, ObjectMapper mapper, Clock clock,
                           @Value("${simplelabel.admin-token-hashes:}") String bootstrapHashes) {
        this.directory = paths.admin();
        this.registryPath = directory.resolve("admin_tokens.json");
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
        return devices.values().stream().map(device -> new Device(device.name(), device.createdAt())).toList();
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
        devices.put(name, new StoredDevice(name, sha256(token), createdAt));
        try {
            persist();
        } catch (IOException exception) {
            devices.remove(name);
            throw exception;
        }
        return new CreatedToken(name, token, createdAt);
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
                    HexFormat.of().parseHex(value.substring(separator + 1)), OffsetDateTime.now(clock).toString()));
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
            try {
                validateDeviceName(name);
                if (!HASH.matcher(hash).matches()) throw new IllegalArgumentException("Invalid administrator token hash");
                OffsetDateTime.parse(createdAt);
            } catch (RuntimeException exception) {
                throw new IOException("Invalid administrator token registry entry", exception);
            }
            if (loaded.putIfAbsent(name, new StoredDevice(name, HexFormat.of().parseHex(hash), createdAt)) != null) {
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

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows and non-POSIX development filesystems do not expose POSIX permissions.
        }
    }

    private record StoredDevice(String name, byte[] hash, String createdAt) { }
    public record Device(String name, String createdAt) { }
    public record CreatedToken(String name, String token, String createdAt) { }
    public enum DeleteResult { DELETED, NOT_FOUND, CURRENT_DEVICE, LAST_DEVICE }

    public static final class DuplicateDeviceException extends IllegalStateException {
        public DuplicateDeviceException(String name) { super("Administrator device already exists: " + name); }
    }
}
