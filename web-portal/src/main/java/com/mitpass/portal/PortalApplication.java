package com.mitpass.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The MITPass web portal (:8090): server-rendered pages that call the gateway with the user's token, which never leaves
 * the server-side session (PROJECT.md §3 BFF, docs/DECISIONS.md D9, D15). Thin slices in P3–P5, finished in P6.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortalApplication.class, args);
    }
}
