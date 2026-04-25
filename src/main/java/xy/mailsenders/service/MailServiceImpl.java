package xy.mailsenders.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import xy.mailsenders.mail.brevo.AnalyticsNotifier;
import xy.mailsenders.mail.brevo.MailSendingReport;
import xy.mailsenders.mail.config.MailSendingProperties;
import xy.mailsenders.mail.domain.MailAttachment;
import xy.mailsenders.mail.domain.BulkMailResult;
import xy.mailsenders.mail.domain.MailFailure;
import xy.mailsenders.mail.domain.MailPayload;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@AllArgsConstructor
public class MailServiceImpl implements MailService {
    private static final Pattern SIMPLE_EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final MailGateway mailGateway;
    private final MailSendingProperties properties;
    private final AnalyticsNotifier analyticsNotifier;


    @Override
    public BulkMailResult sendMails(Collection<MailPayload> mails) {
        if (mails == null || mails.isEmpty()) {
            throw new IllegalArgumentException("mails must not be empty");
        }
        if (mails.size() > properties.getMaxRecipientsPerRequest()) {
            throw new IllegalArgumentException("Maximum recipients per request is " + properties.getMaxRecipientsPerRequest());
        }

        Map<String, MailPayload> uniqueMails = deduplicateByRecipient(mails);
        List<MailFailure> failures = new ArrayList<>();
        List<String> successfulEmails = new ArrayList<>();
        List<String> failedEmails = new ArrayList<>();

        int sentCount = 0;
        long minimumDelayMillis = minimumDelayMillis(properties.getMaxSendRatePerSecond());
        long nextAllowedSendAtMillis = 0L;

        for (MailPayload payload : uniqueMails.values()) {
            validatePayload(payload);
            nextAllowedSendAtMillis = throttle(nextAllowedSendAtMillis, minimumDelayMillis);
            try {
                mailGateway.send(payload);
                sentCount++;
                successfulEmails.add(payload.getTo());
            } catch (RuntimeException ex) {
                failures.add(new MailFailure(payload.getTo(), ex.getMessage()));
                failedEmails.add(payload.getTo());
            }
        }

        BulkMailResult result = BulkMailResult.builder()
                .requestedRecipients(mails.size())
                .uniqueRecipients(uniqueMails.size())
                .sentCount(sentCount)
                .failedCount(failures.size())
                .failures(failures)
                .build();

        sendAnalyticsReport(result, successfulEmails, failedEmails);

        return result;
    }

    private void sendAnalyticsReport(BulkMailResult result, List<String> successfulEmails, List<String> failedEmails) {
        MailSendingReport report = new MailSendingReport(
                result.getUniqueRecipients(),
                result.getSentCount(),
                result.getFailedCount(),
                successfulEmails,
                failedEmails,
                "Batch send completed. Total processed: " + result.getUniqueRecipients()
        );
        analyticsNotifier.sendReport(report);
    }

    private Map<String, MailPayload> deduplicateByRecipient(Collection<MailPayload> mails) {
        Map<String, MailPayload> deduplicated = new LinkedHashMap<>();
        for (MailPayload mail : mails) {
            if (mail == null || !StringUtils.hasText(mail.getTo())) {
                continue;
            }
            String key = mail.getTo().trim().toLowerCase();
            deduplicated.putIfAbsent(key, mail);
        }
        if (deduplicated.isEmpty()) {
            throw new IllegalArgumentException("No valid recipient found in request");
        }
        return deduplicated;
    }

    private void validatePayload(MailPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("mail payload must not be null");
        }
        if (!StringUtils.hasText(payload.getTo())) {
            throw new IllegalArgumentException("recipient address must not be blank");
        }
        if (!StringUtils.hasText(payload.getSubject())) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (!StringUtils.hasText(payload.getBody())) {
            throw new IllegalArgumentException("body must not be blank");
        }
        if (!SIMPLE_EMAIL_PATTERN.matcher(payload.getTo().trim()).matches()) {
            throw new IllegalArgumentException("invalid recipient address: " + payload.getTo());
        }
        if (!CollectionUtils.isEmpty(payload.getAttachments())) {
            for (MailAttachment attachment : payload.getAttachments()) {
                if (attachment == null) {
                    throw new IllegalArgumentException("attachment must not be null");
                }
                if (!StringUtils.hasText(attachment.getFileName())) {
                    throw new IllegalArgumentException("attachment fileName must not be blank");
                }
                if (!StringUtils.hasText(attachment.getBase64Content())) {
                    throw new IllegalArgumentException("attachment content must not be blank");
                }
            }
        }
    }

    private long minimumDelayMillis(double maxSendRatePerSecond) {
        if (maxSendRatePerSecond <= 0) {
            return 0;
        }
        return (long) Math.ceil(1000.0 / maxSendRatePerSecond);
    }

    private long throttle(long nextAllowedSendAtMillis, long minimumDelayMillis) {
        long now = System.currentTimeMillis();
        if (now < nextAllowedSendAtMillis) {
            try {
                Thread.sleep(nextAllowedSendAtMillis - now);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("mail sending interrupted", ex);
            }
        }
        return System.currentTimeMillis() + minimumDelayMillis;
    }
}
