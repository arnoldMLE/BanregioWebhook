package com.mausoleos.PaymentBanregio.repository;

import com.mausoleos.PaymentBanregio.model.PaymentNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentNotificationRepository extends JpaRepository<PaymentNotification, Long>{
    Optional<PaymentNotification> findByClaveRastreo(String claveRastreo);
}

