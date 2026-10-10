package cn.lunadeer.reColorfulMap.utils;

import java.util.Arrays;
import java.util.IllegalFormatException;
import java.util.logging.Logger;

/** Bad translation placeholders must never hide the error that a message is reporting. */
public final class SafeMessageFormat {
    private SafeMessageFormat() { }

    public static String format(Logger logger, String template, Object... arguments) {
        String pattern = String.valueOf(template);
        try {
            return String.format(pattern, arguments);
        } catch (IllegalFormatException failure) {
            // Do not log through Notification or XLogger: both call this formatter.
            if (logger != null) {
                try {
                    logger.warning("Invalid message format (" + failure.getClass().getSimpleName()
                            + "): " + pattern);
                } catch (RuntimeException loggingFailure) {
                    // A failing logging handler must not prevent the fallback reaching its recipient.
                }
            }
            return pattern + " " + Arrays.toString(arguments);
        }
    }
}
