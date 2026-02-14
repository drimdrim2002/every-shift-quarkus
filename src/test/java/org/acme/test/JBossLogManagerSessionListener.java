package org.acme.test;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Ensure JUL LogManager system property is set as early as possible
 * for IDE and Maven JUnit launches.
 */
public class JBossLogManagerSessionListener implements LauncherSessionListener {

    private static final String KEY = "java.util.logging.manager";
    private static final String VALUE = "org.jboss.logmanager.LogManager";

    static {
        if (System.getProperty(KEY) == null) {
            System.setProperty(KEY, VALUE);
        }
    }

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        // no-op
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        // no-op
    }
}
