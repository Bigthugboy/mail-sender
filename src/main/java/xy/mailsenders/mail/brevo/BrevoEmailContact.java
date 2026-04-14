package xy.mailsenders.mail.brevo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BrevoEmailContact {

    private String email;
    private String name;
}
