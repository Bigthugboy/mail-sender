package xy.mailsenders.controller;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import xy.mailsenders.mail.api.BulkMailResponse;
import xy.mailsenders.mail.api.SendMailsRequest;
import xy.mailsenders.mail.domain.BulkMailResult;
import xy.mailsenders.service.MailService;

import java.util.Map;

@RestController
@AllArgsConstructor
@RequestMapping("/api/mails")
public class MailController {
    private final MailService mailService;

    @PostMapping("/send")
    public BulkMailResponse send(@Valid @RequestBody SendMailsRequest request) {
        BulkMailResult result = mailService.sendMails(request.getMails());
        return BulkMailResponse.from(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onIllegalArgument(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage());
    }
}
