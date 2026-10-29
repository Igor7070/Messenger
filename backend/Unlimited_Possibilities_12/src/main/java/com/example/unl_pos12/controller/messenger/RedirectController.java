package com.example.unl_pos12.controller.messenger;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class RedirectController {
    @GetMapping("/messenger")
    public RedirectView redirectToMessenger() {
        return new RedirectView("https://igor7070.github.io/Messenger/");
    }
}
