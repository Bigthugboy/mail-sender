package xy.mailsenders.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping({"/", "/mail-sender", "/mail-sender.html"})
    public String index() {
        return "mail-sender";
    }
}
