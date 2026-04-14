package xy.mailsenders.mail.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MailPayload {

    @NotBlank
    @Email
    private String to;

    @NotBlank
    private String subject;

    @NotBlank
    private String body;

    private boolean html;

    private List<@Valid MailAttachment> attachments;

    public MailPayload(String to, String subject, String body, boolean html) {
        this.to = to;
        this.subject = subject;
        this.body = body;
        this.html = html;
    }
}
