package com.mausoleos.PaymentBanregio.service;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.microsoft.graph.models.Subscription;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.models.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GraphAuthService {

    private final GraphServiceClient graphServiceClient;
    private final TokenCredential tokenCredential;

    @Value("${azure.graph.user-id}")
    private String userId;

    /**
     * Obtiene un token de acceso válido para Microsoft Graph API
     */
    public Mono<String> getAccessToken() {
        return Mono.fromCallable(() -> {
            log.debug("Obteniendo token de acceso para Microsoft Graph");
            
            TokenRequestContext request = new TokenRequestContext()
                    .addScopes("https://graph.microsoft.com/.default");
            
            AccessToken token = tokenCredential.getToken(request).block();
            
            if (token != null) {
                log.debug("Token obtenido exitosamente, expira en: {}", token.getExpiresAt());
                return token.getToken();
            } else {
                throw new RuntimeException("No se pudo obtener el token de acceso");
            }
        });
    }

    /**
     * Verifica la conectividad con Microsoft Graph API
     */
    public Mono<Boolean> testConnection() {
        return Mono.fromCallable(() -> {
            try {
                log.info("Probando conexión con Microsoft Graph API");
                
                // Intenta obtener información básica del usuario
                var user = graphServiceClient.users().byUserId(userId).get();
                
                if (user != null) {
                    log.info("Conexión exitosa. Usuario encontrado: {}", user.getDisplayName());
                    return true;
                } else {
                    log.error("No se pudo obtener información del usuario");
                    return false;
                }
            } catch (Exception e) {
                log.error("Error al probar conexión con Microsoft Graph: {}", e.getMessage(), e);
                return false;
            }
        });
    }

    /**
     * Crea una suscripción de webhook para monitorear correos
     */
    public Mono<Subscription> createEmailSubscription(String notificationUrl) {
        return Mono.fromCallable(() -> {
            try {
                log.info("Creando suscripción de webhook para correos del usuario: {}", userId);
                
                Subscription subscription = new Subscription();
                subscription.setChangeType("created");
                subscription.setNotificationUrl(notificationUrl);
                subscription.setResource("/users/" + userId + "/messages");
                subscription.setExpirationDateTime(OffsetDateTime.now().plusMinutes(4230)); // Máximo permitido
                subscription.setClientState("secretClientValue");

                Subscription createdSubscription = graphServiceClient.subscriptions().post(subscription);
                
                log.info("Suscripción creada exitosamente. ID: {}", createdSubscription.getId());
                return createdSubscription;
                
            } catch (Exception e) {
                log.error("Error al crear suscripción de webhook: {}", e.getMessage(), e);
                throw new RuntimeException("No se pudo crear la suscripción de webhook", e);
            }
        });
    }

    /**
     * Lista las suscripciones activas
     */
    public Mono<List<Subscription>> getActiveSubscriptions() {
        return Mono.fromCallable(() -> {
            try {
                log.debug("Obteniendo suscripciones activas");
                
                var subscriptions = graphServiceClient.subscriptions().get();
                
                if (subscriptions != null && subscriptions.getValue() != null) {
                    log.info("Se encontraron {} suscripciones activas", subscriptions.getValue().size());
                    return subscriptions.getValue();
                } else {
                    log.info("No se encontraron suscripciones activas");
                    return Arrays.asList();
                }
            } catch (Exception e) {
                log.error("Error al obtener suscripciones: {}", e.getMessage(), e);
                throw new RuntimeException("No se pudieron obtener las suscripciones", e);
            }
        });
    }

    /**
     * Obtiene los mensajes más recientes del usuario
     */
    public Mono<List<Message>> getRecentMessages(int top) {
        return Mono.fromCallable(() -> {
            try {
                log.debug("Obteniendo {} mensajes más recientes del usuario: {}", top, userId);
                
                var messages = graphServiceClient.users().byUserId(userId)
                        .messages()
                        .get(config -> {
                            config.queryParameters.top = top;
                            config.queryParameters.orderby = new String[]{"receivedDateTime desc"};
                        });

                if (messages != null && messages.getValue() != null) {
                    log.info("Se obtuvieron {} mensajes", messages.getValue().size());
                    return messages.getValue();
                } else {
                    log.info("No se encontraron mensajes");
                    return Arrays.asList();
                }
            } catch (Exception e) {
                log.error("Error al obtener mensajes: {}", e.getMessage(), e);
                throw new RuntimeException("No se pudieron obtener los mensajes", e);
            }
        });
    }

    /**
     * Elimina una suscripción específica
     */
    public Mono<Boolean> deleteSubscription(String subscriptionId) {
        return Mono.fromCallable(() -> {
            try {
                log.info("Eliminando suscripción: {}", subscriptionId);
                
                graphServiceClient.subscriptions().bySubscriptionId(subscriptionId).delete();
                
                log.info("Suscripción eliminada exitosamente: {}", subscriptionId);
                return true;
                
            } catch (Exception e) {
                log.error("Error al eliminar suscripción {}: {}", subscriptionId, e.getMessage(), e);
                return false;
            }
        });
    }
}