package xy.mailsenders.service;

import xy.mailsenders.mail.domain.MailPayload;

public interface MailGateway {

    void send(MailPayload payload);
}
