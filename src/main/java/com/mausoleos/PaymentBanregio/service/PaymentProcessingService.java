package com.mausoleos.PaymentBanregio.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.Message;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.mausoleos.PaymentBanregio.dto.*;
import com.mausoleos.PaymentBanregio.model.*;
import com.mausoleos.PaymentBanregio.repository.*;

@Service
public class PaymentProcessingService {
    private static final Logger log = LoggerFactory.getLogger(PaymentProcessingService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    @Autowired private GraphServiceClient graphClient;
    @Autowired private EmailParserService parserService;
    @Autowired private PaymentNotificationRepository repository;
    @Autowired private NetSuiteService netSuiteService;
    @Autowired private ObjectMapper objectMapper;

    @Value("${azure.graph.user-id}")
    private String userId;

    @Transactional
    public void processNotification(String notificationBody) {
        try {
            GraphNotification notification = objectMapper.readValue(notificationBody, GraphNotification.class);
            
            for (NotificationValue value : notification.getValue()) {
                if (value.getResourceData() == null) {
                    log.warn("ResourceData is null for notification value, skipping...");
                    continue;
                }

                String messageId = value.getResourceData().getId();
                log.info("Procesando notificación para el mensaje con ID: {}", messageId);

                // Updated to new SDK v6 syntax
                Message message = graphClient.users().byUserId(userId).messages().byMessageId(messageId).get();

                if (message == null || message.getBody() == null || message.getBody().getContent() == null) {
                    log.warn("El mensaje con ID {} está vacío o no tiene cuerpo.", messageId);
                    continue;
                }

                // Debug logs - Updated property access
                log.info("=== EMAIL DETAILS ===");
                log.info("Subject: {}", message.getSubject());
                log.info("From: {}", message.getFrom() != null ? message.getFrom().getEmailAddress().getAddress() : "Unknown");
                log.info("Content Type: {}", message.getBody().getContentType());

                PaymentData data = parserService.parse(message.getBody().getContent());

                if (data.getClaveRastreo() == null) {
                    log.warn("No se pudo extraer la clave de rastreo del mensaje ID: {}. Saltando.", messageId);
                    continue;
                }

                if (repository.findByClaveRastreo(data.getClaveRastreo()).isPresent()) {
                    log.info("El pago con clave de rastreo {} ya fue procesado. Saltando.", data.getClaveRastreo());
                    continue;
                }

                PaymentNotification nuevaNotificacion = new PaymentNotification();
                nuevaNotificacion.setClaveRastreo(data.getClaveRastreo());
                nuevaNotificacion.setConceptoPago(data.getConceptoPago());
                nuevaNotificacion.setMonto(data.getMonto());
                nuevaNotificacion.setReferencia(data.getReferencia());
                nuevaNotificacion.setCuentaOrigen(data.getCuentaOrigen());
                nuevaNotificacion.setNombreClienteOrigen(data.getNombreClienteOrigen()); // NUEVO CAMPO
                nuevaNotificacion.setInstitucionEmisora(data.getInstitucionEmisora());
                nuevaNotificacion.setStatus(PaymentNotification.ProcessingStatus.RECEIVED);

                // Parse date if available
                if (data.getFechaAplicacion() != null && !data.getFechaAplicacion().trim().isEmpty()) {
                    try {
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
                        nuevaNotificacion.setFechaAplicacion(LocalDateTime.parse(data.getFechaAplicacion(), formatter));
                    } catch (Exception e) {
                        log.warn("Could not parse date '{}': {}", data.getFechaAplicacion(), e.getMessage());
                    }
                }
                nuevaNotificacion.setFechaProcesamiento(LocalDateTime.now());

                PaymentNotification notificacionGuardada = repository.save(nuevaNotificacion);

                log.info("Notificación guardada exitosamente:");
                log.info("  - ID: {}", notificacionGuardada.getId());
                log.info("  - Clave de rastreo: {}", notificacionGuardada.getClaveRastreo());
                log.info("  - Cuenta origen: {}", notificacionGuardada.getCuentaOrigen());
                log.info("  - Nombre cliente: {}", notificacionGuardada.getNombreClienteOrigen());
                log.info("  - Monto: ${}", notificacionGuardada.getMonto());

                // Mark message as read - Updated to new SDK v6 syntax
                try {
                    Message readMessage = new Message();
                    readMessage.setIsRead(true);
                    graphClient.users().byUserId(userId).messages().byMessageId(messageId).patch(readMessage);
                    log.info("Mensaje {} marcado como leído.", messageId);
                } catch (Exception e) {
                    log.error("Error al marcar el mensaje {} como leído: {}", messageId, e.getMessage());
                }

                // Process with NetSuite if needed
                try {
                    // TODO: Add NetSuite processing here
                    // netSuiteService.processPayment(notificacionGuardada);
                } catch (Exception e) {
                    log.error("Error procesando pago en NetSuite para clave de rastreo {}: {}", 
                        data.getClaveRastreo(), e.getMessage());
                }
            }      
        } catch (Exception e) {
            log.error("Error al procesar la notificación del webhook", e);
            throw new RuntimeException("Failed to process notification", e);
        }
    }
}