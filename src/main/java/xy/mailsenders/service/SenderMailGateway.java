package xy.mailsenders.service;

import xy.mailsenders.mail.domain.MailFailure;
import xy.mailsenders.mail.domain.MailPayload;

import java.util.Collection;
import java.util.List;

public interface SenderMailGateway {
    void send(MailPayload payload);


}
