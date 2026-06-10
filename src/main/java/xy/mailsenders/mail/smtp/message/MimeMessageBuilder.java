package xy.mailsenders.mail.smtp.message;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.*;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import xy.mailsenders.mail.config.MailSendingProperties;
import xy.mailsenders.mail.domain.MailAttachment;
import xy.mailsenders.mail.domain.MailPayload;
import xy.mailsenders.mail.smtp.SmtpProperties;

import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;


@Component
public class MimeMessageBuilder {

    private static final String X_MAILER = "MailSenders/1.0 (Jakarta Mail)";

    private final MailSendingProperties mailProps;
    private final SmtpProperties        smtpProps;

    public MimeMessageBuilder(MailSendingProperties mailProps, SmtpProperties smtpProps) {
        this.mailProps = mailProps;
        this.smtpProps = smtpProps;
    }

    public MimeMessage build(Session session, MailPayload payload)
            throws MessagingException, UnsupportedEncodingException {

        MimeMessage msg = new MimeMessage(session);
        setFrom(msg);
        setReplyTo(msg);
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(payload.getTo().trim()));
        msg.setSubject(payload.getSubject().trim(), "UTF-8");
        msg.setSentDate(new Date());

        // Delivery-critical headers — these help avoid spam classification
        msg.setHeader("Message-ID",               generateMessageId());
        msg.setHeader("MIME-Version",              "1.0");
        msg.setHeader("X-Mailer",                  X_MAILER);
        msg.setHeader("Precedence",                "bulk");
        msg.setHeader("Date",                      rfcDate());

        setUnsubscribeHeaders(msg);

        if (CollectionUtils.isEmpty(payload.getAttachments())) {
            setBody(msg, payload);
        } else {
            msg.setContent(buildMultipart(payload));
        }
        return msg;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void setFrom(MimeMessage msg) throws MessagingException, UnsupportedEncodingException {
        String addr = StringUtils.hasText(mailProps.getFromAddress())
                ? mailProps.getFromAddress().trim() : smtpProps.getUsername();
        String name = mailProps.getAnalyticsSenderName();
        try {
            msg.setFrom(StringUtils.hasText(name)
                    ? new InternetAddress(addr, name, "UTF-8")
                    : new InternetAddress(addr));
        } catch (Exception e) {
            msg.setFrom(new InternetAddress(addr));
        }
    }

    /**
     * Sets Reply-To to the same From address so replies don't bounce.
     * A missing Reply-To can increase spam score on some filters.
     */
    private void setReplyTo(MimeMessage msg) throws MessagingException, UnsupportedEncodingException {
        String addr = StringUtils.hasText(mailProps.getFromAddress())
                ? mailProps.getFromAddress().trim() : smtpProps.getUsername();
        String name = mailProps.getAnalyticsSenderName();
        try {
            InternetAddress replyTo = StringUtils.hasText(name)
                    ? new InternetAddress(addr, name, "UTF-8")
                    : new InternetAddress(addr);
            msg.setReplyTo(new InternetAddress[]{ replyTo });
        } catch (Exception ignored) { /* non-critical */ }
    }

    private void setUnsubscribeHeaders(MimeMessage msg) throws MessagingException {
        if (StringUtils.hasText(mailProps.getUnsubscribeMailto())) {
            msg.setHeader("List-Unsubscribe",
                    "<mailto:" + mailProps.getUnsubscribeMailto().trim() + ">");
            msg.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        }
    }

    /** RFC 2822-compliant date string required by strict spam filters. */
    private static String rfcDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date());
    }

    private void setBody(MimePart part, MailPayload payload) throws MessagingException {
        if (payload.isHtml()) {
            // RFC 2046 §5.1.4: the "best" representation comes LAST in multipart/alternative.
            // Clients prefer the last part (HTML); spam filters reward the plain-text fallback.
            MimeMultipart alt = new MimeMultipart("alternative");

            // Part 1: plain-text fallback (required for deliverability)
            MimeBodyPart text = new MimeBodyPart();
            text.setText(stripHtml(payload.getBody()), "UTF-8", "plain");
            text.setHeader("Content-Transfer-Encoding", "quoted-printable");
            alt.addBodyPart(text);

            // Part 2: HTML (preferred — rendered by modern clients)
            MimeBodyPart html = new MimeBodyPart();
            html.setContent(payload.getBody(), "text/html; charset=UTF-8");
            html.setHeader("Content-Transfer-Encoding", "quoted-printable");
            alt.addBodyPart(html);

            part.setContent(alt);
        } else {
            part.setText(payload.getBody(), "UTF-8", "plain");
        }
    }

    private MimeMultipart buildMultipart(MailPayload payload) throws MessagingException {
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart bodyPart = new MimeBodyPart();
        setBody(bodyPart, payload);
        mixed.addBodyPart(bodyPart);
        for (MailAttachment att : payload.getAttachments()) {
            mixed.addBodyPart(buildAttachmentPart(att));
        }
        return mixed;
    }

    private MimeBodyPart buildAttachmentPart(MailAttachment att) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        byte[] decoded = Base64.getDecoder().decode(att.getBase64Content());
        part.setContent(decoded, detectMimeType(att.getFileName()));
        try {
            part.setFileName(MimeUtility.encodeText(att.getFileName(), "UTF-8", "B"));
        } catch (UnsupportedEncodingException e) {
            part.setFileName(att.getFileName());
        }
        part.setDisposition(Part.ATTACHMENT);
        return part;
    }

    private String generateMessageId() {
        String from = mailProps.getFromAddress();
        String domain = (StringUtils.hasText(from) && from.contains("@"))
                ? from.substring(from.indexOf('@') + 1) : "mail.local";
        // UUID + timestamp prefix reduces duplicate-detection false positives
        return "<" + System.currentTimeMillis() + "." + UUID.randomUUID() + "@" + domain + ">";
    }

    // ── shared utilities (DRY — single source for whole smtp package) ────────

    public static String stripHtml(String html) {
        if (!StringUtils.hasText(html)) return "";
        return html.replaceAll("(?s)<style.*?>.*?</style>", "")
                .replaceAll("(?s)<script.*?>.*?</script>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</?p\\s*/?>", "\n")
                .replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replaceAll("\\n\\s*\\n+", "\n\n").trim();
    }

    public static String detectMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".csv"))  return "text/csv";
        if (lower.endsWith(".txt"))  return "text/plain";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".zip"))  return "application/zip";
        return "application/octet-stream";
    }
}
