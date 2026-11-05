package com.cognihire.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class CogniHireApplication {
  public static void main(String[] args) { SpringApplication.run(CogniHireApplication.class, args); }
}
