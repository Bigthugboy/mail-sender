package xy.mailsenders.mail.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import xy.mailsenders.mail.domain.MailPayload;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendMailsRequest {

    @NotEmpty
    @Size(max = 1000)
    private List<@Valid MailPayload> mails;
}
