package xy.mailsenders.mail.config.config;

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

    @Min(1) @Max(10000)
    private int maxRecipientsPerRequest;

    @DecimalMin("0.1")
    private double maxSendRatePerSecond;

    private String analyticsRecipient;
    private String analyticsSenderName;

    // ── gateway selection flags ───────────────────────────────────────────────
    // OCP: add a new flag here + a branch in MailServiceImpl.resolveGateway().
    // Never remove or rename existing flags — only add.

    private boolean useBrevoOnly;
    private boolean useBrevoWithProxy;
    private boolean useBrevoWithApiKey;
    private boolean useSmtpOnly;
    private boolean useSmtpWithProxy;
}
