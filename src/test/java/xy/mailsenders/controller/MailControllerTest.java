package xy.mailsenders.controller;

import org.junit.jupiter.api.Test;
import xy.mailsenders.mail.api.BulkMailResponse;
import xy.mailsenders.mail.api.SendMailsRequest;
import xy.mailsenders.mail.domain.BulkMailResult;
import xy.mailsenders.mail.domain.MailPayload;
import xy.mailsenders.service.MailService;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MailControllerTest {

    @Test
    void send_ShouldDelegateToServiceAndReturnMappedResponse() {
        StubMailService stubMailService = new StubMailService();
        MailController mailController = new MailController(stubMailService);
        SendMailsRequest request = new SendMailsRequest(
                List.of(new MailPayload("user@example.com", "subject", "body", false))
        );

        BulkMailResponse response = mailController.send(request);

        assertEquals(1, stubMailService.receivedMails.size());
        assertEquals("user@example.com", stubMailService.receivedMails.get(0).getTo());
        assertEquals(1, response.getRequestedRecipients());
        assertEquals(1, response.getUniqueRecipients());
        assertEquals(1, response.getSentCount());
        assertEquals(0, response.getFailedCount());
    }

    @Test
    void onIllegalArgument_ShouldReturnErrorMap() {
        MailController mailController = new MailController(mails -> null);
        Map<String, String> error = mailController.onIllegalArgument(new IllegalArgumentException("bad request"));
        assertEquals("bad request", error.get("error"));
    }

    private static class StubMailService implements MailService {
        private List<MailPayload> receivedMails;

        @Override
        public BulkMailResult sendMails(Collection<MailPayload> mails) {
            this.receivedMails = List.copyOf(mails);
            return BulkMailResult.builder()
                    .requestedRecipients(1)
                    .uniqueRecipients(1)
                    .sentCount(1)
                    .failedCount(0)
                    .failures(List.of())
                    .build();
        }
    }
}
