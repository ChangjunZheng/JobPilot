package com.jobpilot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.jobpilot.mapper")
public class JobPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobPilotApplication.class, args);
    }
}
