package xy.mailsenders.mail.brevo.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import xy.mailsenders.mail.brevo.request.BrevoSendEmailRequest;
import xy.mailsenders.mail.config.MailSendingProperties;
import xy.mailsenders.mail.smtp.SmtpProperties;

import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * Thin HTTP client for the Brevo transactional email API.
 *
 * SRP: HTTP transport only — no payload mapping, no retry logic.
 * OCP: swap RestClient for WebClient without touching the gateway.
 */
@Slf4j
@Component
public class BrevoHttpClient {

    private static final String SEND_ENDPOINT = "/v3/smtp/email";

    private final RestClient restClient;
    private final String     apiKey;

    public BrevoHttpClient(RestClient.Builder builder,
                           MailSendingProperties mailProps,
                           SmtpProperties smtpProps) {
        this.apiKey     = mailProps.getBrevoApiKey();
        this.restClient = buildClient(builder, mailProps, smtpProps);
    }

    public ResponseEntity<Void> send(BrevoSendEmailRequest request) {
        return restClient.post()
                .uri(SEND_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .header("accept",  "application/json")
                .header("api-key", apiKey)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    // ── construction ──────────────────────────────────────────────────────────

    private RestClient buildClient(RestClient.Builder builder,
                                   MailSendingProperties mailProps,
                                   SmtpProperties smtpProps) {
        if (mailProps.isUseBrevoWithProxy() && StringUtils.hasText(smtpProps.getProxyHost())) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            Proxy.Type type = "HTTP".equalsIgnoreCase(smtpProps.getProxyType())
                    ? Proxy.Type.HTTP : Proxy.Type.SOCKS;
            factory.setProxy(new Proxy(type,
                    new InetSocketAddress(smtpProps.getProxyHost(), smtpProps.getProxyPort())));
            log.info("BrevoHttpClient using {} proxy: {}:{}",
                    type, smtpProps.getProxyHost(), smtpProps.getProxyPort());
            return builder.requestFactory(factory).baseUrl(mailProps.getBrevoBaseUrl()).build();
        }
        return builder.baseUrl(mailProps.getBrevoBaseUrl()).build();
    }
}
