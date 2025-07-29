package com.mausoleos.PaymentBanregio.config;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class GraphClientConfig {
    
    @Value("${azure.graph.tenant-id}")
    private String tenantId;
    
    @Value("${azure.graph.client-id}")
    private String clientId;
    
    @Value("${azure.graph.client-secret}")
    private String clientSecret;

    @Bean
    public TokenCredential tokenCredential() {
        log.info("Configurando credenciales de Azure AD para tenant: {}", tenantId);
        
        if (tenantId == null || clientId == null || clientSecret == null) {
            log.error("Azure AD graph properties are not configured correctly. Please check your application.properties/yml file.");
            throw new IllegalStateException("Azure AD configuration is missing for MS Graph.");
        }
        
        return new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .build();
    }

    @Bean
    public GraphServiceClient graphServiceClient(TokenCredential tokenCredential) {
        log.info("Inicializando Microsoft Graph Service Client");
        
        // New SDK v6+ syntax - much simpler!
        return new GraphServiceClient(tokenCredential);
    }
}