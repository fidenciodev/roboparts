package br.com.roboparts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class RoboPartsApplication {
    public static void main(String[] args) {
        SpringApplication.run(RoboPartsApplication.class, args);
    }
}
