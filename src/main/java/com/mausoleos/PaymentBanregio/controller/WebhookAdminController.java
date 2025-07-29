package com.mausoleos.PaymentBanregio.controller;

import com.mausoleos.PaymentBanregio.service.WebhookStartupService;
import com.mausoleos.PaymentBanregio.service.GraphAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Function;

@RestController
@RequestMapping("/api/v1/admin/webhooks")
@RequiredArgsConstructor
@Slf4j

public class WebhookAdminController {
    private final WebhookStartupService webhookStartupService;
    private final GraphAuthService graphAuthService;

    @Value("${webhook.notification-url}")
    private String notificationUrl;

    @Value("${server.port:8080}")
    private String serverPort;

    @GetMapping("/status")
    public ResponseEntity<WebhookStartupService.WebhookStatus> getWebhookStatus(){
        try {
            WebhookStartupService.WebhookStatus status = webhookStartupService.getWebhookStatus();
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Error obteniendo estado de webhooks: {}", e.getMessage(), e);
            WebhookStartupService.WebhookStatus errorStatus = WebhookStartupService.WebhookStatus.builder()
                    .active(false)
                    .message("Error: " + e.getMessage())
                    .build();
            return ResponseEntity.internalServerError().body(errorStatus);
        }
    }

    @PostMapping("/recreate")
    public ResponseEntity<Map<String, String>> recreateWebhooks() {
        try {
            log.info("Solicitud manual de recreación de webhooks");
            webhookStartupService.recreateWebhooks();
            return ResponseEntity.ok(Map.of(
                "message", "Webhooks recreados exitosamente",
                "timestamp", String.valueOf(System.currentTimeMillis())
            ));
        } catch (Exception e) {
            log.error("Error recreando webhooks: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Error recreando webhooks: " + e.getMessage(),
                "timestamp", String.valueOf(System.currentTimeMillis())
            ));
        }
    }

    @GetMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection() {
        return graphAuthService.testConnection()
                .map(success -> {
                    Map<String, Object> response = Map.of(
                            "connected", success,
                            "timestamp", System.currentTimeMillis(),
                            "notificationUrl", notificationUrl,
                            "serverPort", serverPort
                    );
                    
                    if (success) {
                        return ResponseEntity.ok(response);
                    } else {
                        return ResponseEntity.internalServerError().body(response);
                    }
                })
                .block();
    }
        /**
     * Lista todas las suscripciones activas
     */
    @GetMapping("/subscriptions")
    public ResponseEntity<?> listSubscriptions() {
        return graphAuthService.getActiveSubscriptions()
                .map(subscriptions -> {
                    log.info("Obtenidas {} suscripciones", subscriptions.size());
                    return ResponseEntity.ok(subscriptions);
                })
                .onErrorMap((Function<? super Throwable, ? extends Throwable>) ResponseEntity.internalServerError().body(
                    Map.of("error", "Error obteniendo suscripciones")
                ))
                .block();
    }

    /**
     * Elimina una suscripción específica
     */
    @DeleteMapping("/subscriptions/{subscriptionId}")
    public ResponseEntity<Map<String, String>> deleteSubscription(@PathVariable String subscriptionId) {
        return graphAuthService.deleteSubscription(subscriptionId)
                .map(success -> {
                    if (success) {
                        return ResponseEntity.ok(Map.of(
                            "message", "Suscripción eliminada exitosamente",
                            "subscriptionId", subscriptionId
                        ));
                    } else {
                        return ResponseEntity.internalServerError().body(Map.of(
                            "error", "Error eliminando suscripción",
                            "subscriptionId", subscriptionId
                        ));
                    }
                })
                .block();
    }

    /**
     * Obtiene información de configuración actual
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfiguration() {
        Map<String, Object> config = Map.of(
            "notificationUrl", notificationUrl,
            "serverPort", serverPort,
            "timestamp", System.currentTimeMillis()
        );
        
        return ResponseEntity.ok(config);
    }
}
