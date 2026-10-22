package com.devbrain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class DevbrainApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevbrainApplication.class, args);
    }
}
