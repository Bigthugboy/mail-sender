package xy.mailsenders.mail.resend;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resend attachment object.
 * 'content' must be the raw base64 string (no data-URI prefix).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResendAttachment {

    /** Original file name, e.g. "invoice.pdf" */
    private String filename;

    /** Base64-encoded file content */
    private String content;
}
