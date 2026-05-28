package xy.mailsenders.verification.check;

import org.springframework.stereotype.Component;
import xy.mailsenders.verification.domain.CheckResult;
import xy.mailsenders.verification.domain.CheckType;

import java.util.Set;

/**
 * Check 3 — Role / bot address detection.
 * Flags addresses that typically belong to teams or auto-responders,
 * not real inboxes — these bounce or silently ignore bulk mail.
 *
 * No network I/O — in-process lookup.
 */
@Component
public class RoleAddressCheck implements EmailCheck {

    private static final Set<String> ROLE_PREFIXES = Set.of(
            "admin", "administrator", "abuse", "billing", "contact",
            "help", "hello", "hi", "hostmaster", "info", "it",
            "jobs", "legal", "mail", "mailer-daemon", "marketing",
            "no-reply", "noreply", "newsletter", "notifications",
            "office", "operations", "postmaster", "privacy", "pr",
            "press", "sales", "security", "service", "support",
            "team", "tech", "unsubscribe", "webmaster", "www"
    );

    @Override
    public CheckResult run(String email) {
        String local = localOf(email);
        // Exact match or prefix match (e.g. "support.uk" still matches "support")
        boolean isRole = ROLE_PREFIXES.stream()
                .anyMatch(prefix -> local.equals(prefix) || local.startsWith(prefix + "."));
        if (isRole)
            return CheckResult.fail(CheckType.ROLE_ADDRESS,
                    "Role/functional address unlikely to be read by an individual: " + email);
        return CheckResult.pass(CheckType.ROLE_ADDRESS);
    }

    static String localOf(String email) {
        if (email == null || !email.contains("@")) return email == null ? "" : email;
        return email.substring(0, email.indexOf('@')).toLowerCase().trim();
    }
}
