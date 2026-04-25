package xy.mailsenders.mail.brevo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
@Getter
@Setter
@AllArgsConstructor
public class MailSendingReport {
    private int total;
    private int successCount;
    private int failureCount;
    private List<String> successfulEmails;
    private List<String> failedEmails;


}
