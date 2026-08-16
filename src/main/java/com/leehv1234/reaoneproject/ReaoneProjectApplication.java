package com.leehv1234.reaoneproject;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReaoneProjectApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReaoneProjectApplication.class, args);
    }

}
