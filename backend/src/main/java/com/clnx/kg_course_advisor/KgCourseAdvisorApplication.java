package com.clnx.kg_course_advisor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class KgCourseAdvisorApplication {

	public static void main(String[] args) {
		SpringApplication.run(KgCourseAdvisorApplication.class, args);
	}

}
