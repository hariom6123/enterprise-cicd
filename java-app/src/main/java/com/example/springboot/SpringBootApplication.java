package com.example.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * <p>The Dockerfile's CMD invokes
 * {@code org.springframework.boot.loader.launch.JarLauncher} via
 * {@code java $JAVA_OPTS ...}, so the {@code spring-boot-maven-plugin}'s
 * {@code repackage} goal (configured in {@code pom.xml}) must produce a Spring
 * Boot fat jar that exposes that launcher class.
 */
@SpringBootApplication
public class SpringBootApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootApplication.class, args);
    }
}
