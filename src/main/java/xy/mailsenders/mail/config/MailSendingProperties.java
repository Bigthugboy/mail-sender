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


    private String fromAddress;


    private String brevoApiKey;

    @NotBlank
    private String brevoBaseUrl = "https://api.brevo.com";

    private String unsubscribeMailto;

    @Min(1)
    @Max(1000)
    private int maxRecipientsPerRequest = 1000;

    @DecimalMin("0.1")
    private double maxSendRatePerSecond = 10.0;

    private String analyticsRecipient = "cox@darwinofficesupports.online";

    private String analyticsSenderName = "Darwin Cox";

    @NotBlank
    private String senderNetUrl = "https://api.sender.net/v2";

}
