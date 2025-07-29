package com.mausoleos.PaymentBanregio.service;

import com.mausoleos.PaymentBanregio.model.PaymentNotification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NetSuiteService {

    private static final Logger log = LoggerFactory.getLogger(NetSuiteService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Inyección de credenciales desde application.yml
    @Value("${netsuite.api.account-id}")
    private String accountId;
    @Value("${netsuite.api.consumer-key}")
    private String consumerKey;
    @Value("${netsuite.api.consumer-secret}")
    private String consumerSecret;
    @Value("${netsuite.api.token-id}")
    private String tokenId;
    @Value("${netsuite.api.token-secret}")
    private String tokenSecret;
    @Value("${netsuite.api.base-url:suitetalk.api.netsuite.com}")
    private String baseUrl;

    private final WebClient webClient;

    public NetSuiteService() {
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Busca una factura por número de referencia (tranid) y aplica el pago
     */
    public void processPaymentNotification(PaymentNotification payment) {
        log.info("Procesando notificación de pago - Clave de rastreo: {}, Concepto: {}, Monto: {}", 
                payment.getClaveRastreo(), payment.getConceptoPago(), payment.getMonto());

        try {
            // 1. Buscar la factura por el concepto de pago (número de factura)
            String invoiceInternalId = findInvoiceByReference(payment.getConceptoPago());
            
            if (invoiceInternalId == null) {
                log.warn("No se encontró factura con referencia: {}", payment.getConceptoPago());
                return;
            }

            // 2. Aplicar el pago a la factura encontrada
            applyPaymentToInvoice(invoiceInternalId, payment);
            
        } catch (Exception e) {
            log.error("Error procesando pago para clave de rastreo: {}", payment.getClaveRastreo(), e);
            throw new RuntimeException("Error en el procesamiento del pago", e);
        }
    }

    /**
     * Busca una factura en NetSuite usando SuiteQL
     */
    private String findInvoiceByReference(String conceptoPago) throws GeneralSecurityException {
        String suiteQLEndpoint = String.format("https://%s.%s/services/rest/query/v1/suiteql",
                accountId.replace("_", "-"), baseUrl);

        // Query SuiteQL para buscar la factura por tranid (número de factura)
        String query = String.format(
            "SELECT id FROM transaction WHERE tranid = '%s' AND type = 'CustInvc' AND status != 'CustInvc:V'",
            conceptoPago.replace("'", "''") // Escape single quotes
        );

        String jsonPayload = String.format("{\"q\": \"%s\"}", query);

        try {
            String authHeader = generateOAuth1Header("POST", suiteQLEndpoint);
            
            String response = webClient.post()
                    .uri(suiteQLEndpoint)
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .bodyValue(jsonPayload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("Respuesta búsqueda factura: {}", response);

            // Parsear la respuesta JSON
            JsonNode jsonResponse = objectMapper.readTree(response);
            JsonNode items = jsonResponse.get("items");
            
            if (items != null && items.isArray() && items.size() > 0) {
                return items.get(0).get("id").asText();
            }
            
            return null;
            
        } catch (WebClientResponseException e) {
            log.error("Error buscando factura. Status: {}. Body: {}", 
                    e.getRawStatusCode(), e.getResponseBodyAsString(), e);
            throw new RuntimeException("Error buscando factura en NetSuite", e);
        } catch (Exception e) {
            log.error("Error parseando respuesta de búsqueda de factura", e);
            throw new RuntimeException("Error procesando respuesta de NetSuite", e);
        }
    }

    /**
     * Aplica un pago a una factura específica usando la transformación
     */
    private void applyPaymentToInvoice(String invoiceInternalId, PaymentNotification payment) 
            throws GeneralSecurityException {
        
        String transformEndpoint = String.format(
            "https://%s.%s/services/rest/record/v1/invoice/%s/!transform/customerPayment",
            accountId.replace("_", "-"), baseUrl, invoiceInternalId);

        // Formatear la fecha de aplicación
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String fechaAplicacion = payment.getFechaAplicacion().format(formatter);

        // Construir el payload del pago
        String jsonPayload = String.format("""
            {
                "payment": %s,
                "trandate": "%s",
                "memo": "Pago SPEI - Clave: %s - Ref: %s - Origen: %s",
                "custbody_clave_rastreo": "%s",
                "custbody_referencia_banco": "%s",
                "custbody_cuenta_origen": "%s",
                "custbody_institucion_emisora": "%s"
            }""",
            payment.getMonto(),
            fechaAplicacion,
            payment.getClaveRastreo(),
            payment.getReferencia(),
            payment.getCuentaOrigen(),
            payment.getClaveRastreo(),
            payment.getReferencia(),
            payment.getCuentaOrigen(),
            payment.getInstitucionEmisora()
        );

        try {
            String authHeader = generateOAuth1Header("POST", transformEndpoint);

            String response = webClient.post()
                    .uri(transformEndpoint)
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .bodyValue(jsonPayload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("Pago aplicado exitosamente en NetSuite. Factura: {}, Clave rastreo: {}, Respuesta: {}", 
                    invoiceInternalId, payment.getClaveRastreo(), response);

        } catch (WebClientResponseException e) {
            log.error("Error aplicando pago en NetSuite. Status: {}. Body: {}", 
                    e.getRawStatusCode(), e.getResponseBodyAsString(), e);
            throw new RuntimeException("Error aplicando pago en NetSuite", e);
        }
    }

    /**
     * Genera la cabecera de autorización OAuth 1.0a completa.
     */
    private String generateOAuth1Header(String httpMethod, String url) throws GeneralSecurityException {
        // --- Paso 1: Recolectar todos los parámetros OAuth ---
        Map<String, String> oauthParams = new TreeMap<>();
        oauthParams.put("oauth_consumer_key", consumerKey);
        oauthParams.put("oauth_token", tokenId);
        oauthParams.put("oauth_nonce", UUID.randomUUID().toString().replace("-", ""));
        oauthParams.put("oauth_timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        oauthParams.put("oauth_signature_method", "HMAC-SHA256");
        oauthParams.put("oauth_version", "1.0");

        // --- Paso 2: Crear la "base string" para la firma ---
        String parameterString = oauthParams.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));

        String baseString = httpMethod.toUpperCase() + "&" + encode(url) + "&" + encode(parameterString);
        log.debug("Base string para firma: {}", baseString);

        // --- Paso 3: Crear la clave para firmar ---
        String signingKey = encode(consumerSecret) + "&" + encode(tokenSecret);

        // --- Paso 4: Generar la firma usando HMAC-SHA256 ---
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signatureBytes = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signatureBytes);

        // --- Paso 5: Construir la cabecera de autorización final ---
        String authHeader = "OAuth realm=\"" + accountId.replace("-", "_") + "\", " +
                oauthParams.entrySet().stream()
                        .map(entry -> encode(entry.getKey()) + "=\"" + encode(entry.getValue()) + "\"")
                        .collect(Collectors.joining(", ")) + ", " +
                "oauth_signature=\"" + encode(signature) + "\"";

        log.debug("Cabecera de Autorización Generada: {}", authHeader);
        return authHeader;
    }

    /**
     * Método de utilidad para codificar en formato URL-safe RFC3986.
     */
    private String encode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (Exception e) {
            throw new RuntimeException("Fallo en la codificación URL.", e);
        }
    }

   
}