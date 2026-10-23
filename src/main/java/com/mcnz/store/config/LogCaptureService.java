package com.mcnz.store.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

@Service
public class LogCaptureService {

    private static final int MAX_ENTRIES = 3000;
    private static final Path LOG_FILE = Paths.get("runtime", "app.log").toAbsolutePath().normalize();

    private final Deque<LogEntry> entries = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public LogCaptureService() {
        installAppender();
    }

    public List<LogEntry> recent(int limit, String level) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        Level requestedLevel = parseLevel(level);

        List<LogEntry> snapshot = new ArrayList<>(entries);
        List<LogEntry> filtered = new ArrayList<>();
        for (LogEntry entry : snapshot) {
            if (requestedLevel != null && !entry.level().equalsIgnoreCase(requestedLevel.toString())) {
                continue;
            }
            filtered.add(entry);
        }

        int from = Math.max(0, filtered.size() - safeLimit);
        return filtered.subList(from, filtered.size());
    }

    public List<String> fileTail(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        if (!Files.exists(LOG_FILE)) {
            return List.of("Log file not found: " + LOG_FILE);
        }

        try {
            List<String> lines = Files.readAllLines(LOG_FILE, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - safeLimit);
            return lines.subList(from, lines.size());
        } catch (IOException ioException) {
            return List.of("Failed to read log file: " + ioException.getMessage());
        }
    }

    public String logFilePath() {
        return LOG_FILE.toString();
    }

    private void installAppender() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent eventObject) {
                addEntry(eventObject);
            }
        };
        appender.setContext(context);
        appender.setName("inMemoryAdminAppender");
        appender.start();
        rootLogger.addAppender(appender);
    }

    private void addEntry(ILoggingEvent event) {
        String throwable = event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy());
        LogEntry entry = new LogEntry(
                Instant.ofEpochMilli(event.getTimeStamp()).toString(),
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getFormattedMessage(),
                throwable);

        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.pollFirst();
        }
    }

    private Level parseLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        try {
            return Level.valueOf(level.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record LogEntry(String timestamp, String level, String logger, String message, String throwable) {
    }
}
