package com.mausoleos.PaymentBanregio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;



@SpringBootApplication
@EnableAsync
public class PaymentBanregioApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentBanregioApplication.class, args);
	}

}
