package xy.mailsenders.mail.brevo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    private static final Logger logger = LoggerFactory.getLogger(BrevoMailGateway.class);

    private final RestClient restClient;
    private final MailSendingProperties properties;

    @Value("${BREVO_API_KEY}")
    private String keyyy;

    public BrevoMailGateway(RestClient.Builder restClientBuilder, MailSendingProperties properties) {
        this.restClient = restClientBuilder
                .baseUrl(properties.getBrevoBaseUrl())
                .build();
        this.properties = properties;
    }

    @Override
    public void send(MailPayload payload) {
        BrevoSendEmailRequest request = buildRequest(payload);

        ResponseEntity<Void> response;
        try {
            // Log a brief summary of the outgoing request (do not log full bodies/attachments)
            logger.info("Sending email: from={} to={} subject={} bodyLength= {}",
                    request.getSender() == null ? "<none>" : request.getSender().getEmail(),
                    request.getTo() == null || request.getTo().isEmpty() ? "<none>" : request.getTo().get(0).getEmail(),
                    request.getSubject(),
                    (request.getTextContent() != null ? request.getTextContent().length() : (request.getHtmlContent() != null ? request.getHtmlContent().length() : 0))
            );

            response = restClient.post()
                    .uri("/v3/smtp/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("accept", "application/json")
                    .header("api-key", keyyy)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();

            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("Email sent successfully: to={} status={}", payload.getTo(), response.getStatusCode());
            } else {
                logger.warn("Non-success response while sending email to {}: status={}", payload.getTo(), response.getStatusCode());
                throw new RuntimeException("Failed to send email: status=" + response.getStatusCode());
            }

        } catch (Exception ex) {
            String recipient = payload.getTo() == null ? "<unknown>" : payload.getTo();
            logger.error("Failed to send mail to {}", recipient, ex);
        }
    }

    private BrevoSendEmailRequest buildRequest(MailPayload payload) {
        BrevoSendEmailRequest request = new BrevoSendEmailRequest();
        String from = StringUtils.hasText(properties.getFromAddress()) ? properties.getFromAddress().trim() : properties.getAnalyticsRecipient();
        request.setSender(new BrevoEmailContact(from, properties.getAnalyticsSenderName()));
        request.setTo(List.of(new BrevoEmailContact(payload.getTo().trim(), null)));
        request.setSubject(payload.getSubject().trim());

        if (payload.isHtml()) {
            request.setHtmlContent(payload.getBody());
            request.setTextContent(stripHtml(payload.getBody()));
        } else {
            request.setTextContent(payload.getBody());
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

    private String stripHtml(String html) {
        if (!StringUtils.hasText(html)) {
            return "";
        }
        String text = html.replaceAll("(?s)<style.*?>.*?</style>", "");
        text = text.replaceAll("(?s)<script.*?>.*?</script>", "");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</?p\\s*/?>", "\n");
        text = text.replaceAll("<[^>]*>", "");
        text = text.replace("&nbsp;", " ")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"");
        return text.replaceAll("\\n\\s*\\n+", "\n\n").trim();
    }

}
