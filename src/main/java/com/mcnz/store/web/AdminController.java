package com.mcnz.store.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mcnz.store.config.LogCaptureService;
import com.mcnz.store.service.AdminInspectorService;
import com.mcnz.store.service.ApplicationRestartService;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/admin")
public class AdminController {

    private final AdminInspectorService adminInspectorService;
    private final LogCaptureService logCaptureService;
    private final ApplicationRestartService applicationRestartService;

    public AdminController(
            AdminInspectorService adminInspectorService,
            LogCaptureService logCaptureService,
            ApplicationRestartService applicationRestartService) {

        this.adminInspectorService = adminInspectorService;
        this.logCaptureService = logCaptureService;
        this.applicationRestartService = applicationRestartService;
    }

    @GetMapping("/config")
    public AdminInspectorService.ConfigSnapshot config() {
        return adminInspectorService.currentConfig();
    }

    @GetMapping("/database/inspect")
    public AdminInspectorService.DatabaseProbeResult inspectCurrentDatabase() {
        return adminInspectorService.inspectDatabase(null, null, null);
    }

    @PostMapping("/database/inspect")
    public AdminInspectorService.DatabaseProbeResult inspectDatabase(
            @RequestBody(required = false) DatabaseRequest request) {

        if (request == null) {
            return adminInspectorService.inspectDatabase(null, null, null);
        }

        return adminInspectorService.inspectDatabase(request.url, request.username, request.password);
    }

    @GetMapping("/kafka/inspect")
    public AdminInspectorService.KafkaProbeResult inspectCurrentKafka() {
        return adminInspectorService.inspectKafka(null);
    }

    @PostMapping("/kafka/inspect")
    public AdminInspectorService.KafkaProbeResult inspectKafka(@RequestBody(required = false) KafkaRequest request) {
        if (request == null) {
            return adminInspectorService.inspectKafka(null);
        }
        return adminInspectorService.inspectKafka(request.bootstrapServers);
    }

    @PostMapping("/config/apply")
    public AdminInspectorService.ApplyConfigResult apply(@RequestBody ApplyRequest request) {
        AdminInspectorService.ApplyConfigCommand command = new AdminInspectorService.ApplyConfigCommand(
                request.profile,
                request.databaseUrl,
                request.databaseUsername,
                request.databasePassword,
                request.kafkaBootstrapServers,
                request.attemptHotSwitch,
                request.persistToContainer);
        return adminInspectorService.applyConfig(command);
    }

    @GetMapping("/logs")
    public LogResponse logs(
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "true") boolean includeFileTail) {

        List<LogCaptureService.LogEntry> memory = logCaptureService.recent(limit, level);
        List<String> tail = includeFileTail ? logCaptureService.fileTail(limit) : List.of();
        return new LogResponse(logCaptureService.logFilePath(), memory, tail);
    }

    @PostMapping("/restart")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApplicationRestartService.RestartResponse restart(@RequestBody(required = false) RestartRequest request) {
        if (request == null) {
            return applicationRestartService.scheduleRestart("Admin requested restart", 1500, 0);
        }
        return applicationRestartService.scheduleRestart(request.reason, request.delayMs, request.exitCode);
    }

    public static class DatabaseRequest {
        public String url;
        public String username;
        public String password;
    }

    public static class KafkaRequest {
        public String bootstrapServers;
    }

    public static class ApplyRequest {
        public String profile;
        public String databaseUrl;
        public String databaseUsername;
        public String databasePassword;
        public String kafkaBootstrapServers;
        public boolean attemptHotSwitch = true;
        public boolean persistToContainer = true;
    }

    public static class RestartRequest {
        public String reason;
        public Integer delayMs = 1500;
        public Integer exitCode = 0;
    }

    public record LogResponse(String logFilePath, List<LogCaptureService.LogEntry> memoryLogs, List<String> fileTail) {
    }
}
