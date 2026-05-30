package com.example.order;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class OrderService {

    private final RestTemplate restTemplate;

    public OrderService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String getOrder(Long id) {
        // Calls svc-user via RestTemplate: GET /api/users/{id}
        String user = restTemplate.getForObject("http://svc-user/api/users/" + id, String.class);
        return "order-" + id + " user=" + user;
    }
}
