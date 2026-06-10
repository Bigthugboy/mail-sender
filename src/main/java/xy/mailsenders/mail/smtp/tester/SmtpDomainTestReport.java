package xy.mailsenders.mail.smtp.tester;

import xy.mailsenders.mail.smtp.tester.SmtpDomainTesterService.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Full diagnostic report from the SMTP domain tester.
 * Each field maps to one check — null means that check was not run.
 */
public class SmtpDomainTestReport {

    /** The domain extracted from the sender email. */
    private final String domain;

    /** ISO-8601 timestamp of when the test was performed. */
    private final String testedAt;

    // ── DNS checks ────────────────────────────────────────────────────────────

    /** MX record presence — is the domain configured to receive email? */
    private final DnsCheckResult mxCheck;

    /** SPF TXT record — anti-spoofing policy. */
    private final DnsCheckResult spfCheck;

    /**
     * DKIM TXT record — public key for email signing.
     * Probes common selectors (default, mail, google, brevo…).
     */
    private final DnsCheckResult dkimCheck;

    /** DMARC TXT record at _dmarc.<domain>. */
    private final DnsCheckResult dmarcCheck;

    // ── SMTP checks ───────────────────────────────────────────────────────────

    /**
     * SMTP port 25 connect to the domain's primary MX.
     * Tests that the domain can actually accept inbound mail.
     */
    private final DnsCheckResult smtpMxConnectCheck;

    /**
     * SMTP auth on the configured relay server.
     * Tests that your SMTP credentials work for sending.
     */
    private final SmtpAuthResult smtpAuthCheck;

    // ── Optional test send ────────────────────────────────────────────────────

    /**
     * Result of the optional test email send.
     * Null when no testRecipient was specified in the request.
     */
    private final TestSendResult testSendResult;

    // ─────────────────────────────────────────────────────────────────────────

    public SmtpDomainTestReport(
            String domain,
            String testedAt,
            DnsCheckResult mxCheck,
            DnsCheckResult spfCheck,
            DnsCheckResult dkimCheck,
            DnsCheckResult dmarcCheck,
            DnsCheckResult smtpMxConnectCheck,
            SmtpAuthResult smtpAuthCheck,
            TestSendResult testSendResult) {
        this.domain             = domain;
        this.testedAt           = testedAt;
        this.mxCheck            = mxCheck;
        this.spfCheck           = spfCheck;
        this.dkimCheck          = dkimCheck;
        this.dmarcCheck         = dmarcCheck;
        this.smtpMxConnectCheck = smtpMxConnectCheck;
        this.smtpAuthCheck      = smtpAuthCheck;
        this.testSendResult     = testSendResult;
    }

    // ── Getters (Jackson-serializable) ────────────────────────────────────────

    public String          getDomain()             { return domain; }
    public String          getTestedAt()           { return testedAt; }
    public DnsCheckResult  getMxCheck()            { return mxCheck; }
    public DnsCheckResult  getSpfCheck()           { return spfCheck; }
    public DnsCheckResult  getDkimCheck()          { return dkimCheck; }
    public DnsCheckResult  getDmarcCheck()         { return dmarcCheck; }
    public DnsCheckResult  getSmtpMxConnectCheck() { return smtpMxConnectCheck; }
    public SmtpAuthResult  getSmtpAuthCheck()      { return smtpAuthCheck; }
    public TestSendResult  getTestSendResult()     { return testSendResult; }

    // ── Computed summary ─────────────────────────────────────────────────────

    /**
     * Overall deliverability score (0–100).
     * Weighted: auth > mx > spf > dkim > dmarc
     */
    public int getDeliverabilityScore() {
        int score = 0;
        if (mxCheck   != null && mxCheck.passed())           score += 25; // required
        if (spfCheck  != null && spfCheck.passed())           score += 20; // important
        if (dkimCheck != null && dkimCheck.passed())          score += 20; // important
        if (dmarcCheck != null && dmarcCheck.passed())        score += 10; // nice to have
        if (smtpMxConnectCheck != null && smtpMxConnectCheck.passed()) score += 10;
        if (smtpAuthCheck != null && smtpAuthCheck.success()) score += 15; // confirms relay works
        return score;
    }

    /**
     * Human-readable grade based on the deliverability score.
     */
    public String getDeliverabilityGrade() {
        int s = getDeliverabilityScore();
        if (s >= 90) return "EXCELLENT";
        if (s >= 70) return "GOOD";
        if (s >= 50) return "FAIR — likely going to spam";
        return "POOR — high spam risk";
    }

    /**
     * Flattened list of actionable recommendations for items that failed.
     */
    public List<String> getRecommendations() {
        List<String> recs = new ArrayList<>();
        if (mxCheck   != null && !mxCheck.passed())           recs.add("⚠️ MX: "       + mxCheck.detail());
        if (spfCheck  != null && !spfCheck.passed())           recs.add("⚠️ SPF: "      + spfCheck.detail());
        if (dkimCheck != null && !dkimCheck.passed())          recs.add("⚠️ DKIM: "     + dkimCheck.detail());
        if (dmarcCheck != null && !dmarcCheck.passed())        recs.add("ℹ️ DMARC: "    + dmarcCheck.detail());
        if (smtpMxConnectCheck != null && !smtpMxConnectCheck.passed())
            recs.add("⚠️ SMTP-MX: " + smtpMxConnectCheck.detail());
        if (smtpAuthCheck != null && !smtpAuthCheck.success())
            recs.add("❌ SMTP Auth: " + smtpAuthCheck.detail());
        if (recs.isEmpty())
            recs.add("✅ All checks passed — your domain is well configured.");
        return recs;
    }
}
