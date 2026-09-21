package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationFilesTest {

    @Test
    void coreMigrationFilesShouldExist() {
        Path dir = Paths.get("src/main/resources/db/migration");
        assertTrue(Files.isDirectory(dir), "migration dir should exist");
        for (String name : new String[]{"V1__schema_core.sql", "V2__schema_marketing.sql",
                "V3__seed_base.sql", "V4__seed_product.sql"}) {
            assertTrue(Files.exists(dir.resolve(name)), name + " should exist");
        }
    }
}
