package cn.lunadeer.reColorfulMap.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class NotificationFormatTest {
    private final Notification previousNotification = Notification.instance;
    private final XLogger previousLogger = XLogger.instance;
    private final Logger logger = mock(Logger.class);
    private final JavaPlugin plugin = mock(JavaPlugin.class);

    @BeforeEach
    void setUp() {
        when(plugin.getName()).thenReturn("ReColorfulMap");
        when(plugin.getLogger()).thenReturn(logger);
        new Notification(plugin);
        new XLogger(plugin);
    }

    @AfterEach
    void restoreInstances() {
        Notification.instance = previousNotification;
        XLogger.instance = previousLogger;
    }

    @Test
    void badConfigErrorTranslationStillReachesTheCommandSender() {
        CommandSender sender = mock(CommandSender.class);

        assertDoesNotThrow(() -> Notification.error(sender, "无法加载配置 %d", "malformed YAML"));

        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(sender).sendMessage(sent.capture());
        assertEquals("[ReColorfulMap] 无法加载配置 %d [malformed YAML]", ((TextComponent) sent.getValue()).content());
        verify(logger).warning(contains("Invalid message format"));
    }

    @Test
    void playerActionBarsUseTheSameSafeFormatter() {
        Player player = mock(Player.class);

        assertDoesNotThrow(() -> Notification.actionBar(player, "错误 %d", "download failed"));

        verify(player).sendActionBar(Component.text("错误 %d [download failed]"));
    }

    @Test
    void messagesWithoutArgumentsKeepLiteralPercentSigns() {
        Player player = mock(Player.class);

        Notification.info(player, "100% ready");

        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(sent.capture());
        assertEquals("[ReColorfulMap] 100% ready", ((TextComponent) sent.getValue()).content());
        verifyNoInteractions(logger);
    }

    @Test
    void malformedLogMessagesUseTheSameNonRecursiveFallback() {
        assertDoesNotThrow(() -> XLogger.err("Error %d", "storage unavailable"));

        verify(logger).warning(contains("Invalid message format"));
        verify(logger).severe(" E | Error %d [storage unavailable]");
    }

    @Test
    void disabledDebugDoesNotFormatOrLogAnInvalidTemplate() {
        XLogger.setDebug(false);
        XLogger.debug("Error %d", "storage unavailable");
        verifyNoInteractions(logger);
    }
}
