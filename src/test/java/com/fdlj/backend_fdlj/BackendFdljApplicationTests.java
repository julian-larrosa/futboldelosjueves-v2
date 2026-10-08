package com.fdlj.backend_fdlj;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BackendFdljApplicationTests {

	@Test
	void contextLoads() {
	}

}
