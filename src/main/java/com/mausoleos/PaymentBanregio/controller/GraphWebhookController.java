package com.mausoleos.PaymentBanregio.controller;

import com.mausoleos.PaymentBanregio.service.PaymentProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.CompletableFuture;


@RestController
@RequestMapping("/api/v1/webhooks")
public class GraphWebhookController {
    private static final Logger log = LoggerFactory.getLogger(GraphWebhookController.class);

    @Autowired
    private PaymentProcessingService processingService;

    @PostMapping("/notifications")
    public ResponseEntity<String> handleGraphNotification(
            @RequestParam(value = "validationToken", required = false) String validationToken,
            @RequestBody(required = false) String notificationBody
            )
            {
                  if (validationToken != null) {
                    log.info("Token de validación de webhook recibido. Respondiendo para confirmar.");
                    return new ResponseEntity<>(validationToken, HttpStatus.OK);
            }

            CompletableFuture.runAsync(()->{
                 try {
                processingService.processNotification(notificationBody);
            } catch (Exception e) {
                log.error("Error no controlado durante el procesamiento del webhook.", e);
            }
            });

            return new ResponseEntity<>(HttpStatus.ACCEPTED);
            }

}
