package com.ticketmaster.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * User profile, preferences and saved payment-method references
 * (wiki/projects/user-service.md's Target Design).
 *
 * Deliberately absent: any read of booking/ticket purchase history
 * (booking-service and ticket-service do not exist yet), any real
 * payment-service integration (payment-service does not exist yet — see
 * paymentmethods/AddPaymentMethodRequest's javadoc), and any gRPC surface
 * (ADR-023 covers that for later; this slice needs no internal calls).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
