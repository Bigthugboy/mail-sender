package xy.mailsenders.mail.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Setter
@Getter
@Validated
@ConfigurationProperties(prefix = "app.mail")
public class MailSendingProperties {

    @NotBlank
    private String fromAddress;

    @NotBlank
    private String brevoApiKey;

    @NotBlank
    private String brevoBaseUrl = "https://api.brevo.com";

    private String replyTo;

    private String unsubscribeMailto;

    @Min(1)
    @Max(1000)
    private int maxRecipientsPerRequest = 1000;

    @DecimalMin("0.1")
    private double maxSendRatePerSecond = 10.0;

}
