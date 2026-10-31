package com.moveinsync.roster_sync;

import org.springframework.boot.SpringApplication;

public class TestRosterSyncApplication {

	public static void main(String[] args) {
		SpringApplication.from(RosterSyncApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
