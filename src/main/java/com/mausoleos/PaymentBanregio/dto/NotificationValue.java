package com.mausoleos.PaymentBanregio.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mausoleos.PaymentBanregio.dto.ResourceData;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotificationValue {
    private String subscriptionId;
    private String clientState;
    private String resource;
    private ResourceData resourceData;
}
