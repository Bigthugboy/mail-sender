package xy.mailsenders.verification.domain;

/** Every check has a stable identity used in scoring and reporting. */
public enum CheckType {
    SYNTAX,
    MX_RECORD,
    DISPOSABLE,
    ROLE_ADDRESS,
    DNSBL,
    SMTP_PROBE,
    CATCH_ALL
}
