package xy.mailsenders.mail.brevo;

import lombok.extern.slf4j.Slf4j;
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
import xy.mailsenders.mail.domain.MailFailure;
import xy.mailsenders.mail.domain.MailPayload;
import xy.mailsenders.service.MailGateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class BrevoMailGateway implements MailGateway {


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
            log.info("Sending email: from={} to={} subject={} bodyLength= {}",
                    request.getSender() == null ? "<none>" : request.getSender().getEmail(),
                    request.getTo() == null || request.getTo().isEmpty() ? "<none>" : request.getTo().get(0).getEmail(),
                    request.getSubject(),
                    (request.getTextContent() != null ? request.getTextContent().length() : (request.getHtmlContent() != null ? request.getHtmlContent().length() : 0))
            );

            response = restClient.post()
                    .uri("/v3/smtp/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("accept", "application/json")
                    .header("api-key", "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")
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Email sent successfully: to={} status={}", payload.getTo(), response.getStatusCode());
            } else {
                log.warn("Non-success response while sending email to {}: status={}", payload.getTo(), response.getStatusCode());
                throw new RuntimeException("Failed to send email: status=" + response.getStatusCode());
            }

        } catch (Exception ex) {
            String recipient = payload.getTo() == null ? "<unknown>" : payload.getTo();
            log.error("Failed to send mail to {}", recipient, ex);
        }
    }

    @Override
    public List<MailFailure> sendAll(Collection<MailPayload> payloads, int concurrency) {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Future<MailFailure>> futures = new ArrayList<>(payloads.size());

        for (MailPayload payload : payloads) {
            futures.add(pool.submit(() -> {
                try {
                    send(payload);
                    return null; // null = success
                } catch (Exception ex) {
                    log.error("Bulk send failure for {}: {}", payload.getTo(), ex.getMessage());
                    return new MailFailure(payload.getTo(), ex.getMessage());
                }
            }));
        }

        pool.shutdown();
        try {
            boolean finished = pool.awaitTermination(5, TimeUnit.MINUTES);
            if (!finished) {
                log.warn("Bulk send timed out — some emails may not have been sent");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bulk mail sending interrupted", e);
        }

        List<MailFailure> failures = new ArrayList<>();
        for (Future<MailFailure> future : futures) {
            try {
                MailFailure failure = future.get();
                if (failure != null) {
                    failures.add(failure);
                }
            } catch (Exception ex) {
                failures.add(new MailFailure("<unknown>", ex.getMessage()));
            }
        }

        log.info("Bulk send complete: total={} failures={}", payloads.size(), failures.size());
        return failures;
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
