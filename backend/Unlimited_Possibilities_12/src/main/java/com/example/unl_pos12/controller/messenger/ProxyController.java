package com.example.unl_pos12.controller.messenger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/messenger")
public class ProxyController {
    private final RestTemplate restTemplate;
    @Autowired
    public ProxyController(RestTemplate customRestTemplate) {
        this.restTemplate = customRestTemplate;
    }
    @GetMapping
    @ResponseBody
    public String proxyMessenger() {
        String url = "https://igor7070.github.io/Messenger/";
        return restTemplate.getForObject(url, String.class);
    }
}
