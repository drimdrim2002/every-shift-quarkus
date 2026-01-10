package org.acme.solver;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@QuarkusTest
public class SolverRunnerTest {

    @Inject
    SolverRunner solverRunner;

    @Test
    public void testRun() throws IOException {
        // Load sample.json content
        Path path = Paths.get("src/test/resources/json/sample.json");
        String jsonInput = Files.readString(path);

        // Run the solver
        // This should not throw exception
        solverRunner.run(jsonInput);
    }
}
