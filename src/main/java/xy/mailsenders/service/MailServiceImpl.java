package xy.mailsenders.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import xy.mailsenders.mail.brevo.BrevoMailGateway;
import xy.mailsenders.mail.config.MailSendingProperties;
import xy.mailsenders.mail.domain.BulkMailResult;
import xy.mailsenders.mail.domain.MailAttachment;
import xy.mailsenders.mail.domain.MailFailure;
import xy.mailsenders.mail.domain.MailPayload;
import xy.mailsenders.mail.smtp.SmtpMailGateway;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class MailServiceImpl implements MailService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_CONCURRENCY = 100;
    private final MailSendingProperties props;
    private final BrevoMailGateway      brevoGateway;
    private final SmtpMailGateway       smtpGateway;


    @Override
    public BulkMailResult sendMails(Collection<MailPayload> mails) {
        if (mails == null || mails.isEmpty())
            throw new IllegalArgumentException("mails must not be empty");
        if (mails.size() > props.getMaxRecipientsPerRequest())
            throw new IllegalArgumentException(
                    "Maximum recipients per request is " + props.getMaxRecipientsPerRequest());

        Map<String, MailPayload> unique = deduplicate(mails);
        unique.values().forEach(this::validate);

        MailGateway gateway = resolveGateway();
        log.info("Gateway: {} — sending to {} unique recipients",
                gateway.getClass().getSimpleName(), unique.size());

        int concurrency = Math.min(unique.size(), MAX_CONCURRENCY);
        List<MailFailure> failures = gateway.sendAll(unique.values(), concurrency);

        Set<String> failed = failures.stream()
                .map(MailFailure::getRecipient).collect(Collectors.toSet());

        return BulkMailResult.builder()
                .requestedRecipients(mails.size())
                .uniqueRecipients(unique.size())
                .sentCount(unique.size() - failures.size())
                .failedCount(failures.size())
                .failures(failures)
                .build();
    }

    // ── gateway selection (OCP: add flag + branch, never change existing) ────

    private MailGateway resolveGateway() {
        if (props.isUseBrevoOnly() || props.isUseBrevoWithApiKey())
            return brevoGateway;
        return smtpGateway; // default
    }

    // ── deduplication ────────────────────────────────────────────────────────

    private Map<String, MailPayload> deduplicate(Collection<MailPayload> mails) {
        Map<String, MailPayload> map = new LinkedHashMap<>();
        for (MailPayload m : mails) {
            if (m == null || !StringUtils.hasText(m.getTo())) continue;
            map.putIfAbsent(m.getTo().trim().toLowerCase(), m);
        }
        if (map.isEmpty())
            throw new IllegalArgumentException("No valid recipient found in request");
        return map;
    }

    // ── validation ───────────────────────────────────────────────────────────

    private void validate(MailPayload payload) {
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
        requireText(payload.getTo(),      "recipient address");
        requireText(payload.getSubject(), "subject");
        requireText(payload.getBody(),    "body");
        if (!EMAIL_PATTERN.matcher(payload.getTo().trim()).matches())
            throw new IllegalArgumentException("invalid recipient address: " + payload.getTo());
        if (!CollectionUtils.isEmpty(payload.getAttachments())) {
            for (MailAttachment mailAttachment : payload.getAttachments()) {
                if (mailAttachment == null) throw new IllegalArgumentException("attachment must not be null");
                requireText(mailAttachment.getFileName(),     "attachment fileName");
                requireText(mailAttachment.getBase64Content(),"attachment content");
            }
        }
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value))
            throw new IllegalArgumentException(field + " must not be blank");
    }
}
