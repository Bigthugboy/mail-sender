package xy.mailsenders.mail.smtp.proxy;

public enum ProxyType {
    SOCKS5, HTTP, NONE;

    public static ProxyType from(String value) {
        if (value == null || value.isBlank()) return SOCKS5;
        return switch (value.toUpperCase()) {
            case "HTTP"            -> HTTP;
            case "SOCKS", "SOCKS5" -> SOCKS5;
            default                -> NONE;
        };
    }
}
