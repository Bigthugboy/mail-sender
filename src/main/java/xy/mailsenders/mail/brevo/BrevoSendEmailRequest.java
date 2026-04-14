package xy.mailsenders.mail.brevo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class BrevoSendEmailRequest {

    private BrevoEmailContact sender;
    private List<BrevoEmailContact> to;
    private BrevoEmailContact replyTo;
    private String subject;
    private String htmlContent;
    private String textContent;
    private Map<String, String> headers;
    private java.util.List<BrevoAttachment> attachment;
}
