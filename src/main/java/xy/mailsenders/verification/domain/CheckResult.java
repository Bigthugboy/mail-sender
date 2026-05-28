package xy.mailsenders.verification.domain;

/**
 * Result of a single check against one email address.
 * Immutable value object — safe to share across threads.
 */
public record CheckResult(
        CheckType type,
        boolean   passed,
        String    detail      // human-readable reason on failure, empty on pass
) {
    public static CheckResult pass(CheckType type) {
        return new CheckResult(type, true, "");
    }

    public static CheckResult fail(CheckType type, String reason) {
        return new CheckResult(type, false, reason);
    }
}
