package xy.mailsenders.mail.senderNet;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import xy.mailsenders.mail.config.MailSendingProperties;
import xy.mailsenders.mail.domain.MailPayload;
import xy.mailsenders.service.SenderMailGateway;

import java.util.*;



@Service
@Slf4j
public class SenderNetGatewayImp implements SenderMailGateway {

    private final RestClient restClient;
    private final MailSendingProperties properties;

    @Value("${SENDER_NET_API_KEY}") // Updated key name
    private String apiKey;

    public SenderNetGatewayImp(RestClient.Builder restClientBuilder, MailSendingProperties properties) {
        // Ensure properties.getSenderNetUrl() returns "https://api.sender.net/v2"
        this.restClient = restClientBuilder
                .baseUrl(properties.getSenderNetUrl())
                .build();
        this.properties = properties;
    }

    @Override
    public void send(MailPayload payload) {
        // Build the Sender.net specific request
        Map<String, Object> request = buildRequest(payload);

        try {
            log.info("Sending email via Sender.net: from={} to={} subject={}",
                    properties.getFromAddress(), payload.getTo(), payload.getSubject());

            ResponseEntity<Void> response = restClient.post()
                    .uri("/message/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Email sent successfully via Sender.net to {}", payload.getTo());
            } else {
                log.warn("Sender.net returned non-success status: {}", response.getStatusCode());
                throw new RuntimeException("Sender.net failed: " + response.getStatusCode());
            }

        } catch (Exception ex) {
            log.error("Failed to send mail via Sender.net to {}", payload.getTo(), ex);
        }
    }



    private Map<String, Object> buildRequest(MailPayload payload) {
        Map<String, Object> request = new HashMap<>();

        // Sender.net 'from' object
        request.put("from", Map.of(
                "email", "cox@darwinofficesupports.online",
                "name", "darwin cox"
        ));

        // Sender.net 'to' object (single recipient)
        request.put("to", Map.of(
                "email", payload.getTo().trim(),
                "name", "" // Add name if available in payload
        ));

        request.put("subject", payload.getSubject().trim());

        // Content handling
        if (payload.isHtml()) {
            request.put("html", payload.getBody());
            request.put("plain", stripHtml(payload.getBody()));
        } else {
            request.put("plain", payload.getBody());
        }

        // Attachments: Sender.net supports URL-based attachments or Base64
        if (!CollectionUtils.isEmpty(payload.getAttachments())) {
            // Note: Sender.net usually expects an object or specific array format for base64
            List<Map<String, String>> attachments = payload.getAttachments().stream()
                    .map(a -> Map.of(
                            "name", a.getFileName(),
                            "content", a.getBase64Content()
                    )).toList();
            request.put("attachments", attachments);
        }

        return request;
    }

    private String stripHtml(String html) {
        if (!StringUtils.hasText(html)) return "";
        String text = html.replaceAll("(?s)<style.*?>.*?</style>", "")
                .replaceAll("(?s)<script.*?>.*?</script>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</?p\\s*/?>", "\n")
                .replaceAll("<[^>]*>", "");
        return text.replace("&nbsp;", " ").replace("&amp;", "&").trim();
    }
}

