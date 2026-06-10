package xy.mailsenders.controller;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import xy.mailsenders.mail.smtp.tester.SmtpDomainTestReport;
import xy.mailsenders.mail.smtp.tester.SmtpDomainTestRequest;
import xy.mailsenders.mail.smtp.tester.SmtpDomainTesterService;

import java.util.Map;

/**
 * REST controller for the SMTP domain tester.
 *
 * POST /api/smtp/test
 *   Runs a full DNS + SMTP diagnostic on a domain and returns a structured report.
 *
 * GET  /api/smtp/test?domain=yourdomain.com
 *   Quick DNS-only check (no SMTP credentials needed).
 *
 * All operations are read-only / probe-only — they do NOT send production emails.
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/api/smtp")
public class SmtpDomainTesterController {

    private final SmtpDomainTesterService testerService;

    /**
     * Full diagnostic: DNS records + SMTP auth + optional test send.
     *
     * Example request body:
     * {
     *   "senderEmail":   "noreply@1free.fr",
     *   "dkimSelector":  "default",
     *   "smtpHost":      "1free.fr",
     *   "smtpPort":      587,
     *   "smtpUsername":  "jfhubert@1free.fr",
     *   "smtpPassword":  "caroline",
     *   "useSsl":        false,
     *   "testRecipient": "youremail@gmail.com"   // optional — leave blank to skip sending
     * }
     */
    @PostMapping("/test")
    public SmtpDomainTestReport test(@RequestBody SmtpDomainTestRequest request) {
        log.info("SMTP domain test requested for domain={}, smtpHost={}",
                extractDomain(request.getSenderEmail()), request.getSmtpHost());
        return testerService.test(request);
    }

    /**
     * Quick DNS-only check — no SMTP credentials required.
     * Useful for a lightweight domain health check from the UI.
     *
     * GET /api/smtp/test?domain=1free.fr
     */
    @GetMapping("/test")
    public SmtpDomainTestReport quickDnsTest(@RequestParam String domain) {
        log.info("Quick DNS test for domain={}", domain);
        SmtpDomainTestRequest request = new SmtpDomainTestRequest();
        request.setSenderEmail("test@" + domain);
        return testerService.test(request);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> onError(Exception ex) {
        log.error("SmtpDomainTesterController error: {}", ex.getMessage(), ex);
        return Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Unexpected error");
    }

    private static String extractDomain(String email) {
        if (email == null || !email.contains("@")) return email != null ? email : "unknown";
        return email.substring(email.indexOf('@') + 1);
    }
}
