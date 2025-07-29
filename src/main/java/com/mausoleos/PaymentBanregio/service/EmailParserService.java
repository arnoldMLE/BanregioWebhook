package com.mausoleos.PaymentBanregio.service;

import com.mausoleos.PaymentBanregio.dto.PaymentData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EmailParserService {
    
    private static final Logger log = LoggerFactory.getLogger(EmailParserService.class);
    
    // Patrón para extraer cuenta origen y nombre del cliente
    // Formato: "Cuenta origen *****8016 JUANA ELVIRA CHAPARRO LOYA"
    private static final Pattern CUENTA_ORIGEN_PATTERN = Pattern.compile("Cuenta origen\\s+([*\\d]+)\\s+([A-ZÁÉÍÓÚÑ\\s]+?)(?=\\s+Cuenta destino|\\s+Cantidad|$)", Pattern.CASE_INSENSITIVE);
    
    // Patrones para los demás campos
    private static final Pattern CLAVE_RASTREO_PATTERN = Pattern.compile("Clave de rastreo\\s+([A-Z0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONCEPTO_PATTERN = Pattern.compile("Concepto de pago\\s+([^\\s]+(?:\\s+[^\\s]+)*?)(?:\\s+Referencia|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCIA_PATTERN = Pattern.compile("Referencia\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FECHA_PATTERN = Pattern.compile("Fecha de aplicaci[oó]n\\s+(\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTITUCION_PATTERN = Pattern.compile("Instituci[oó]n emisora\\s+([^\\s]+(?:\\s+[^\\s]+)*?)(?:\\s+[A-Z][a-z]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CANTIDAD_PATTERN = Pattern.compile("Cantidad\\s+\\$([\\d,]+\\.\\d{2})", Pattern.CASE_INSENSITIVE);
    
    // Patrones alternativos basados en el HTML original
    private static final Pattern CUENTA_HTML_PATTERN = Pattern.compile("<p[^>]*>([*\\d]+)</p>\\s*<p[^>]*>([A-ZÁÉÍÓÚÑ\\s.,]+)</p>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAVE_HTML_PATTERN = Pattern.compile("Clave de rastreo</p>\\s*<p[^>]*>([A-Z0-9]+)</p>", Pattern.CASE_INSENSITIVE);
    
    // Fallback patterns
    private static final Pattern SPIN_PATTERN = Pattern.compile("(SPIN\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MBAN_PATTERN = Pattern.compile("(MBAN\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\$([\\d,]+\\.\\d{2})");
    
    public PaymentData parse(String emailBody) {
        if (emailBody == null || emailBody.isEmpty()) {
            log.warn("Email body is null or empty");
            return new PaymentData();
        }
        
        log.info("=== EMAIL PARSER DEBUG ===");
        log.info("Email body length: {}", emailBody.length());
        
        PaymentData data = new PaymentData();
        
        // Primero intentar extraer del HTML original antes de limpiar
        extractFromOriginalHtml(emailBody, data);
        
        // Luego limpiar HTML y normalizar para otros campos
        String cleanedBody = removeHtmlTags(emailBody);
        String normalizedBody = cleanedBody.replace("\r\n", " ").replace("\r", " ").replace("\n", " ");
        normalizedBody = normalizedBody.replaceAll("\\s+", " ").trim();
        
        log.info("Normalized body: {}", normalizedBody);
        
        // Extraer campos restantes si no se obtuvieron del HTML
        if (data.getCuentaOrigen() == null) {
            extractCuentaOrigenFromNormalized(normalizedBody, data);
        }
        
        if (data.getClaveRastreo() == null) {
            data.setClaveRastreo(extractValue(normalizedBody, CLAVE_RASTREO_PATTERN, "Clave de rastreo"));
        }
        
        data.setConceptoPago(extractValue(normalizedBody, CONCEPTO_PATTERN, "Concepto de pago"));
        data.setReferencia(extractValue(normalizedBody, REFERENCIA_PATTERN, "Referencia"));
        data.setFechaAplicacion(extractValue(normalizedBody, FECHA_PATTERN, "Fecha de aplicación"));
        data.setInstitucionEmisora(extractValue(normalizedBody, INSTITUCION_PATTERN, "Institución emisora"));
        
        String montoStr = extractValue(normalizedBody, CANTIDAD_PATTERN, "Cantidad");
        if (montoStr != null) {
            data.setMonto(montoStr.replace(",", ""));
        }
        
        // Fallback patterns
        applyFallbackPatterns(normalizedBody, data);
        
        log.info("=== FINAL PARSED RESULTS ===");
        log.info("Clave Rastreo: {}", data.getClaveRastreo());
        log.info("Cuenta Origen: {}", data.getCuentaOrigen());
        log.info("Nombre Cliente: {}", data.getNombreClienteOrigen());
        log.info("Concepto Pago: {}", data.getConceptoPago());
        log.info("Monto: {}", data.getMonto());
        log.info("Referencia: {}", data.getReferencia());
        log.info("Institución Emisora: {}", data.getInstitucionEmisora());
        log.info("Fecha Aplicación: {}", data.getFechaAplicacion());
        log.info("=== END PARSING ===");
        
        return data;
    }
    
    private void extractFromOriginalHtml(String html, PaymentData data) {
        log.info("Attempting extraction from original HTML...");
        
        // Buscar patrón de cuenta origen en HTML
        Matcher cuentaMatcher = CUENTA_HTML_PATTERN.matcher(html);
        if (cuentaMatcher.find()) {
            data.setCuentaOrigen(cuentaMatcher.group(1).trim());
            data.setNombreClienteOrigen(cuentaMatcher.group(2).trim());
            log.info("✓ Found from HTML - Cuenta: '{}', Nombre: '{}'", 
                data.getCuentaOrigen(), data.getNombreClienteOrigen());
        }
        
        // Buscar clave de rastreo en HTML
        Matcher claveMatcher = CLAVE_HTML_PATTERN.matcher(html);
        if (claveMatcher.find()) {
            data.setClaveRastreo(claveMatcher.group(1).trim());
            log.info("✓ Found from HTML - Clave de rastreo: '{}'", data.getClaveRastreo());
        }
    }
    
    private void extractCuentaOrigenFromNormalized(String normalizedBody, PaymentData data) {
        Matcher matcher = CUENTA_ORIGEN_PATTERN.matcher(normalizedBody);
        if (matcher.find()) {
            data.setCuentaOrigen(matcher.group(1).trim());
            data.setNombreClienteOrigen(matcher.group(2).trim());
            log.info("✓ Found from normalized - Cuenta: '{}', Nombre: '{}'", 
                data.getCuentaOrigen(), data.getNombreClienteOrigen());
        }
    }
    
    private void applyFallbackPatterns(String normalizedBody, PaymentData data) {
        // Fallback para clave de rastreo
        if (data.getClaveRastreo() == null) {
            log.info("Main pattern failed, trying fallback patterns...");
            String spinCode = extractValue(normalizedBody, SPIN_PATTERN, "SPIN Code");
            String mbanCode = extractValue(normalizedBody, MBAN_PATTERN, "MBAN Code");
            
            if (spinCode != null) {
                data.setClaveRastreo(spinCode);
            } else if (mbanCode != null) {
                data.setClaveRastreo(mbanCode);
            }
        }
        
        // Fallback para monto
        if (data.getMonto() == null) {
            String amountFallback = extractValue(normalizedBody, AMOUNT_PATTERN, "Amount fallback");
            if (amountFallback != null) {
                data.setMonto(amountFallback.replace(",", ""));
            }
        }
    }
    
    private String removeHtmlTags(String html) {
        return html.replaceAll("<[^>]+>", " ")
                  .replaceAll("&nbsp;", " ")
                  .replaceAll("&amp;", "&")
                  .replaceAll("&lt;", "<")
                  .replaceAll("&gt;", ">")
                  .replaceAll("\\s+", " ")
                  .trim();
    }
    
    private String extractValue(String text, Pattern pattern, String fieldName) {
        try {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String value = matcher.group(1).trim();
                log.info("✓ Found {}: '{}'", fieldName, value);
                return value.isEmpty() ? null : value;
            } else {
                log.warn("✗ Could not find '{}' in text", fieldName);
                return null;
            }
        } catch (Exception e) {
            log.warn("Error extracting '{}': {}", fieldName, e.getMessage());
            return null;
        }
    }
}