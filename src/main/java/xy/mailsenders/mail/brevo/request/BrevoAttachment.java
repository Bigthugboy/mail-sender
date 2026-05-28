package xy.mailsenders.mail.brevo.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BrevoAttachment {

    private String name;
    private String content;
}
