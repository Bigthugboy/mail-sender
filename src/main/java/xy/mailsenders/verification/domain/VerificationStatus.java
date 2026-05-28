package xy.mailsenders.verification.domain;

public enum VerificationStatus {
    SAFE,    // score >= threshold, all critical checks pass
    RISKY,   // deliverable but flagged (catch-all, role address, etc.)
    INVALID  // hard fail (no MX, syntax error, disposable, blacklisted)
}
