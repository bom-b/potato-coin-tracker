package com.potatonetwork.potatocointracker.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestApiController {

    @GetMapping("/test")
    public String testApi() {
        return "test";
    }
}
