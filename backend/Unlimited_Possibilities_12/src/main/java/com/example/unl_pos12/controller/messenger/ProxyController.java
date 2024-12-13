package com.example.unl_pos12.controller.messenger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/messenger")
public class ProxyController {
    private final RestTemplate restTemplate;

    @Autowired
    public ProxyController(RestTemplate customRestTemplate) {
        this.restTemplate = customRestTemplate;
    }

    @GetMapping("/**")
    @ResponseBody
    public ResponseEntity<String> proxyResources(@PathVariable(value = "path") String path) {
        String resourceUrl = "https://igor7070.github.io/Messenger/" + path;
        return restTemplate.getForEntity(resourceUrl, String.class);
    }

    @GetMapping
    @ResponseBody
    public ResponseEntity<String> proxyHome() {
        String resourceUrl = "https://igor7070.github.io/Messenger/";
        return restTemplate.getForEntity(resourceUrl, String.class);
    }
}
