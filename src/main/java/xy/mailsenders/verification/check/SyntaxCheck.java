package xy.mailsenders.verification.check;

import org.springframework.stereotype.Component;
import xy.mailsenders.verification.domain.CheckResult;
import xy.mailsenders.verification.domain.CheckType;

import java.util.regex.Pattern;

/**
 * Check 1 — RFC 5322 syntax validation.
 * No network I/O — pure string evaluation.
 */
@Component
public class SyntaxCheck implements EmailCheck {

    // Simplified RFC 5322 pattern — covers all real-world addresses
    private static final Pattern RFC5322 = Pattern.compile(
            "^[a-zA-Z0-9!#$%&'*+/=?^_`{|}~.-]+@[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z]{2,}$"
    );

    @Override
    public CheckResult run(String email) {
        if (email == null || email.isBlank())
            return CheckResult.fail(CheckType.SYNTAX, "Email address is blank");
        if (email.length() > 254)
            return CheckResult.fail(CheckType.SYNTAX, "Email exceeds 254 characters (RFC 5321)");
        String local = email.contains("@") ? email.split("@")[0] : "";
        if (local.length() > 64)
            return CheckResult.fail(CheckType.SYNTAX, "Local part exceeds 64 characters (RFC 5321)");
        if (!RFC5322.matcher(email).matches())
            return CheckResult.fail(CheckType.SYNTAX, "Invalid email format: " + email);
        return CheckResult.pass(CheckType.SYNTAX);
    }
}
