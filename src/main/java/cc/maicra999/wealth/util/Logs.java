package cc.maicra999.wealth.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Logs {

    private static final String LOGGER_NAME = "Wealth";

    // Private constructor to prevent instantiation
    private Logs() {}

    public static Logger logger() {
        return LoggerFactory.getLogger(LOGGER_NAME);
    }
}
