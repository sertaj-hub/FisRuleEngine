package com.fisre.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FisreApplication {

    public static void main(String[] args) {
        SpringApplication.run(FisreApplication.class, args);
    }
}
