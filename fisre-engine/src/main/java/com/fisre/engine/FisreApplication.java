package com.fisre.engine;

import com.fisre.engine.config.StartupChecks;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FisreApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FisreApplication.class);
        app.addListeners((ApplicationEnvironmentPreparedEvent e) -> StartupChecks.validate(e.getEnvironment()));
        app.run(args);
    }
}
