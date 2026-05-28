package xy.mailsenders.verification.domain;

import java.util.List;

/**
 * Aggregated result for one email address after all checks have run.
 */
public record VerificationResult(
        String           email,
        int              score,        // 0-100
        VerificationStatus status,
        List<CheckResult> checks
) {
    /** Convenience: collect failure reasons for display. */
    public List<String> failureReasons() {
        return checks.stream()
                .filter(c -> !c.passed())
                .map(CheckResult::detail)
                .toList();
    }
}
