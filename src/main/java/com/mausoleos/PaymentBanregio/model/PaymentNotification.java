package com.mausoleos.PaymentBanregio.model;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name= "notificaciones_pago")
@Data

public class PaymentNotification {
  @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
   
    @Column(nullable = false, unique = true)
    private String claveRastreo;

    private String cuentaOrigen;


    @Column(name = "nombre_cliente_origen")
    private String nombreClienteOrigen;

    private String conceptoPago;
    private String referencia;
    private String institucionEmisora;
    
    private String monto;

    private LocalDateTime fechaAplicacion;
    private LocalDateTime fechaProcesamiento;

    @Enumerated(EnumType.STRING)
    private ProcessingStatus status;

    public enum ProcessingStatus{
        RECEIVED,
        PROCESSED_IN_NETSUITE,
        PARSING_FAILED,
        NETSUITE_FAILED
    }

}
