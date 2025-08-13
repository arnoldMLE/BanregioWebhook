package com.mausoleos.PaymentBanregio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductionWebhookService {

    private final ProductionOAuthService oauthService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    @Value("${azure.graph.user-id}")
    private String userId;

    @Value("${webhook.notification-url}")
    private String notificationUrl;

    @Value("${webhook.auto-create:true}")
    private boolean autoCreateWebhook;

    @Value("${webhook.client-state:PaymentBanregioSecret2024}")
    private String clientState;

    // Webhook management
    private volatile String currentSubscriptionId;
    private volatile OffsetDateTime currentSubscriptionExpiry;
    private static final String GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0";
    private static final int RENEWAL_HOURS_BEFORE_EXPIRY = 24;

    @PostConstruct
    public void initialize() {
        if (!autoCreateWebhook) {
            log.info("🚫 Automatic webhook creation disabled");
            return;
        }

        log.info("🔔 Initializing Production Webhook Service");
        
        // Wait a bit for OAuth service to get initial token
        scheduler.schedule(() -> {
            try {
                initializeWebhooks();
                // Schedule renewal check every 6 hours
                scheduler.scheduleAtFixedRate(this::checkAndRenewSubscription, 6, 6, TimeUnit.HOURS);
            } catch (Exception e) {
                log.error("❌ Error initializing webhooks", e);
            }
        }, 30, TimeUnit.SECONDS);
    }

    /**
     * Initializes webhooks by cleaning up old ones and creating new ones
     */
    public void initializeWebhooks() {
        try {
            log.info("🔔 Initializing webhook subscriptions");
            
            // Clean up existing subscriptions for our resource
            cleanupExistingSubscriptions();
            
            // Create new subscription
            createWebhookSubscription();
            
            log.info("✅ Webhook initialization completed");
            
        } catch (Exception e) {
            log.error("❌ Error during webhook initialization", e);
            throw new RuntimeException("Failed to initialize webhooks", e);
        }
    }

    /**
     * Creates a new webhook subscription
     */
    public void createWebhookSubscription() {
        try {
            log.info("📧 Creating new webhook subscription for user: {}", userId);
            log.info("🌐 Notification URL: {}", notificationUrl);
            
            String subscriptionsUrl = GRAPH_BASE_URL + "/subscriptions";
            
            // Calculate expiration time (maximum allowed: 3 days for mail messages)
            OffsetDateTime expirationTime = OffsetDateTime.now().plusDays(3).minusHours(1);
            
            Map<String, Object> subscriptionRequest = new HashMap<>();
            subscriptionRequest.put("changeType", "created");
            subscriptionRequest.put("notificationUrl", notificationUrl);
            subscriptionRequest.put("resource", "/users/" + userId + "/messages");
            subscriptionRequest.put("expirationDateTime", expirationTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            subscriptionRequest.put("clientState", clientState);
            
            log.info("📋 Subscription request: {}", objectMapper.writeValueAsString(subscriptionRequest));
            
            ResponseEntity<String> response = oauthService.makeAuthenticatedPostRequest(
                subscriptionsUrl, subscriptionRequest);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode subscriptionResponse = objectMapper.readTree(response.getBody());
                
                currentSubscriptionId = subscriptionResponse.get("id").asText();
                String expiryString = subscriptionResponse.get("expirationDateTime").asText();
                currentSubscriptionExpiry = OffsetDateTime.parse(expiryString);
                
                log.info("✅ Webhook subscription created successfully");
                log.info("🆔 Subscription ID: {}", currentSubscriptionId);
                log.info("⏰ Expires at: {}", currentSubscriptionExpiry);
                log.info("🔗 Resource: {}", subscriptionResponse.get("resource").asText());
                log.info("📍 Notification URL: {}", subscriptionResponse.get("notificationUrl").asText());
                
            } else {
                throw new RuntimeException("Failed to create subscription. Status: " + response.getStatusCode());
            }
            
        } catch (Exception e) {
            log.error("❌ Error creating webhook subscription", e);
            throw new RuntimeException("Failed to create webhook subscription", e);
        }
    }

    /**
     * Cleans up existing subscriptions for our resource
     */
    private void cleanupExistingSubscriptions() {
        try {
            log.info("🧹 Cleaning up existing subscriptions");
            
            String subscriptionsUrl = GRAPH_BASE_URL + "/subscriptions";
            ResponseEntity<String> response = oauthService.makeAuthenticatedRequest(subscriptionsUrl);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode subscriptionsResponse = objectMapper.readTree(response.getBody());
                JsonNode subscriptions = subscriptionsResponse.get("value");
                
                if (subscriptions != null && subscriptions.isArray()) {
                    String ourResource = "/users/" + userId + "/messages";
                    int deletedCount = 0;
                    
                    for (JsonNode subscription : subscriptions) {
                        String resource = subscription.get("resource").asText();
                        String subscriptionId = subscription.get("id").asText();
                        
                        if (ourResource.equals(resource)) {
                            log.info("🗑️ Deleting existing subscription: {}", subscriptionId);
                            deleteSubscription(subscriptionId);
                            deletedCount++;
                        }
                    }
                    
                    log.info("✅ Cleaned up {} existing subscriptions", deletedCount);
                } else {
                    log.info("ℹ️ No existing subscriptions found");
                }
            } else {
                log.warn("⚠️ Could not retrieve existing subscriptions. Status: {}", response.getStatusCode());
            }
            
        } catch (Exception e) {
            log.warn("⚠️ Error during subscription cleanup: {}", e.getMessage());
            // Don't throw exception here, as this is cleanup and shouldn't block new subscription creation
        }
    }

    /**
     * Deletes a specific subscription
     */
    private void deleteSubscription(String subscriptionId) {
        try {
            String deleteUrl = GRAPH_BASE_URL + "/subscriptions/" + subscriptionId;
            ResponseEntity<String> response = oauthService.makeAuthenticatedDeleteRequest(deleteUrl);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("✅ Subscription deleted: {}", subscriptionId);
            } else {
                log.warn("⚠️ Failed to delete subscription {}. Status: {}", subscriptionId, response.getStatusCode());
            }
            
        } catch (Exception e) {
            log.warn("⚠️ Error deleting subscription {}: {}", subscriptionId, e.getMessage());
        }
    }

    /**
     * Checks if subscription needs renewal and renews if necessary
     */
    public void checkAndRenewSubscription() {
        try {
            if (currentSubscriptionId == null || currentSubscriptionExpiry == null) {
                log.warn("⚠️ No active subscription found, creating new one");
                createWebhookSubscription();
                return;
            }
            
            OffsetDateTime renewalThreshold = OffsetDateTime.now().plusHours(RENEWAL_HOURS_BEFORE_EXPIRY);
            
            if (currentSubscriptionExpiry.isBefore(renewalThreshold)) {
                log.info("🔄 Subscription expiring soon, renewing...");
                log.info("📅 Current expiry: {}", currentSubscriptionExpiry);
                log.info("📅 Renewal threshold: {}", renewalThreshold);
                
                renewWebhookSubscription();
            } else {
                log.debug("✅ Subscription still valid until: {}", currentSubscriptionExpiry);
            }
            
        } catch (Exception e) {
            log.error("❌ Error during subscription renewal check", e);
        }
    }

    /**
     * Renews the current webhook subscription
     */
    public void renewWebhookSubscription() {
        try {
            log.info("🔄 Renewing webhook subscription: {}", currentSubscriptionId);
            
            String renewUrl = GRAPH_BASE_URL + "/subscriptions/" + currentSubscriptionId;
            
            // Calculate new expiration time
            OffsetDateTime newExpirationTime = OffsetDateTime.now().plusDays(3).minusHours(1);
            
            Map<String, Object> renewalRequest = new HashMap<>();
            renewalRequest.put("expirationDateTime", newExpirationTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            
            ResponseEntity<String> response = oauthService.makeAuthenticatedPostRequest(renewUrl, renewalRequest);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode renewalResponse = objectMapper.readTree(response.getBody());
                String newExpiryString = renewalResponse.get("expirationDateTime").asText();
                currentSubscriptionExpiry = OffsetDateTime.parse(newExpiryString);
                
                log.info("✅ Subscription renewed successfully");
                log.info("🆔 Subscription ID: {}", currentSubscriptionId);
                log.info("⏰ New expiry: {}", currentSubscriptionExpiry);
                
            } else {
                log.warn("⚠️ Subscription renewal failed. Status: {}. Recreating...", response.getStatusCode());
                // If renewal fails, try to create a new subscription
                initializeWebhooks();
            }
            
        } catch (Exception e) {
            log.error("❌ Error renewing subscription, attempting to recreate", e);
            try {
                initializeWebhooks();
            } catch (Exception recreateError) {
                log.error("❌ Failed to recreate subscription after renewal failure", recreateError);
            }
        }
    }

    /**
     * Gets the current webhook subscription status
     */
    public WebhookStatus getWebhookStatus() {
        try {
            if (currentSubscriptionId == null) {
                return WebhookStatus.builder()
                    .active(false)
                    .message("No active subscription")
                    .build();
            }
            
            // Verify subscription still exists by querying Microsoft Graph
            String subscriptionUrl = GRAPH_BASE_URL + "/subscriptions/" + currentSubscriptionId;
            ResponseEntity<String> response = oauthService.makeAuthenticatedRequest(subscriptionUrl);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode subscription = objectMapper.readTree(response.getBody());
                
                return WebhookStatus.builder()
                    .active(true)
                    .subscriptionId(currentSubscriptionId)
                    .expirationDateTime(currentSubscriptionExpiry)
                    .notificationUrl(subscription.get("notificationUrl").asText())
                    .resource(subscription.get("resource").asText())
                    .clientState(subscription.get("clientState").asText())
                    .message("Subscription is active and healthy")
                    .build();
            } else {
                return WebhookStatus.builder()
                    .active(false)
                    .subscriptionId(currentSubscriptionId)
                    .message("Subscription not found in Microsoft Graph")
                    .build();
            }
            
        } catch (Exception e) {
            log.error("Error checking webhook status", e);
            return WebhookStatus.builder()
                .active(false)
                .subscriptionId(currentSubscriptionId)
                .message("Error checking status: " + e.getMessage())
                .build();
        }
    }

    /**
     * Manually recreates all webhooks (useful for admin endpoints)
     */
    public void recreateWebhooks() {
        log.info("🔄 Manual webhook recreation requested");
        initializeWebhooks();
    }

    @lombok.Data
    @lombok.Builder
    public static class WebhookStatus {
        private boolean active;
        private String subscriptionId;
        private OffsetDateTime expirationDateTime;
        private String notificationUrl;
        private String resource;
        private String clientState;
        private String message;
    }
}