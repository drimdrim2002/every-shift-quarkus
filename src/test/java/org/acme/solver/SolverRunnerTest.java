package org.acme.solver;

import java.io.IOException;

import org.acme.test.JsonLoader;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
public class SolverRunnerTest {

    @Inject
    SolverRunner solverRunner;

    @Test
    public void testRun() throws IOException {
        // Load sample.json content
        String jsonInput = JsonLoader.loadAsString("/json/sample.json");

        // Run the solver
        // This should not throw exception
        solverRunner.run(jsonInput);
    }
}
