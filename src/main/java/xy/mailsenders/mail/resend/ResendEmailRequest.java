package xy.mailsenders.mail.resend;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Represents a single email entry in Resend's batch request array.

 */
@Data
public class ResendEmailRequest {

    /** Sender in "Name <email>" format, e.g. "Darwin Cox <cox@darwinofficesupports.online>" */
    private String from;

    /** Single recipient address */
    private List<String> to;

    private String subject;

    /** Plain text body — always included as fallback */
    private String text;

    /** HTML body — only set when payload.isHtml() == true */
    private String html;

    /** Optional attachments */
    private List<ResendAttachment> attachments;

    /** Optional headers e.g. List-Unsubscribe */
    private Map<String, String> headers;
}
