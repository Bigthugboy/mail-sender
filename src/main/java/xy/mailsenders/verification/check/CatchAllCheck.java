package xy.mailsenders.verification.check;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import xy.mailsenders.verification.domain.CheckResult;
import xy.mailsenders.verification.domain.CheckType;
import xy.mailsenders.verification.throttle.SmtpProbeThrottle;

import java.util.UUID;

@Slf4j
@Component
public class CatchAllCheck implements EmailCheck {

    private final SmtpProbeThrottle throttle;

    public CatchAllCheck(SmtpProbeThrottle throttle) {
        this.throttle = throttle;
    }

    @Override
    public CheckResult run(String email) {
        String domain = DisposableCheck.domainOf(email);
        if (domain.isBlank()) {
            return CheckResult.fail(CheckType.CATCH_ALL, "Cannot extract domain from: " + email);
        }

        // Generate a random local part for canary address
        String localPart = "canary-" + UUID.randomUUID().toString().substring(0, 8);
        String canaryEmail = localPart + "@" + domain;

        try {
            // SmtpProbeThrottle protects the server from being hammered
            if (!throttle.acquire(10_000)) {
                return CheckResult.fail(CheckType.CATCH_ALL, "SMTP check throttled");
            }
            try {
                SmtpConversation.SmtpProbeResult result = SmtpConversation.probe(canaryEmail, null);
                if (result.outcome() == SmtpConversation.Outcome.ACCEPTED) {
                    return CheckResult.fail(CheckType.CATCH_ALL, "Domain is a catch-all (accepts any email)");
                }
                return CheckResult.pass(CheckType.CATCH_ALL);
            } finally {
                throttle.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CheckResult.fail(CheckType.CATCH_ALL, "Interrupted during catch-all check");
        }
    }
}
