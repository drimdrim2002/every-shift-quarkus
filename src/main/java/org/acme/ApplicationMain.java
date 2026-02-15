package org.acme;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.jboss.logging.Logger;

@QuarkusMain
public class ApplicationMain implements QuarkusApplication {
    private static final Logger LOG = Logger.getLogger(ApplicationMain.class);

    @Override
    public int run(String... args) {
        String appMode = System.getenv("APP_MODE");
        if (appMode != null && !appMode.isBlank() && !"API".equalsIgnoreCase(appMode)) {
            LOG.warnf(">>> APP_MODE=%s is ignored. This service supports API mode only.", appMode);
        }

        LOG.info(">>> [API] Server waiting...");
        Quarkus.waitForExit();
        return 0;
    }
}
