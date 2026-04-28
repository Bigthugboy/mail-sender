package xy.mailsenders.mail.resend;

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
import xy.mailsenders.service.ResendMailGateWay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Sends transactional emails via Resend's HTTP API.
 *
 * Single send  → POST /emails          (used by send())
 * Bulk send    → POST /emails/batch    (used by sendAll(), up to 100 per request)
 *
 * Resend batch limit is 100 emails per request.
 * sendAll() automatically chunks larger lists into groups of 100.
 */
@Service
public  class ResendMailGatewayImpl implements ResendMailGateWay {

    private static final Logger logger = LoggerFactory.getLogger(ResendMailGatewayImpl.class);

    private static final String RESEND_BASE_URL = "https://api.resend.com";
    private static final int BATCH_LIMIT = 100;

    private final RestClient restClient;
    private final MailSendingProperties properties;

    @Value("${RESEND_API_KEY}")
    private String apiKey;

    public ResendMailGatewayImpl(RestClient.Builder restClientBuilder, MailSendingProperties properties) {
        this.restClient = restClientBuilder
                .baseUrl(RESEND_BASE_URL)
                .build();
        this.properties = properties;
    }

    // -------------------------------------------------------------------------
    // Single send  (satisfies MailGateway contract; also used internally)
    // -------------------------------------------------------------------------

    @Override
    public void send(MailPayload payload) {
        ResendEmailRequest request = buildRequest(payload);

        logger.info("Sending single email via Resend: to={} subject={}", payload.getTo(), payload.getSubject());

        try {
            restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();

            logger.info("Email sent successfully to {}", payload.getTo());
        } catch (Exception ex) {
            logger.error("Failed to send email to {}: {}", payload.getTo(), ex.getMessage());
            throw new RuntimeException("Resend single send failed for " + payload.getTo(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Bulk send  — true batch via POST /emails/batch, chunked at 100
    // -------------------------------------------------------------------------



    /**
     * Sends all payloads using Resend's batch endpoint.
     * Each chunk of up to 100 emails is sent in a single HTTP request,
     * so 200 emails = 2 requests, 500 emails = 5 requests, etc.
     *
     * @param payloads    emails to send
     * @param concurrency ignored — Resend handles parallelism server-side
     * @return list of failures (empty = all succeeded)
     */
    @Override
    public List<MailFailure> sendAll(Collection<MailPayload> payloads, int concurrency) {
        List<MailPayload> payloadList = new ArrayList<>(payloads);
        List<MailFailure> failures = new ArrayList<>();

        List<List<MailPayload>> chunks = chunk(payloadList, BATCH_LIMIT);
        logger.info("Sending {} emails in {} batch request(s)", payloadList.size(), chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            List<MailPayload> chunk = chunks.get(i);
            logger.info("Sending batch {}/{} ({} emails)", i + 1, chunks.size(), chunk.size());
            failures.addAll(sendBatch(chunk));
        }

        logger.info("Bulk send complete: total={} failures={}", payloadList.size(), failures.size());
        return failures;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Sends one chunk (max 100) to POST /emails/batch.
     * Returns failures for any email in the chunk that didn't get an ID back.
     */
    private List<MailFailure> sendBatch(List<MailPayload> chunk) {
        List<ResendEmailRequest> requests = chunk.stream()
                .map(this::buildRequest)
                .toList();

        List<MailFailure> failures = new ArrayList<>();

        try {
            ResponseEntity<ResendBatchResponse> response = restClient.post()
                    .uri("/emails/batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(requests)
                    .retrieve()
                    .toEntity(ResendBatchResponse.class);

            ResendBatchResponse body = response.getBody();

            if (body == null || CollectionUtils.isEmpty(body.getData())) {
                // Whole batch failed — mark every recipient
                logger.error("Resend batch returned empty response for {} emails", chunk.size());
                for (MailPayload payload : chunk) {
                    failures.add(new MailFailure(payload.getTo(), "Empty response from Resend batch API"));
                }
                return failures;
            }

            // Match results back to payloads by position
            List<ResendBatchResponse.ResendEmailResult> results = body.getData();
            for (int i = 0; i < chunk.size(); i++) {
                MailPayload payload = chunk.get(i);
                ResendBatchResponse.ResendEmailResult result = i < results.size() ? results.get(i) : null;

                if (result == null || !StringUtils.hasText(result.getId())) {
                    logger.warn("No message ID returned for recipient {}", payload.getTo());
                    failures.add(new MailFailure(payload.getTo(), "No message ID in Resend response"));
                } else {
                    logger.info("Email queued for {}: messageId={}", payload.getTo(), result.getId());
                }
            }

        } catch (Exception ex) {
            logger.error("Resend batch request failed: {}", ex.getMessage());
            // Mark the whole chunk as failed
            for (MailPayload payload : chunk) {
                failures.add(new MailFailure(payload.getTo(), ex.getMessage()));
            }
        }

        return failures;
    }

    /** Builds a Resend request object from a MailPayload */
    private ResendEmailRequest buildRequest(MailPayload payload) {
        ResendEmailRequest request = new ResendEmailRequest();

        // "Name <email>" format
        String senderName = properties.getAnalyticsSenderName();
        String senderEmail = StringUtils.hasText(properties.getFromAddress())
                ? properties.getFromAddress().trim()
                : properties.getAnalyticsRecipient();
        request.setFrom(senderName + " <" + senderEmail + ">");

        request.setTo(List.of(payload.getTo().trim()));
        request.setSubject(payload.getSubject().trim());

        if (payload.isHtml()) {
            request.setHtml(payload.getBody());
            request.setText(stripHtml(payload.getBody()));
        } else {
            request.setText(payload.getBody());
        }

        if (StringUtils.hasText(properties.getUnsubscribeMailto())) {
            request.setHeaders(Map.of(
                    "List-Unsubscribe", "<mailto:" + properties.getUnsubscribeMailto().trim() + ">"
            ));
        }

        if (!CollectionUtils.isEmpty(payload.getAttachments())) {
            request.setAttachments(payload.getAttachments().stream()
                    .map(a -> new ResendAttachment(a.getFileName(), a.getBase64Content()))
                    .toList());
        }

        return request;
    }

    /** Splits a list into consecutive sublists of at most {@code size} elements */
    private static <T> List<List<T>> chunk(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }

    /** Strips HTML tags to produce a plain-text fallback */
    private static String stripHtml(String html) {
        if (!StringUtils.hasText(html)) return "";
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
