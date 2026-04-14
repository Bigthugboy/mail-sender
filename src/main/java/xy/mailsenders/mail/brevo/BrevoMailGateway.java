package xy.mailsenders.mail.brevo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import xy.mailsenders.mail.config.MailSendingProperties;
import xy.mailsenders.mail.domain.MailPayload;
import xy.mailsenders.service.MailGateway;

import java.util.List;
import java.util.Map;

@Service
public class BrevoMailGateway implements MailGateway {

    private final RestClient restClient;
    private final MailSendingProperties properties;

    @Value("${app.mail.from-address}")
    private String defaultSenderEmail;

    public BrevoMailGateway(RestClient.Builder restClientBuilder, MailSendingProperties properties) {
        this.restClient = restClientBuilder
                .baseUrl(properties.getBrevoBaseUrl())
                .build();
        this.properties = properties;
    }

    @Override
    public void send(MailPayload payload) {
        BrevoSendEmailRequest request = buildRequest(payload);

        restClient.post()
                .uri("/v3/smtp/email")
                .contentType(MediaType.APPLICATION_JSON)
                .header("accept", "application/json")
                .header("api-key", properties.getBrevoApiKey())
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    private BrevoSendEmailRequest buildRequest(MailPayload payload) {
        BrevoSendEmailRequest request = new BrevoSendEmailRequest();
        request.setSender(new BrevoEmailContact(defaultSenderEmail, "admin"));
        request.setTo(List.of(new BrevoEmailContact(payload.getTo().trim(), null)));
        request.setSubject(payload.getSubject().trim());

        if (payload.isHtml()) {
            request.setHtmlContent(payload.getBody());
        } else {
            request.setTextContent(payload.getBody());
        }

        if (StringUtils.hasText(properties.getReplyTo())) {
            request.setReplyTo(new BrevoEmailContact(properties.getReplyTo().trim(), null));
        }
        if (StringUtils.hasText(properties.getUnsubscribeMailto())) {
            request.setHeaders(Map.of("List-Unsubscribe", "<mailto:" + properties.getUnsubscribeMailto().trim() + ">"));
        }
        if (!CollectionUtils.isEmpty(payload.getAttachments())) {
            request.setAttachment(payload.getAttachments().stream()
                    .map(attachment -> new BrevoAttachment(attachment.getFileName(), attachment.getBase64Content()))
                    .toList());
        }

        return request;
    }
}
