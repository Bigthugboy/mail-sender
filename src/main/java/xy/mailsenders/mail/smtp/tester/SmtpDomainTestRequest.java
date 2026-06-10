package xy.mailsenders.mail.smtp.tester;

import lombok.Getter;
import lombok.Setter;

/**
 * Input model for the SMTP domain tester.
 * All credentials are optional — only domain checks run if SMTP fields are blank.
 */
@Getter
@Setter
public class SmtpDomainTestRequest {

    /**
     * The sender email address — the domain part is used for DNS checks.
     * Example: "noreply@yourdomain.com"
     */
    private String senderEmail;

    // ── DKIM ────────────────────────────────────────────────────────────────

    /**
     * Optional explicit DKIM selector to probe first.
     * If blank, the tester tries a list of common selectors (default, mail, google, brevo…).
     */
    private String dkimSelector;

    // ── SMTP relay ──────────────────────────────────────────────────────────

    /** SMTP relay host (e.g. "smtp-relay.brevo.com", "1free.fr", "smtp.gmail.com"). */
    private String smtpHost;

    /** SMTP relay port (25, 465, or 587). */
    private int smtpPort = 587;

    /** SMTP login username (usually the sender email or API key). */
    private String smtpUsername;

    /** SMTP login password or API/app key. */
    private String smtpPassword;

    /** True = implicit SSL (port 465 / smtps). False = STARTTLS (port 587). */
    private boolean useSsl;

    // ── Optional test send ───────────────────────────────────────────────────

    /**
     * If set, the tester will send a real test email to this address
     * after a successful SMTP auth.  Leave blank to skip.
     */
    private String testRecipient;
}
