package com.example.connect_sphere;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("integration")
@SpringBootTest(properties= "spring.main.lazy-initialization=false")
class ConnectSphereApplicationTests {

	@Test
	void contextLoads() {
	}

}
