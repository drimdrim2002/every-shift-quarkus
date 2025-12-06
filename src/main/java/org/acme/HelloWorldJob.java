package org.acme;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.jboss.logging.Logger;

@QuarkusMain
public class HelloWorldJob implements QuarkusApplication {
    private static final Logger LOG = Logger.getLogger(HelloWorldJob.class);

    @Override
    public int run(String... args) throws Exception {
        LOG.info("Hello World from Cloud Run Job");
        return 0;
    }
}
