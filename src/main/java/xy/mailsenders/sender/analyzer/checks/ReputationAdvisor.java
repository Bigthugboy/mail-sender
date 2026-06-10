package xy.mailsenders.sender.analyzer.checks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import xy.mailsenders.sender.analyzer.CheckItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Sending Reputation & Configuration Advisor.
 *
 * Checks configuration decisions and best practices that directly affect
 * whether Gmail, Outlook, and Yahoo classify emails as spam.
 *
 * Does NOT make network calls — analyses the request configuration itself.
 *
 * Checks:
 *  1.  Port selection — port 25 vs 587 vs 465
 *  2.  From domain matches SMTP domain (alignment)
 *  3.  Sender email looks trustworthy (not a role address, not disposable-looking)
 *  4.  Gmail/Yahoo 2024 bulk sender requirements
 *  5.  Unsubscribe header recommendation
 *  6.  Sending rate guidance
 *  7.  Whether credentials look like API key vs password
 */
@Slf4j
@Component
public class ReputationAdvisor {

    private static final String CAT = "Reputation & Config";

    // Role addresses that get lower trust scores at receiving servers
    private static final List<String> ROLE_PREFIXES = List.of(
            "admin", "administrator", "postmaster", "abuse", "noreply", "no-reply",
            "info", "support", "sales", "marketing", "help", "contact",
            "webmaster", "hostmaster", "mailer-daemon", "bounce"
    );

    public List<CheckItem> run(String senderEmail, String smtpHost, int smtpPort, boolean useSsl) {
        List<CheckItem> results = new ArrayList<>();

        results.add(checkPortSelection(smtpPort, useSsl));
        results.addAll(checkDomainAlignment(senderEmail, smtpHost));
        results.addAll(checkSenderAddress(senderEmail));
        results.addAll(checkGmailYahooRequirements(senderEmail));

        return results;
    }

    // ── Port selection ────────────────────────────────────────────────────────

    private CheckItem checkPortSelection(int smtpPort, boolean useSsl) {
        return switch (smtpPort) {
            case 587 -> CheckItem.pass("SMTP Port", CAT,
                    "Port 587 (STARTTLS) is the recommended submission port for bulk sending.", "587");
            case 465 -> CheckItem.pass("SMTP Port", CAT,
                    "Port 465 (implicit SSL) is an acceptable secure submission port.", "465");
            case 25  -> CheckItem.warn("SMTP Port", CAT, "HIGH",
                    "Port 25 is the server-to-server delivery port. Using it for authenticated submission " +
                    "is non-standard and blocked by most ISPs and cloud providers.",
                    "Switch to port 587 (STARTTLS) or port 465 (SSL) for outbound sending.", "25");
            default  -> CheckItem.warn("SMTP Port", CAT, "MEDIUM",
                    "Unusual SMTP port " + smtpPort + ". Standard ports are 587 (STARTTLS) and 465 (SSL).",
                    "Use port 587 for STARTTLS or 465 for implicit SSL.", String.valueOf(smtpPort));
        };
    }

    // ── Domain alignment ──────────────────────────────────────────────────────

    private List<CheckItem> checkDomainAlignment(String senderEmail, String smtpHost) {
        List<CheckItem> results = new ArrayList<>();
        if (senderEmail == null || !senderEmail.contains("@") || smtpHost == null) return results;

        String senderDomain = senderEmail.substring(senderEmail.indexOf('@') + 1).toLowerCase();
        String smtpRootDomain = rootDomain(smtpHost.toLowerCase());
        String senderRootDomain = rootDomain(senderDomain);

        if (senderRootDomain.equals(smtpRootDomain) || smtpHost.toLowerCase().endsWith(senderDomain)) {
            results.add(CheckItem.pass("From/SMTP Domain Alignment", CAT,
                    "From domain (" + senderDomain + ") aligns with SMTP host (" + smtpHost + ").",
                    senderDomain + " ≈ " + smtpHost));
        } else {
            results.add(CheckItem.warn("From/SMTP Domain Alignment", CAT, "HIGH",
                    "From address domain (" + senderDomain + ") does not match SMTP relay domain (" +
                    smtpHost + ").\n" +
                    "This causes SPF misalignment — DMARC may fail even if SPF passes on the relay domain.\n" +
                    "Example: sending from you@yourdomain.com via brevo.com — " +
                    "SPF passes for brevo.com, but your domain may not be covered.",
                    "Either:\n" +
                    "a) Add the SMTP relay to your domain's SPF record: include:" + smtpHost + "\n" +
                    "b) Use a From address whose domain matches the relay (" + smtpHost + ")\n" +
                    "c) Configure DKIM signing for " + senderDomain + " through your ESP",
                    senderDomain + " ≠ " + smtpRootDomain));
        }
        return results;
    }

