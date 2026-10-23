package com.mcnz.store.service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

@Service
public class ApplicationRestartService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationRestartService.class);

    private final ConfigurableApplicationContext applicationContext;
    private final AtomicBoolean restartScheduled = new AtomicBoolean(false);

    public ApplicationRestartService(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public RestartResponse scheduleRestart(String reason, Integer delayMs, Integer requestedExitCode) {
        int safeDelayMs = sanitizeDelay(delayMs);
        int safeExitCode = sanitizeExitCode(requestedExitCode);
        String safeReason = reason == null || reason.isBlank() ? "Admin requested restart" : reason.trim();

        if (!restartScheduled.compareAndSet(false, true)) {
            return new RestartResponse(
                    false,
                    true,
                    "A restart is already scheduled.",
                    safeReason,
                    safeDelayMs,
                    safeExitCode,
                    Instant.now().toString());
        }

        Thread restartThread = new Thread(() -> {
            try {
                Thread.sleep(safeDelayMs);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                log.warn("Restart thread interrupted; continuing with shutdown.");
            }

            int exitCode = safeExitCode;
            try {
                log.warn("Admin-triggered restart initiated: {}", safeReason);
                exitCode = SpringApplication.exit(applicationContext, () -> safeExitCode);
            } catch (Exception exception) {
                log.error("SpringApplication.exit failed during admin restart request", exception);
            }

            log.warn("Exiting JVM now with code {}", exitCode);
            System.exit(exitCode);
        }, "admin-restart-thread");

        restartThread.setDaemon(false);
        restartThread.start();

        return new RestartResponse(
                true,
                false,
                "Restart scheduled. The JVM will exit to let the container restart policy recreate it.",
                safeReason,
                safeDelayMs,
                safeExitCode,
                Instant.now().toString());
    }

    private int sanitizeDelay(Integer delayMs) {
        if (delayMs == null) {
            return 1500;
        }
        return Math.max(250, Math.min(delayMs, 60000));
    }

    private int sanitizeExitCode(Integer exitCode) {
        if (exitCode == null) {
            return 0;
        }
        return Math.max(0, exitCode);
    }

    public record RestartResponse(
            boolean accepted,
            boolean alreadyScheduled,
            String message,
            String reason,
            int delayMs,
            int exitCode,
            String requestedAtUtc) {
    }
}
