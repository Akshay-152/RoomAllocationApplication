package com.exam.allocation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RoomAllocationApplication {

	public static void main(String[] args) {
		SpringApplication.run(RoomAllocationApplication.class, args);
	}

}
