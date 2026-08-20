package com.simplelabel.service;

import com.simplelabel.config.AppPaths;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class WorkLogService {
    public static final long MAX_LOG_BYTES = 10L * 1024 * 1024;
    public static final int BACKUP_COUNT = 30;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Path logPath;
    private final Clock clock;

    public WorkLogService(AppPaths paths, Clock clock) {
        this.logPath = paths.logs().resolve("ip_work.log");
        this.clock = clock;
    }

    public synchronized void write(String action, String clientIp, String mainFolder,
                                   String subfolder, String target, Integer count,
                                   Integer boxes, String destination) {
        try {
            rotateIfNeeded();
            List<String> projectParts = new ArrayList<>();
            if (mainFolder != null && !mainFolder.isBlank()) projectParts.add(mainFolder);
            if (subfolder != null && !subfolder.isBlank()) projectParts.add(subfolder);
            String project = projectParts.isEmpty() ? "-" : String.join("/", projectParts);
            List<String> fields = new ArrayList<>();
            fields.add(safe(clientIp));
            fields.add(safe(action));
            fields.add(safe(project));
            fields.add(safe(target));
            if (count != null) fields.add("count=" + safe(count));
            if (boxes != null) fields.add("boxes=" + safe(boxes));
            if (destination != null && !destination.isBlank()) fields.add("destination=" + safe(destination));
            fields.add("success");
            String line = LocalDateTime.now(clock).format(TIMESTAMP) + " | " + String.join(" | ", fields) + System.lineSeparator();
            Files.writeString(logPath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Statistics are best-effort and must not break annotation operations.
        }
    }

    public Path logPath() { return logPath; }

    private void rotateIfNeeded() throws IOException {
        if (!Files.exists(logPath) || Files.size(logPath) < MAX_LOG_BYTES) return;
        Files.deleteIfExists(Path.of(logPath + "." + BACKUP_COUNT));
        for (int index = BACKUP_COUNT - 1; index >= 1; index--) {
            Path source = Path.of(logPath + "." + index);
            if (Files.exists(source)) Files.move(source, Path.of(logPath + "." + (index + 1)));
        }
        Files.move(logPath, Path.of(logPath + ".1"));
    }

    private static String safe(Object value) {
        if (value == null) return "-";
        String text = String.valueOf(value).replace('\r', ' ').replace('\n', ' ')
                .replace('\t', ' ').replace('|', '/').trim();
        if (text.isEmpty()) return "-";
        return text.substring(0, Math.min(500, text.length()));
    }
}
