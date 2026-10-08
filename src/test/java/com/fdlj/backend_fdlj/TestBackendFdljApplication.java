package com.fdlj.backend_fdlj;

import org.springframework.boot.SpringApplication;

public class TestBackendFdljApplication {

	public static void main(String[] args) {
		SpringApplication.from(BackendFdljApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
