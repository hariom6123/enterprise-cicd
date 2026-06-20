package com.example.springboot.counter;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/counter")
public class CounterController {

    private final CounterService service;

    public CounterController(CounterService service) {
        this.service = service;
    }

    @GetMapping
    public CounterResponse get() {
        return new CounterResponse(service.current());
    }

    @PostMapping("/increment")
    public CounterResponse increment() {
        return new CounterResponse(service.increment());
    }

    @PostMapping("/reset")
    public CounterResponse reset() {
        return new CounterResponse(service.reset());
    }

    /** Wire shape of {@code /api/counter} responses. */
    public record CounterResponse(long count) {
    }
}
