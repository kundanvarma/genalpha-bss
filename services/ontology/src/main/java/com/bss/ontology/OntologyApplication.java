package com.bss.ontology;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The Operational Semantic Registry: the machine-readable representation of the
 * GenAlpha Operational Ontology. It serves what exists, what can be done and
 * under which conditions; it checks and executes governed actions through the
 * mapped TM Forum capability with the caller's own rights; and it explains all
 * of it in words — to people in the console, to applications through the
 * generated SDK, and to AI agents through MCP.
 */
@SpringBootApplication
@EnableScheduling
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.bss.ontology.client.OntologyProperties.class)
public class OntologyApplication {

    public static void main(String[] args) {
        SpringApplication.run(OntologyApplication.class, args);
    }
}
