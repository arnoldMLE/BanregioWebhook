package com.mausoleos.PaymentBanregio.dto;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)

public class ResourceData {
    @JsonProperty("@odata.type")
    private String odataType;

    @JsonProperty("@odata.id")
    private String odataId;
    
    @JsonProperty("id")
    private String id;
}
