package com.simplelabel.service;

import com.simplelabel.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkLogReadServiceTest {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path root;

    @Test
    void todayStartsAtMidnightAndYesterdayEndsAtTodayMidnight() throws Exception {
        WorkLogReadService reader = reader();
        LocalDateTime now = LocalDateTime.of(2026, 8, 11, 14, 0);
        writeLines(
                line(LocalDateTime.of(2026, 8, 10, 23, 59), "10.0.0.1", "old.jpg", 1),
                line(LocalDateTime.of(2026, 8, 11, 0, 0), "10.0.0.1", "today.jpg", 2)
        );

        List<WorkLogReadService.Event> today = reader.filter("today", null, null, null, null, now);
        List<WorkLogReadService.Event> yesterday = reader.filter("yesterday", null, null, null, null, now);

        assertThat(today).extracting(WorkLogReadService.Event::target).containsExactly("today.jpg");
        assertThat(yesterday).extracting(WorkLogReadService.Event::target).containsExactly("old.jpg");
    }

    @Test
    void overviewUsesOnlyTheLastSavedBoxCountForEachImage() throws Exception {
        WorkLogReadService reader = reader();
        LocalDateTime now = LocalDateTime.now();
        writeLines(
                line(now.minusMinutes(3), "10.0.0.1", "same.jpg", 2),
                line(now.minusMinutes(2), "10.0.0.1", "same.jpg", 3),
                line(now.minusMinutes(1), "10.0.0.1", "other.jpg", 1)
        );

        Map<String, Object> result = reader.overview("today", null, null, null, null);
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");

        assertThat(summary.get("images")).isEqualTo(2);
        assertThat(summary.get("saves")).isEqualTo(3);
        assertThat(summary.get("boxes")).isEqualTo(4);
    }

    @Test
    void overviewIncludesTheInitialSaveWhenItIsTheOnlySaveForAnImage() throws Exception {
        WorkLogReadService reader = reader();
        LocalDateTime now = LocalDateTime.now();
        writeLines(
                annotationLine(now.minusMinutes(2), "10.0.0.1", "START_ANNOTATION", "new.jpg", 6),
                line(now.minusMinutes(1), "10.0.0.1", "updated.jpg", 2)
        );

        Map<String, Object> result = reader.overview("today", null, null, null, null);
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");

        assertThat(summary.get("images")).isEqualTo(2);
        assertThat(summary.get("boxes")).isEqualTo(8);
    }

    @Test
    void overviewAssignsFinalBoxesToTheLastSaverInsteadOfAccumulatingHistory() throws Exception {
        WorkLogReadService reader = reader();
        LocalDateTime now = LocalDateTime.now();
        writeLines(
                line(now.minusMinutes(3), "10.0.0.1", "shared.jpg", 20),
                line(now.minusMinutes(2), "10.0.0.1", "only-first.jpg", 7),
                line(now.minusMinutes(1), "10.0.0.2", "shared.jpg", 25)
        );

        Map<String, Object> all = reader.overview("today", null, null, null, null);
        Map<?, ?> allSummary = (Map<?, ?>) all.get("summary");
        List<Map<?, ?>> ranking = (List<Map<?, ?>>) all.get("ranking");
        Map<?, ?> first = ranking.stream().filter(row -> row.get("ip").equals("10.0.0.1")).findFirst().orElseThrow();
        Map<?, ?> second = ranking.stream().filter(row -> row.get("ip").equals("10.0.0.2")).findFirst().orElseThrow();

        assertThat(allSummary.get("boxes")).isEqualTo(32);
        assertThat(first.get("boxes")).isEqualTo(7);
        assertThat(first.get("saves")).isEqualTo(2);
        assertThat(second.get("boxes")).isEqualTo(25);

        Map<String, Object> firstOnly = reader.overview("today", null, "10.0.0.1", null, null);
        Map<?, ?> firstOnlySummary = (Map<?, ?>) firstOnly.get("summary");
        assertThat(firstOnlySummary.get("boxes")).isEqualTo(7);
    }

    @Test
    void overviewAddsOnlyConfirmedMoveCountsToAnnotationImages() throws Exception {
        WorkLogReadService reader = reader();
        LocalDateTime now = LocalDateTime.now();
        writeLines(
                line(now.minusMinutes(3), "10.0.0.1", "same.jpg", 2),
                line(now.minusMinutes(2), "10.0.0.1", "other.jpg", 1),
                moveLine(now.minusMinutes(1), "10.0.0.1", "MOVE_FILES", 3),
                moveLine(now, "10.0.0.1", "RESTORE_FILES", 5)
        );

        Map<String, Object> result = reader.overview("today", null, null, null, null);
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        Map<?, ?> worker = ((List<Map<?, ?>>) result.get("ranking")).getFirst();

        assertThat(summary.get("annotated_images")).isEqualTo(2);
        assertThat(summary.get("moved_images")).isEqualTo(3);
        assertThat(summary.get("images")).isEqualTo(5);
        assertThat(worker.get("annotated_images")).isEqualTo(2);
        assertThat(worker.get("moved_images")).isEqualTo(3);
        assertThat(worker.get("images")).isEqualTo(5);
    }

    @Test
    void selectedDateMustStayWithinSevenCalendarDays() throws Exception {
        WorkLogReadService reader = reader();
        assertThatThrownBy(() -> reader.filter("date", "2026-08-04", null, null, null,
                LocalDateTime.of(2026, 8, 11, 12, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last 7 days");
    }

    @Test
    void writesConfiguredBusinessTimeWhenInstantIsUtc() throws Exception {
        Files.createDirectories(root.resolve("logs"));
        Clock clock = Clock.fixed(Instant.parse("2026-08-17T07:45:32Z"), SHANGHAI);
        WorkLogService logs = new WorkLogService(new AppPaths(root.toString()), clock);

        logs.write("SAVE_ANNOTATION", "192.168.1.10", "main", "sub", "one.jpg", null, 1, null);

        assertThat(Files.readString(root.resolve("logs/ip_work.log")))
                .startsWith("2026-08-17 15:45:32 | ");
    }

    private WorkLogReadService reader() throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("models"));
        Files.createDirectories(root.resolve("logs"));
        Clock clock = Clock.system(SHANGHAI);
        return new WorkLogReadService(new WorkLogService(new AppPaths(root.toString()), clock), clock);
    }

    private void writeLines(String... lines) throws Exception {
        Files.write(root.resolve("logs").resolve("ip_work.log"), List.of(lines), StandardCharsets.UTF_8);
    }

    private static String line(LocalDateTime time, String ip, String target, int boxes) {
        return annotationLine(time, ip, "SAVE_ANNOTATION", target, boxes);
    }

    private static String annotationLine(LocalDateTime time, String ip, String action, String target, int boxes) {
        return time.format(FORMAT) + " | " + ip + " | " + action + " | annotation files/shift | "
                + target + " | boxes=" + boxes + " | success";
    }

    private static String moveLine(LocalDateTime time, String ip, String action, int count) {
        return time.format(FORMAT) + " | " + ip + " | " + action + " | annotation files/shift | files | count="
                + count + " | success";
    }
}
