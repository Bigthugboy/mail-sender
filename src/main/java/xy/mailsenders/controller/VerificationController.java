package xy.mailsenders.controller;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xy.mailsenders.verification.domain.CheckResult;
import xy.mailsenders.verification.domain.CheckType;
import xy.mailsenders.verification.domain.VerificationResult;
import xy.mailsenders.verification.domain.VerificationStatus;
import xy.mailsenders.verification.service.VerificationService;

import java.util.List;

@RestController
@AllArgsConstructor
@RequestMapping("/api")
public class VerificationController {

    private final VerificationService verificationService;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyRequest {
        private List<String> emails;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyResponse {
        private int safe;
        private int risky;
        private int invalid;
        private int total;
        private double safeRate;
        private List<VerifyResultItem> results;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyResultItem {
        private String email;
        private int score;
        private String status; // "safe", "risky", "invalid"
        private boolean mx;
        private boolean smtp;
        private boolean blacklist;
        private List<String> reasons;
    }

    @PostMapping("/verify")
    public VerifyResponse verify(@RequestBody VerifyRequest request) {
        List<VerificationResult> rawResults = verificationService.verifyList(request.getEmails());

        int safeCount = 0;
        int riskyCount = 0;
        int invalidCount = 0;

        for (VerificationResult r : rawResults) {
            if (r.status() == VerificationStatus.SAFE) {
                safeCount++;
            } else if (r.status() == VerificationStatus.RISKY) {
                riskyCount++;
            } else if (r.status() == VerificationStatus.INVALID) {
                invalidCount++;
            }
        }

        int total = rawResults.size();
        double safeRate = total == 0 ? 0.0 : (safeCount * 100.0) / total;

        List<VerifyResultItem> items = rawResults.stream().map(r -> {
            boolean mxPassed = r.checks().stream()
                    .filter(c -> c.type() == CheckType.MX_RECORD)
                    .map(CheckResult::passed)
                    .findFirst()
                    .orElse(false);

            boolean smtpPassed = r.checks().stream()
                    .filter(c -> c.type() == CheckType.SMTP_PROBE)
                    .map(CheckResult::passed)
                    .findFirst()
                    .orElse(false);

            boolean blacklistPassed = r.checks().stream()
                    .filter(c -> c.type() == CheckType.DNSBL)
                    .map(CheckResult::passed)
                    .findFirst()
                    .orElse(true); // default to true (clean) if check didn't run because syntax failed

            // If MX check wasn't even run because syntax check failed, mxPassed is false, which is correct.
            // If DNSBL check wasn't run because syntax/MX check failed, blacklistPassed should be false too (or at least it's not verified).
            // Let's adjust so that if MX check failed or Syntax check failed, blacklist is false too.
            boolean hasSyntaxError = r.checks().stream()
                    .filter(c -> c.type() == CheckType.SYNTAX)
                    .map(c -> !c.passed())
                    .findFirst()
                    .orElse(true);

            if (hasSyntaxError || !mxPassed) {
                blacklistPassed = false;
            }

            return new VerifyResultItem(
                    r.email(),
                    r.score(),
                    r.status().name().toLowerCase(),
                    mxPassed,
                    smtpPassed,
                    blacklistPassed,
                    r.failureReasons()
            );
        }).toList();

        return new VerifyResponse(safeCount, riskyCount, invalidCount, total, safeRate, items);
    }
}
