package com.kevin.mcp;

import com.kevin.mcp.registry.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.util.GsonUtil;
import jakarta.annotation.PostConstruct;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@MapperScan("com.kevin.mcp.mapper")
@RestController
@SpringBootApplication
public class McpApplication {

	private static final Logger log = LoggerFactory.getLogger(McpApplication.class);

	private final PrivateMcpToolSchemaRegistry privateMcpToolSchemaRegistry;

    public McpApplication(PrivateMcpToolSchemaRegistry privateMcpToolSchemaRegistry) {
        this.privateMcpToolSchemaRegistry = privateMcpToolSchemaRegistry;
    }

    public static void main(String[] args) {
		SpringApplication.run(McpApplication.class, args);
	}

	@RequestMapping("/")
	@ResponseBody
	public String home() {
		return "Enterprise AI Platform";
	}

	@RequestMapping("schema")
	@ResponseBody
	public String jsonSchema() {
        return GsonUtil.toJson(this.privateMcpToolSchemaRegistry.getPlanningSchemas());
    }

	@PostConstruct
	public void init() {
		log.info("McpApplication started");
	}
}
