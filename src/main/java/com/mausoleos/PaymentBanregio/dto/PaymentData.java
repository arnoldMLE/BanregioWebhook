package com.mausoleos.PaymentBanregio.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data  // Anotación de Lombok para generar getters, setters, etc.
public class PaymentData {
    private String cuentaOrigen;
    private String claveRastreo;
    private String nombreClienteOrigen; 
    private String conceptoPago;
    private String referencia;
    private String fechaAplicacion;
    private String institucionEmisora;
    private String monto;


    @Override
    public String toString(){
        // Personalizamos el toString para una salida bonita en la consola
         return "--- DATOS DEL PAGO EXTRAÍDOS ---\n" +
               "  Clave de Rastreo:   " + claveRastreo + "\n" +
               "  Concepto de Pago:   " + conceptoPago + "\n" +
               "  Monto:              $" + monto + "\n" +
               "  Fecha de Aplicación:" + fechaAplicacion + "\n" +
               "  Institución Emisora:" + institucionEmisora + "\n" +
               "  Referencia:         " + referencia + "\n" +
               "  Cuenta Origen:      " + cuentaOrigen + "\n" +
               "  Nombre Cliente:       " + nombreClienteOrigen + "\n" +
               "---------------------------------";
    }
}
