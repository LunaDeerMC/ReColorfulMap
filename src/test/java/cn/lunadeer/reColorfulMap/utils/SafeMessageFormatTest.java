package cn.lunadeer.reColorfulMap.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SafeMessageFormatTest {
    private final Logger logger = mock(Logger.class);

    @ParameterizedTest
    @ValueSource(strings = {"错误 %d", "错误 %s / %s", "错误 %q", "错误 %"})
    void malformedTranslationsKeepTheOriginalErrorVisible(String template) {
        assertEquals(template + " [broken YAML]", SafeMessageFormat.format(logger, template, "broken YAML"));
        verify(logger).warning(contains("Invalid message format"));
    }

    @Test
    void validArgumentsAndEscapedPercentKeepTheirNormalMeaning() {
        assertEquals("Saved 4 maps: 100%", SafeMessageFormat.format(logger, "Saved %d maps: %s%%", 4, "100"));
        verifyNoInteractions(logger);
    }

    @Test
    void evenAFailingLogHandlerCannotBlockTheFallback() {
        doThrow(new IllegalStateException("logging unavailable")).when(logger).warning(contains("Invalid message format"));
        assertEquals("错误 %d [broken YAML]", SafeMessageFormat.format(logger, "错误 %d", "broken YAML"));
    }
}
