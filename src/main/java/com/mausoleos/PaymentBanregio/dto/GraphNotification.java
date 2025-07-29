package com.mausoleos.PaymentBanregio.dto;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GraphNotification {
    private NotificationValue[] value;
}
