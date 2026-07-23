package com.kevin.mcp;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SpringBootApplication
public class McpApplication {

	private static final Logger log = LoggerFactory.getLogger(McpApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(McpApplication.class, args);
	}

	@RequestMapping("/")
	@ResponseBody
	public String home() {
		return "Enterprise AI Platform";
	}

	@PostConstruct
	public void init() {
		log.info("McpApplication started");
	}
}
