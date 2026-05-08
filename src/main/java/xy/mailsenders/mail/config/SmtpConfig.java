package xy.mailsenders.mail.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import xy.mailsenders.mail.smtp.SmtpProperties;


@Configuration
@EnableConfigurationProperties(SmtpProperties.class)
public class SmtpConfig {
}
