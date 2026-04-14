package xy.mailsenders.mail.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MailFailure {

    private String recipient;
    private String reason;
}