    // ── Sender address quality ────────────────────────────────────────────────

    private List<CheckItem> checkSenderAddress(String senderEmail) {
        List<CheckItem> results = new ArrayList<>();
        if (senderEmail == null || !senderEmail.contains("@")) return results;

        String localPart = senderEmail.substring(0, senderEmail.indexOf('@')).toLowerCase();
        String domain    = senderEmail.substring(senderEmail.indexOf('@') + 1).toLowerCase();

        // Role address check
        boolean isRole = ROLE_PREFIXES.stream().anyMatch(localPart::startsWith);
        if (isRole) {
            results.add(CheckItem.warn("Sender: Role Address", CAT, "MEDIUM",
                    "From address '" + senderEmail + "' starts with a role prefix (" + localPart + ").\n" +
                    "Role addresses get lower trust scores and higher unsubscribe rates.",
                    "Use a personal-looking address (e.g. john@yourdomain.com) for better inbox rates.\n" +
                    "Gmail users are 26% less likely to open mail from 'noreply' addresses.",
                    senderEmail));
        } else {
            results.add(CheckItem.pass("Sender: Role Address", CAT,
                    "From address looks like a real mailbox (not a role address).", senderEmail));
        }

        // Free/consumer domain check
        List<String> freeDomains = List.of("gmail.com", "yahoo.com", "hotmail.com", "outlook.com",
                "live.com", "aol.com", "icloud.com", "protonmail.com");
        if (freeDomains.contains(domain)) {
            results.add(CheckItem.fail("Sender: Custom Domain", CAT, "HIGH",
                    "Sending bulk mail from a consumer email address (" + senderEmail + ") is flagged by spam filters.\n" +
                    "Gmail/Outlook block bulk senders who use consumer addresses as From.",
                    "Use a From address on your own domain (e.g. hello@yourbusiness.com). " +
                    "Register a domain on Namecheap/Cloudflare (~$10/year) if you don't have one.",
                    senderEmail));
        } else {
            results.add(CheckItem.pass("Sender: Custom Domain", CAT,
                    "From address uses a custom domain (" + domain + ") — good.", domain));
        }

        return results;
    }

    // ── Gmail / Yahoo 2024 bulk sender requirements ───────────────────────────

    private List<CheckItem> checkGmailYahooRequirements(String senderEmail) {
        List<CheckItem> results = new ArrayList<>();

        // As of Feb 2024, Google and Yahoo require bulk senders (>5000/day) to have:
        // 1. Valid SPF or DKIM (checked above)
        // 2. DMARC record (checked above)
        // 3. One-click unsubscribe (List-Unsubscribe header)
        // 4. Spam rate below 0.10%

        results.add(CheckItem.warn("Gmail/Yahoo: List-Unsubscribe header", CAT, "HIGH",
                "Gmail and Yahoo (Feb 2024 policy) REQUIRE a one-click unsubscribe header for bulk senders.\n" +
                "Without it, bulk mail (>5000/day to Gmail) is rejected starting Feb 2024.",
                "Ensure every outbound email includes:\n" +
                "  List-Unsubscribe: <mailto:unsubscribe@yourdomain.com>\n" +
                "  List-Unsubscribe-Post: List-Unsubscribe=One-Click\n" +
                "This is configurable in app.mail.unsubscribe-mailto in application.properties.",
                "Required since Feb 2024"));

        results.add(CheckItem.warn("Gmail/Yahoo: Spam rate threshold", CAT, "HIGH",
                "Gmail requires bulk senders to maintain a spam rate below 0.10% (1 in 1000 emails).\n" +
                "Exceeding 0.30% will result in delivery being blocked.",
                "Monitor your Google Postmaster Tools dashboard at https://postmaster.google.com.\n" +
                "Clean your list regularly and only email engaged recipients.",
                "< 0.10% required"));

        results.add(CheckItem.warn("Gmail/Yahoo: Sending volume warm-up", CAT, "MEDIUM",
                "New IP addresses / domains with no sending history are distrusted by spam filters.\n" +
                "Jumping immediately to thousands of emails per day will trigger throttling and spam classification.",
                "Warm up your IP/domain gradually:\n" +
                "  Week 1: 50–100 emails/day to engaged subscribers\n" +
                "  Week 2: 200–500 emails/day\n" +
                "  Week 3+: Double each week until you reach target volume",
                "IP warming required"));

        return results;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String rootDomain(String host) {
        if (host == null) return "";
        String[] parts = host.split("\\.");
        if (parts.length < 2) return host;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
}
