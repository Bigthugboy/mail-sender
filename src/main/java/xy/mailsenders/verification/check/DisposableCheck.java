package xy.mailsenders.verification.check;

import org.springframework.stereotype.Component;
import xy.mailsenders.verification.domain.CheckResult;
import xy.mailsenders.verification.domain.CheckType;

import java.util.Set;

/**
 * Check 2 — Disposable / throwaway domain filter.
 * No network I/O — in-process set lookup.
 *
 * OCP: extend the list by adding to DISPOSABLE_DOMAINS.
 * In production, load from a file or a maintained API (e.g. Kickbox, DisposableMail).
 */
@Component
public class DisposableCheck implements EmailCheck {

    private static final Set<String> DISPOSABLE_DOMAINS = Set.of(
            "mailinator.com", "guerrillamail.com", "guerrillamail.net",
            "guerrillamail.org", "guerrillamail.biz", "guerrillamail.de",
            "guerrillamail.info", "10minutemail.com", "10minutemail.net",
            "10minutemail.org", "yopmail.com", "yopmail.fr", "trashmail.com",
            "trashmail.me", "trashmail.net", "trashmail.org", "trashmail.at",
            "tempmail.com", "tempmail.net", "temp-mail.org", "temp-mail.io",
            "sharklasers.com", "guerrillamailblock.com", "grr.la", "spam4.me",
            "fakeinbox.com",  "mailnull.com", "spamgourmet.com",
            "spamgourmet.net", "spamgourmet.org", "dispostable.com",
            "mailnesia.com", "maildrop.cc", "discard.email", "spamthisplease.com",
            "getairmail.com", "filzmail.com", "binkmail.com", "bobmail.info",
            "inoutmail.de", "inoutmail.eu", "inoutmail.info", "inoutmail.net",
            "throwam.com", "mt2015.com", "mt2014.com", "dingbone.com",
            "fudgerub.com", "lookugly.com", "dontreg.com"
    );

    @Override
    public CheckResult run(String email) {
        String domain = domainOf(email);
        if (DISPOSABLE_DOMAINS.contains(domain))
            return CheckResult.fail(CheckType.DISPOSABLE,
                    "Disposable/throwaway email domain: " + domain);
        return CheckResult.pass(CheckType.DISPOSABLE);
    }

    static String domainOf(String email) {
        if (email == null || !email.contains("@")) return "";
        return email.substring(email.indexOf('@') + 1).toLowerCase().trim();
    }
}
