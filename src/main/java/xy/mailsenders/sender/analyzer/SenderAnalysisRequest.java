package xy.mailsenders.sender.analyzer;

import lombok.Getter;
import lombok.Setter;

/**
 * Input for the sender spam analyzer.
 *
 * Supply the SMTP credentials exactly as you use them for sending.
 * The analyzer resolves the server IP from smtpHost and tests that IP + domain.
 */
@Getter
@Setter
public class SenderAnalysisRequest {

    /**
     * The From address you use when sending (e.g. "ilanda7@1free.fr").
     * The domain is used for DNS record checks.
     */
    private String senderEmail;

    /**
     * SMTP relay host (e.g. "1free.fr", "smtp-relay.brevo.com").
     * This hostname is resolved to an IP and tested against blacklists.
     */
    private String smtpHost;

    /** SMTP port: 25, 465 (SSL), or 587 (STARTTLS). */
    private int smtpPort = 587;

    /** SMTP username (usually same as senderEmail or an API key). */
    private String smtpUsername;

    /** SMTP password or API key. */
    private String smtpPassword;

    /** true = implicit SSL (port 465). false = STARTTLS (port 587). */
    private boolean useSsl;

    /**
     * Optional: your DKIM selector (e.g. "default", "brevo", "mail").
     * If blank, the analyzer tries a list of 15+ common selectors automatically.
     */
    private String dkimSelector;
}
