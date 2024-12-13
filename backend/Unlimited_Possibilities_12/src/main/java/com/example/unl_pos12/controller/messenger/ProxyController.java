package com.example.unl_pos12.controller.messenger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/messenger")
public class ProxyController {

    private final RestTemplate restTemplate;

    @Autowired
    public ProxyController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/**")
    public ResponseEntity<String> proxyResources(@PathVariable(value = "path") String path) {
        String resourceUrl = "https://igor7070.github.io/Messenger/" + path;
        System.out.println("Requesting resource: " + resourceUrl);

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(resourceUrl, String.class);
            System.out.println("Response status: " + response.getStatusCode());
            return response;
        } catch (Exception e) {
            System.err.println("Error fetching resource: " + e.getMessage());
            return ResponseEntity.status(500).body("Error fetching resource");
        }
    }

    @GetMapping
    public ResponseEntity<String> proxyHome() {
        String resourceUrl = "https://igor7070.github.io/Messenger/";
        return restTemplate.getForEntity(resourceUrl, String.class);
    }
}
