package xy.mailsenders.sender.analyzer;

import java.util.List;

/**
 * One check result — shared by every checker in the analyzer.
 *
 * status: PASS | WARN | FAIL | ERROR | SKIP
 * severity: CRITICAL | HIGH | MEDIUM | LOW | INFO
 */
public record CheckItem(
        String  checkName,
        String  category,
        String  status,
        String  severity,
        String  detail,
        String  recommendation,
        String  value          // the raw DNS/network value found (nullable)
) {

    /* ── factory helpers ────────────────────────────────────────────────── */

    public static CheckItem pass(String name, String category, String detail, String value) {
        return new CheckItem(name, category, "PASS", "INFO", detail, null, value);
    }

    public static CheckItem warn(String name, String category, String severity,
                                 String detail, String recommendation, String value) {
        return new CheckItem(name, category, "WARN", severity, detail, recommendation, value);
    }

    public static CheckItem fail(String name, String category, String severity,
                                 String detail, String recommendation, String value) {
        return new CheckItem(name, category, "FAIL", severity, detail, recommendation, value);
    }

    public static CheckItem error(String name, String category, String detail) {
        return new CheckItem(name, category, "ERROR", "MEDIUM", detail,
                "Check network connectivity and DNS resolution.", null);
    }

    public static CheckItem skip(String name, String category, String reason) {
        return new CheckItem(name, category, "SKIP", "INFO", reason, null, null);
    }

    public boolean passed()  { return "PASS".equals(status); }
    public boolean failed()  { return "FAIL".equals(status); }
    public boolean warned()  { return "WARN".equals(status); }

    /** True when this item actively hurts deliverability. */
    public boolean isDeliveryRisk() {
        return ("FAIL".equals(status) || "WARN".equals(status))
                && ("CRITICAL".equals(severity) || "HIGH".equals(severity) || "MEDIUM".equals(severity));
    }

    /** Penalty weight — used for scoring. */
    public int penaltyWeight() {
        if ("PASS".equals(status)) return 0;
        if ("SKIP".equals(status) || "ERROR".equals(status)) return 0;
        return switch (severity) {
            case "CRITICAL" -> 25;
            case "HIGH"     -> 15;
            case "MEDIUM"   -> 8;
            case "LOW"      -> 3;
            default         -> 0;
        };
    }
}
