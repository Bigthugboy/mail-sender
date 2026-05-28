package xy.mailsenders.verification.check;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import xy.mailsenders.verification.domain.CheckResult;
import xy.mailsenders.verification.domain.CheckType;
import xy.mailsenders.verification.throttle.SmtpProbeThrottle;

/**
 * Check 6 — SMTP RCPT TO probe (mailbox existence).
 *
 * Opens a real TCP connection to the domain's MX server and issues
 * MAIL FROM + RCPT TO without ever sending DATA. The server's response
 * tells us if the mailbox exists. No email is sent.
 *
 * Uses SmtpProbeThrottle to prevent hammering MX servers (anti-block).
 * Uses SmtpConversation for the actual protocol — single responsibility.
 */
@Slf4j
@Component
public class SmtpProbeCheck implements EmailCheck {

    private static final long ACQUIRE_TIMEOUT_MS = 10_000;

    private final SmtpProbeThrottle throttle;

    public SmtpProbeCheck(SmtpProbeThrottle throttle) {
        this.throttle = throttle;
    }

    @Override
    public CheckResult run(String email) {
        try {
            if (!throttle.acquire(ACQUIRE_TIMEOUT_MS))
                return CheckResult.fail(CheckType.SMTP_PROBE,
                        "SMTP probe skipped — rate limit reached");
            try {
                SmtpConversation.SmtpProbeResult result =
                        SmtpConversation.probe(email, null);
                return switch (result.outcome()) {
                    case ACCEPTED        -> CheckResult.pass(CheckType.SMTP_PROBE);
                    case REJECTED        -> CheckResult.fail(CheckType.SMTP_PROBE,
                            "Mailbox does not exist: " + result.detail());
                    case CATCH_ALL_LIKELY -> CheckResult.pass(CheckType.SMTP_PROBE);
                    case ERROR           -> CheckResult.fail(CheckType.SMTP_PROBE,
                            "SMTP probe error: " + result.detail());
                };
            } finally {
                throttle.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CheckResult.fail(CheckType.SMTP_PROBE, "SMTP probe interrupted");
        }
    }
}
