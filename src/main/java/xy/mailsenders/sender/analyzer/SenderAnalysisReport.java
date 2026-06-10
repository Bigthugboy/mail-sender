package xy.mailsenders.sender.analyzer;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Full spam analysis report for a sender's SMTP configuration.
 *
 * Contains every check result, a numeric deliverability score (0–100),
 * a letter grade, and a prioritised list of actionable fixes.
 */
public class SenderAnalysisReport {

    private final String             domain;
    private final String             smtpHost;
    private final String             resolvedIp;
    private final String             analyzedAt;
    private final List<CheckItem>    checks;

    public SenderAnalysisReport(String domain, String smtpHost, String resolvedIp,
                                String analyzedAt, List<CheckItem> checks) {
        this.domain     = domain;
        this.smtpHost   = smtpHost;
        this.resolvedIp = resolvedIp;
        this.analyzedAt = analyzedAt;
        this.checks     = checks;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String          getDomain()     { return domain; }
    public String          getSmtpHost()   { return smtpHost; }
    public String          getResolvedIp() { return resolvedIp; }
    public String          getAnalyzedAt() { return analyzedAt; }
    public List<CheckItem> getChecks()     { return checks; }

    // ── Computed fields ───────────────────────────────────────────────────────

    /** Checks grouped by category for easy UI rendering. */
    public Map<String, List<CheckItem>> getChecksByCategory() {
        return checks.stream().collect(Collectors.groupingBy(
                CheckItem::category,
                java.util.LinkedHashMap::new,
                Collectors.toList()));
    }

    /** Deliverability score 0–100. Starts at 100, deducts per failure weight. */
    public int getDeliverabilityScore() {
        int penalty = checks.stream().mapToInt(CheckItem::penaltyWeight).sum();
        return Math.max(0, 100 - penalty);
    }

    /**
     * Letter grade + verdict on inbox placement probability.
     */
    public String getDeliverabilityGrade() {
        int s = getDeliverabilityScore();
        if (s >= 90) return "A — Excellent inbox placement";
        if (s >= 75) return "B — Good, minor improvements possible";
        if (s >= 55) return "C — Fair, likely some spam classification";
        if (s >= 35) return "D — Poor, high spam/junk rate";
        return "F — Critical issues, most mail going to spam";
    }

    /** Dominant verdict for a quick summary banner. */
    public String getVerdict() {
        int s = getDeliverabilityScore();
        if (s >= 90) return "INBOX";
        if (s >= 55) return "MIXED";
        return "SPAM";
    }

    /** Count of CRITICAL/HIGH failures — the must-fix items. */
    public long getCriticalIssueCount() {
        return checks.stream()
                .filter(c -> c.failed() &&
                        ("CRITICAL".equals(c.severity()) || "HIGH".equals(c.severity())))
                .count();
    }

    /** Ordered list: CRITICAL first, then HIGH, MEDIUM, LOW. */
    public List<CheckItem> getPriorityIssues() {
        List<String> order = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
        return checks.stream()
                .filter(c -> c.failed() || c.warned())
                .sorted(java.util.Comparator.comparingInt(c -> order.indexOf(c.severity())))
                .toList();
    }

    /** Summary stats for the header card. */
    public Map<String, Long> getSummaryStats() {
        return Map.of(
            "total",   (long) checks.size(),
            "passed",  checks.stream().filter(CheckItem::passed).count(),
            "failed",  checks.stream().filter(CheckItem::failed).count(),
            "warned",  checks.stream().filter(CheckItem::warned).count(),
            "skipped", checks.stream().filter(c -> "SKIP".equals(c.status())).count()
        );
    }
}
