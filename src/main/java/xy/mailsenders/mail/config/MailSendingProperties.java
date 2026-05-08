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
    private String brevoBaseUrl;

    private String unsubscribeMailto;

    @Min(1)
    @Max(1000)
    private int maxRecipientsPerRequest;

    @DecimalMin("0.1")
    private double maxSendRatePerSecond;

    private String analyticsRecipient;

    private String analyticsSenderName;

    @NotBlank
    private String senderNetUrl;

    // --- Provider Strategy Flags ---

    private boolean useBrevoOnly;
    private boolean useBrevoWithProxy;
    private boolean useSmtpWithProxy;
    private boolean useSmtpOnly;

    private boolean useBrevoWithApiKey;
    private boolean useBrevoWithSmtp;
    private boolean useResendWithApiKey;
    private boolean useResendWithSmtp;
    private boolean useSendgridWithApiKey;
    private boolean useSendgridWithSmtp;

}
