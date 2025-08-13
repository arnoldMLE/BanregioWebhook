package com.mausoleos.PaymentBanregio.service;


import com.fasterxml.jackson.databind.json.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor

public class ProductionOAuthService {
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @Value("${azure.graph.tenant-id}")
    private String tenantId;
    
    @Value("${azure.graph.client-id}")
    private String clientId;
    
    @Value("${azure.graph.client-secret}")
    private String clientSecret;

    private volatile String currentAccessToken;
    private volatile LocalDateTime tokenExpiresAt;
    private static final int TOKEN_REFRESH_BUFFER_MINUTES = 5;

    public void initialize(){
        log.info("🔧 Initializing Production OAuth Service");
        
        // Get initial token
        refreshAccessToken();
        
        // Schedule automatic token refresh (every 50 minutes for 1-hour tokens)
        scheduler.scheduleAtFixedRate(this::refreshAccessToken, 50, 50, TimeUnit.MINUTES);
        
        log.info("✅ OAuth Service initialized with automatic token refresh");
    }

    public String getValidAccessToken(){
        if (isTokenExpiringSoon()) {
            log.info("🔄 Token expiring soon, refreshing...");
            refreshAccessToken();
        }
        return currentAccessToken;
    }

    public synchronized void refreshAccessToken(){
        try {
            log.info("Requesting new access token");

            String tokenUrl = String.format("https://login.microsoftonline.com/%s/oauth2/v2.0/token", tenantId);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            
            MultiValueMap<String, String> requestBody = new LinkedMultiValueMap<>();
            requestBody.add("client_id", clientId);
            requestBody.add("client_secret", clientSecret);
            requestBody.add("scope", "https://graph.microsoft.com/.default");
            requestBody.add("grant_type", "client_credentials");

            HttpEntity <MultiValueMap<String,String>> request = new HttpEntity<>(requestBody,headers);

            ResponseEntity<String> response = restTemplate.postForEntity(tokenUrl, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode tokenResponse = objectMapper.readTree(response.getBody());

                currentAccessToken = tokenResponse.get("access_token").asText();
                int expiresInSeconds = tokenResponse.get("expires_in").asInt();

                tokenExpiresAt = LocalDateTime.now().plusSeconds(expiresInSeconds);

                log.info("✅ Access token refreshed successfully");
                log.info("🕒 Token expires at: {}", tokenExpiresAt);
            }

            else{
                throw new RuntimeException("Failed to get access token. Status: " + response.getStatusCode());
            }
        
          } catch (Exception e) {
            log.error("❌ Error refreshing access token", e);
            throw new RuntimeException("Failed to refresh access token", e);
        }
    }

     private boolean isTokenExpiringSoon() {
        if (currentAccessToken == null || tokenExpiresAt == null) {
            return true;
        }
        
        LocalDateTime refreshThreshold = LocalDateTime.now().plusMinutes(TOKEN_REFRESH_BUFFER_MINUTES);
        return tokenExpiresAt.isBefore(refreshThreshold);
    }

    public ResponseEntity<String> makeAuthenticatedRequest(String url){
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getValidAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(headers);
        try {
            return restTemplate.exchange(url, HttpMethod.GET, request, String.class);
        } catch (Exception e) {
            log.error("Error making authenticated GET request to: {}", url, e);
            throw new RuntimeException("Failed to make authenticated request", e);
        }
    }

    public ResponseEntity<String> makeAuthenticatedPostRequest(String url, Object requestBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getValidAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);
            
            return restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        } catch (Exception e) {
            log.error("Error making authenticated POST request to: {}", url, e);
            throw new RuntimeException("Failed to make authenticated POST request", e);
        }
    }

    /**
     * Makes an authenticated DELETE request to Microsoft Graph
     */
    public ResponseEntity<String> makeAuthenticatedDeleteRequest(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getValidAccessToken());
        
        HttpEntity<String> request = new HttpEntity<>(headers);
        
        try {
            return restTemplate.exchange(url, HttpMethod.DELETE, request, String.class);
        } catch (Exception e) {
            log.error("Error making authenticated DELETE request to: {}", url, e);
            throw new RuntimeException("Failed to make authenticated DELETE request", e);
        }
    }


    public TokenStatus getTokenStatus(){
        return TokenStatus.builder().hasToken(currentAccessToken != null)
            .expiresAt(tokenExpiresAt).isExpiringSoon(isTokenExpiringSoon())
            .minutesUntilExpiry(tokenExpiresAt != null ? 
                ChronoUnit.MINUTES.between(LocalDateTime.now(), tokenExpiresAt) : 0).build();

    }


    @lombok.Data
    @lombok.Builder
    public static class TokenStatus {
        private boolean hasToken;
        private LocalDateTime expiresAt;
        private boolean isExpiringSoon;
        private long minutesUntilExpiry;
    }
}
