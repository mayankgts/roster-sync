package com.moveinsync.roster_sync;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RosterSyncApplicationTests {

	@Test
	void contextLoads() {
	}

}
