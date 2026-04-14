package xy.mailsenders.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xy.mailsenders.mail.config.MailSendingProperties;
import xy.mailsenders.mail.domain.BulkMailResult;
import xy.mailsenders.mail.domain.MailPayload;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MailServiceImplTest {

    private FakeMailGateway fakeMailGateway;
    private MailServiceImpl mailService;

    @BeforeEach
    void setUp() {
        MailSendingProperties properties = new MailSendingProperties();
        properties.setFromAddress("no-reply@example.com");
        properties.setBrevoApiKey("test-key");
        properties.setBrevoBaseUrl("https://api.brevo.com");
        properties.setMaxRecipientsPerRequest(1000);
        properties.setMaxSendRatePerSecond(1000);

        fakeMailGateway = new FakeMailGateway();
        mailService = new MailServiceImpl(fakeMailGateway, properties);
    }

    @Test
    void sendMails_ShouldDeduplicateAndReturnFailureSummary() {
        MailPayload first = new MailPayload("user1@example.com", "subject", "body", false);
        MailPayload duplicate = new MailPayload("user1@example.com", "subject-2", "body-2", true);
        MailPayload failing = new MailPayload("user2@example.com", "subject", "body", false);
        fakeMailGateway.failingRecipient = "user2@example.com";

        BulkMailResult result = mailService.sendMails(List.of(first, duplicate, failing));

        assertEquals(3, result.getRequestedRecipients());
        assertEquals(2, result.getUniqueRecipients());
        assertEquals(1, result.getSentCount());
        assertEquals(1, result.getFailedCount());
        assertEquals("user2@example.com", result.getFailures().get(0).getRecipient());
        assertEquals(2, fakeMailGateway.sentRecipients.size());
    }

    @Test
    void sendMails_ShouldRejectRequestOverLimit() {
        MailPayload payload = new MailPayload("user@example.com", "subject", "body", false);
        List<MailPayload> mails = java.util.Collections.nCopies(1001, payload);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> mailService.sendMails(mails)
        );

        assertEquals("Maximum recipients per request is 1000", exception.getMessage());
    }

    private static class FakeMailGateway implements MailGateway {
        private final List<String> sentRecipients = new ArrayList<>();
        private String failingRecipient;

        @Override
        public void send(MailPayload payload) {
            sentRecipients.add(payload.getTo());
            if (payload.getTo().equals(failingRecipient)) {
                throw new RuntimeException("Brevo failure");
            }
        }
    }
}
