package xy.mailsenders.mail.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MailAttachment {

    @NotBlank
    private String fileName;

    @NotBlank
    private String base64Content;

    private String contentType;
}
