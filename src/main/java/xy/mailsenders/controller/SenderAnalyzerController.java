package xy.mailsenders.controller;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import xy.mailsenders.sender.analyzer.SenderAnalysisReport;
import xy.mailsenders.sender.analyzer.SenderAnalysisRequest;
import xy.mailsenders.sender.analyzer.SenderAnalyzerService;

import java.util.Map;

/**
 * REST controller for the Sender Spam Analyzer.
 *
 * POST /api/analyze/sender
 *   Runs all 37+ checks and returns a full spam diagnosis report.
 *
 * Example request:
 * {
 *   "senderEmail":   "jfhubert@1free.fr",
 *   "smtpHost":      "1free.fr",
 *   "smtpPort":      587,
 *   "smtpUsername":  "jfhubert@1free.fr",
 *   "smtpPassword":  "caroline",
 *   "useSsl":        false,
 *   "dkimSelector":  "default"
 * }
 *
 * Response includes:
 *   - deliverabilityScore  (0–100)
 *   - deliverabilityGrade  ("A — Excellent" … "F — Critical issues")
 *   - verdict              ("INBOX" | "MIXED" | "SPAM")
 *   - criticalIssueCount
 *   - priorityIssues       (ordered CRITICAL → HIGH → MEDIUM → LOW)
 *   - checksByCategory     (grouped by DNS Auth, Blacklists, PTR, SMTP, Config)
 *   - summaryStats         ({total, passed, failed, warned, skipped})
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/api/analyze")
public class SenderAnalyzerController {

    private final SenderAnalyzerService analyzerService;

    @PostMapping("/sender")
    public SenderAnalysisReport analyze(@RequestBody SenderAnalysisRequest request) {
        log.info("Spam analysis requested: domain={} smtpHost={}",
                extractDomain(request.getSenderEmail()), request.getSmtpHost());
        return analyzerService.analyze(request);
    }

    /**
     * Quick analysis using the currently configured application.properties SMTP settings.
     * No body needed — uses the defaults from the running config.
     */
    @GetMapping("/sender/quick")
    public Map<String, String> quickInfo() {
        return Map.of(
            "endpoint",    "POST /api/analyze/sender",
            "description", "Submit your SMTP config to get a full spam deliverability diagnosis",
            "example", """
                {
                  "senderEmail":   "you@yourdomain.com",
                  "smtpHost":      "your-smtp-host.com",
                  "smtpPort":      587,
                  "smtpUsername":  "you@yourdomain.com",
                  "smtpPassword":  "your-smtp-password",
                  "useSsl":        false,
                  "dkimSelector":  "default"
                }"""
        );
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> onError(Exception ex) {
        log.error("SenderAnalyzerController error: {}", ex.getMessage(), ex);
        return Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Unexpected error");
    }

    private static String extractDomain(String email) {
        if (email == null || !email.contains("@")) return "?";
        return email.substring(email.indexOf('@') + 1);
    }
}
