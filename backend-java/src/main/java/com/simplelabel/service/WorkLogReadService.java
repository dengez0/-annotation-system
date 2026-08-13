package com.simplelabel.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Stream;

@Service
public class WorkLogReadService {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> RANGES = Set.of("today", "yesterday", "7d", "date", "all");
    private final WorkLogService workLog;

    public WorkLogReadService(WorkLogService workLog) { this.workLog = workLog; }

    public Map<String, Object> overview(String range, String date, String ip, String project, String action) throws IOException {
        List<Event> events = filter(range, date, ip, project, action, LocalDateTime.now());
        Map<String, IpSummary> byIp = new HashMap<>();
        Set<ImageKey> annotatedImages = new HashSet<>();
        int movedImages = 0;
        for (Event event : events) {
            IpSummary summary = byIp.computeIfAbsent(event.ip, ignored -> new IpSummary());
            ImageKey key = event.imageKey();
            if (key != null) {
                annotatedImages.add(key); summary.annotatedImages.add(key); summary.saves++; summary.boxes += event.boxes;
            }
            int moved = event.movedImageCount();
            if (moved > 0) { movedImages += moved; summary.movedImages += moved; }
            if (summary.lastActivity == null || event.timestamp.isAfter(summary.lastActivity)) summary.lastActivity = event.timestamp;
        }
        List<Map<String, Object>> ranking = new ArrayList<>();
        byIp.forEach((workerIp, value) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ip", workerIp); row.put("images", value.annotatedImages.size() + value.movedImages);
            row.put("annotated_images", value.annotatedImages.size()); row.put("moved_images", value.movedImages);
            row.put("saves", value.saves);
            row.put("boxes", value.boxes); row.put("last_activity", value.lastActivity.format(FORMAT)); ranking.add(row);
        });
        ranking.sort(Comparator
                .comparingInt((Map<String, Object> row) -> number(row, "images"))
                .thenComparingInt(row -> number(row, "boxes"))
                .thenComparingInt(row -> number(row, "saves"))
                .thenComparing(row -> String.valueOf(row.get("last_activity"))).reversed());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("active_ips", byIp.size()); summary.put("images", annotatedImages.size() + movedImages);
        summary.put("annotated_images", annotatedImages.size()); summary.put("moved_images", movedImages);
        summary.put("saves", ranking.stream().mapToInt(row -> number(row, "saves")).sum());
        summary.put("boxes", ranking.stream().mapToInt(row -> number(row, "boxes")).sum());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary); result.put("ranking", ranking); result.put("filters", filterOptions(range, date));
        return result;
    }

    public Map<String, Object> detail(String workerIp, String range, String date,
                                      String project, String action, int limit) throws IOException {
        List<Event> events = filter(range, date, workerIp, project, action, LocalDateTime.now());
        Map<String, ProjectSummary> projects = new HashMap<>();
        for (Event event : events) {
            ProjectSummary summary = projects.computeIfAbsent(event.project, ignored -> new ProjectSummary());
            summary.actions++;
            ImageKey key = event.imageKey();
            if (key != null) { summary.annotatedImages.add(key); summary.saves++; summary.boxes += event.boxes; }
            summary.movedImages += event.movedImageCount();
        }
        List<Map<String, Object>> projectRows = new ArrayList<>();
        projects.forEach((name, value) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project", name); row.put("images", value.annotatedImages.size() + value.movedImages);
            row.put("annotated_images", value.annotatedImages.size()); row.put("moved_images", value.movedImages);
            row.put("saves", value.saves);
            row.put("boxes", value.boxes); row.put("actions", value.actions); projectRows.add(row);
        });
        projectRows.sort(Comparator
                .comparingInt((Map<String, Object> row) -> number(row, "images"))
                .thenComparingInt(row -> number(row, "boxes"))
                .thenComparingInt(row -> number(row, "saves"))
                .thenComparingInt(row -> number(row, "actions")).reversed());
        List<Event> newest = events.stream().sorted(Comparator.comparing(Event::timestamp).reversed()).toList();
        List<Map<String, Object>> payloads = newest.stream().limit(limit).map(Event::payload).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ip", workerIp); result.put("event_count", newest.size());
        result.put("events", payloads); result.put("projects", projectRows);
        return result;
    }

    List<Event> filter(String range, String date, String ip, String project,
                       String action, LocalDateTime now) throws IOException {
        Bounds bounds = bounds(range, date, now);
        return load().stream().filter(event -> bounds.start == null || !event.timestamp.isBefore(bounds.start))
                .filter(event -> bounds.end == null || event.timestamp.isBefore(bounds.end))
                .filter(event -> ip == null || ip.isBlank() || event.ip.equals(ip))
                .filter(event -> project == null || project.isBlank() || event.project.equals(project))
                .filter(event -> action == null || action.isBlank() || event.action.equals(action)).toList();
    }

    private Map<String, Object> filterOptions(String range, String date) throws IOException {
        List<Event> events = filter(range, date, null, null, null, LocalDateTime.now());
        Set<String> ips = new TreeSet<>(), projects = new TreeSet<>(), actions = new TreeSet<>();
        for (Event event : events) {
            ips.add(event.ip); if (!event.project.equals("-")) projects.add(event.project); actions.add(event.action);
        }
        return Map.of("ips", ips, "projects", projects, "actions", actions);
    }

    private List<Event> load() throws IOException {
        Path base = workLog.logPath();
        List<Path> paths;
        try (Stream<Path> stream = Files.list(base.getParent())) {
            paths = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(base.getFileName().toString()))
                    .sorted().toList();
        }
        List<Event> result = new ArrayList<>();
        for (Path path : paths) {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                Event event = parse(line); if (event != null) result.add(event);
            }
        }
        result.sort(Comparator.comparing(Event::timestamp));
        return result;
    }

    private static Event parse(String line) {
        String[] parts = line.strip().split(" \\| ");
        if (parts.length < 6) return null;
        try {
            LocalDateTime timestamp = LocalDateTime.parse(parts[0].trim(), FORMAT);
            int count = 0, boxes = 0; String destination = null;
            for (int index = 5; index < parts.length - 1; index++) {
                String field = parts[index].trim(); int equals = field.indexOf('=');
                if (equals < 1) continue;
                String key = field.substring(0, equals), value = field.substring(equals + 1);
                try {
                    if (key.equals("count")) count = Integer.parseInt(value);
                    else if (key.equals("boxes")) boxes = Integer.parseInt(value);
                    else if (key.equals("destination")) destination = value;
                } catch (NumberFormatException ignored) { }
            }
            return new Event(timestamp, parts[1].trim(), parts[2].trim(), parts[3].trim(),
                    parts[4].trim(), count, boxes, destination);
        } catch (DateTimeParseException ignored) { return null; }
    }

    private static Bounds bounds(String range, String date, LocalDateTime now) {
        if (!RANGES.contains(range)) throw new IllegalArgumentException("Invalid time range");
        LocalDate today = now.toLocalDate(); LocalDateTime todayStart = today.atStartOfDay();
        return switch (range) {
            case "today" -> new Bounds(todayStart, null);
            case "yesterday" -> new Bounds(today.minusDays(1).atStartOfDay(), todayStart);
            case "7d" -> new Bounds(today.minusDays(6).atStartOfDay(), null);
            case "date" -> {
                if (date == null || date.isBlank()) throw new IllegalArgumentException("Date is required");
                LocalDate selected;
                try { selected = LocalDate.parse(date); }
                catch (DateTimeParseException exception) { throw new IllegalArgumentException("Invalid date"); }
                if (selected.isBefore(today.minusDays(6)) || selected.isAfter(today)) {
                    throw new IllegalArgumentException("Date must be within the last 7 days");
                }
                yield new Bounds(selected.atStartOfDay(), selected.plusDays(1).atStartOfDay());
            }
            default -> new Bounds(null, null);
        };
    }

    private static int number(Map<String, Object> row, String key) { return ((Number) row.get(key)).intValue(); }
    private record Bounds(LocalDateTime start, LocalDateTime end) { }
    private record ImageKey(String project, String target) { }
    private static final class IpSummary { final Set<ImageKey> annotatedImages = new HashSet<>(); int movedImages, saves, boxes; LocalDateTime lastActivity; }
    private static final class ProjectSummary { final Set<ImageKey> annotatedImages = new HashSet<>(); int movedImages, saves, boxes, actions; }

    record Event(LocalDateTime timestamp, String ip, String action, String project,
                 String target, int count, int boxes, String destination) {
        ImageKey imageKey() {
            return action.equals("SAVE_ANNOTATION") && !target.isBlank() && !target.equals("-")
                    ? new ImageKey(project, target) : null;
        }
        int movedImageCount() { return action.equals("MOVE_FILES") ? Math.max(count, 0) : 0; }
        Map<String, Object> payload() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timestamp", timestamp.format(FORMAT)); result.put("ip", ip); result.put("action", action);
            result.put("project", project); result.put("target", target); result.put("count", count);
            result.put("boxes", boxes); result.put("destination", destination); return result;
        }
    }
}
