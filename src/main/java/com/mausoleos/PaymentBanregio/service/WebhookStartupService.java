package com.mausoleos.PaymentBanregio.service;

import com.microsoft.graph.models.Subscription;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;


@Service
@RequiredArgsConstructor
@Slf4j

public class WebhookStartupService {

    private final GraphServiceClient graphServiceClient;

    @Value("${azure.graph.user-id}")
    private String userId;

    @Value("${webhook.notification-url}")
    private String notificationUrl;

    @Value("${webhook.auto-create:true}")
    private boolean autoCreateWebhook;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeWebhooks(){
        if (!autoCreateWebhook) {
            log.info("Auto-creación de webhooks deshabilitada");
            return;
        }

        log.info("=== Inicializando webhooks automáticamente ===");

        testGraphConnection();
    }

     private void testGraphConnection() {
        try {
            var user = graphServiceClient.users().byUserId(userId).get();
            if (user != null) {
                log.info("✅ Conexión con Microsoft Graph exitosa. Usuario: {}", user.getDisplayName());
            } else {
                throw new RuntimeException("No se pudo obtener información del usuario");
            }
        } catch (Exception e) {
            log.error("❌ Error conectando con Microsoft Graph", e);
            throw new RuntimeException("Error de conexión con Microsoft Graph", e);
        }
    }

    /**
     * Limpia suscripciones antiguas o expiradas
     */
    private void cleanupOldSubscriptions() {
        try {
            log.info("🧹 Limpiando suscripciones antiguas...");
            
            var subscriptions = graphServiceClient.subscriptions().get();
            
            if (subscriptions != null && subscriptions.getValue() != null) {
                List<Subscription> activeSubscriptions = subscriptions.getValue();
                log.info("Encontradas {} suscripciones existentes", activeSubscriptions.size());
                
                for (Subscription subscription : activeSubscriptions) {
                    // Verificar si la suscripción es para nuestro recurso
                    String expectedResource = "/users/" + userId + "/messages";
                    
                    if (expectedResource.equals(subscription.getResource())) {
                        // Verificar si está expirada o próxima a expirar (menos de 1 hora)
                        OffsetDateTime expiration = subscription.getExpirationDateTime();
                        OffsetDateTime oneHourFromNow = OffsetDateTime.now().plusHours(1);
                        
                        if (expiration.isBefore(oneHourFromNow)) {
                            log.info("🗑️ Eliminando suscripción expirada/próxima a expirar: {}", subscription.getId());
                            try {
                                graphServiceClient.subscriptions().bySubscriptionId(subscription.getId()).delete();
                                log.info("✅ Suscripción eliminada: {}", subscription.getId());
                            } catch (Exception e) {
                                log.warn("⚠️ Error eliminando suscripción {}: {}", subscription.getId(), e.getMessage());
                            }
                        } else {
                            log.info("📧 Suscripción activa encontrada (expira: {}): {}", 
                                   expiration, subscription.getId());
                            // Si hay una suscripción activa válida, no crear otra
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Error durante limpieza de suscripciones: {}", e.getMessage());
        }
    }

    /**
     * Crea una nueva suscripción de webhook
     */
    private void createWebhookSubscription() {
        try {
            log.info("📧 Creando nueva suscripción de webhook...");
            log.info("URL de notificación: {}", notificationUrl);
            
            Subscription subscription = new Subscription();
            subscription.setChangeType("created");
            subscription.setNotificationUrl(notificationUrl);
            subscription.setResource("/users/" + userId + "/messages");
            subscription.setExpirationDateTime(OffsetDateTime.now().plusMinutes(4230)); // ~3 días (máximo permitido)
            subscription.setClientState("PaymentBanregioSecret2024");

            Subscription createdSubscription = graphServiceClient.subscriptions().post(subscription);
            
            log.info("✅ Suscripción creada exitosamente:");
            log.info("   - ID: {}", createdSubscription.getId());
            log.info("   - Recurso: {}", createdSubscription.getResource());
            log.info("   - URL: {}", createdSubscription.getNotificationUrl());
            log.info("   - Expira: {}", createdSubscription.getExpirationDateTime());
            
        } catch (Exception e) {
            log.error("❌ Error creando suscripción de webhook", e);
            throw new RuntimeException("Error creando webhook", e);
        }
    }

    /**
     * Método manual para recrear webhooks (útil para endpoints de administración)
     */
    public void recreateWebhooks() {
        log.info("🔄 Recreando webhooks manualmente...");
        cleanupOldSubscriptions();
        createWebhookSubscription();
        log.info("✅ Recreación de webhooks completada");
    }

    /**
     * Obtiene el estado actual de los webhooks
     */
    public WebhookStatus getWebhookStatus() {
        try {
            var subscriptions = graphServiceClient.subscriptions().get();
            
            if (subscriptions != null && subscriptions.getValue() != null) {
                String expectedResource = "/users/" + userId + "/messages";
                
                for (Subscription subscription : subscriptions.getValue()) {
                    if (expectedResource.equals(subscription.getResource())) {
                        return WebhookStatus.builder()
                                .active(true)
                                .subscriptionId(subscription.getId())
                                .expirationDateTime(subscription.getExpirationDateTime())
                                .notificationUrl(subscription.getNotificationUrl())
                                .resource(subscription.getResource())
                                .build();
                    }
                }
            }
            
            return WebhookStatus.builder()
                    .active(false)
                    .message("No hay suscripciones activas")
                    .build();
                    
        } catch (Exception e) {
            return WebhookStatus.builder()
                    .active(false)
                    .message("Error obteniendo estado: " + e.getMessage())
                    .build();
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class WebhookStatus {
        private boolean active;
        private String subscriptionId;
        private OffsetDateTime expirationDateTime;
        private String notificationUrl;
        private String resource;
        private String message;
    }

}
