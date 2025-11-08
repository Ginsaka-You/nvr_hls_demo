package com.example.nvr.tcb.web;

import com.example.nvr.tcb.model.AlertEvent;
import com.example.nvr.tcb.service.AlertPublisherService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/demo/alert")
@Validated
public class DemoAlertController {

    private final AlertPublisherService publisherService;

    public DemoAlertController(AlertPublisherService publisherService) {
        this.publisherService = publisherService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> publishAlert(@Valid DemoAlertRequest request,
                                                            @RequestPart(value = "snapshot", required = false) MultipartFile snapshot) throws IOException {
        AlertEvent event = request.toEvent();
        byte[] imageBytes = snapshot != null && !snapshot.isEmpty() ? snapshot.getBytes() : null;
        String id = publisherService.publish(event, imageBytes);
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("id", id);
        return ResponseEntity.ok(resp);
    }
}

