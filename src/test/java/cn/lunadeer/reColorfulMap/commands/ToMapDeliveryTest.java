package cn.lunadeer.reColorfulMap.commands;

import cn.lunadeer.reColorfulMap.ReColorfulMap;
import cn.lunadeer.reColorfulMap.services.GenerationService;
import cn.lunadeer.reColorfulMap.utils.Notification;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.*;

/** The command validates user input; the service owns scheduling, charging and delivery. */
class ToMapDeliveryTest {
    @Test void validCommandDelegatesExactlyOnceToTheGenerationService() {
        Player player = mock(Player.class);
        GenerationService service = mock(GenerationService.class);
        ReColorfulMap plugin = mock(ReColorfulMap.class);
        when(plugin.getGenerationService()).thenReturn(service);
        try (MockedStatic<ReColorfulMap> plugins = mockStatic(ReColorfulMap.class)) {
            plugins.when(ReColorfulMap::getInstance).thenReturn(plugin);
            new ToMap().onCommand(player, mock(Command.class), "tomap", new String[]{"https://example.test/a.png", "2"});
            verify(service).submit(player, "https://example.test/a.png", 2f);
        }
    }
    @Test void invalidScaleNeverReachesTheService() {
        try (MockedStatic<ReColorfulMap> plugins = mockStatic(ReColorfulMap.class);
             MockedStatic<Notification> notifications = mockStatic(Notification.class)) {
            for (String scale : new String[]{"NaN", "Infinity", "-1", "0", "oops"}) {
                new ToMap().onCommand(mock(Player.class), mock(Command.class), "tomap", new String[]{"https://example.test/a.png", scale});
            }
            plugins.verifyNoInteractions();
        }
    }
    @Test void consoleAndMissingUrlDoNotStartWork() {
        try (MockedStatic<ReColorfulMap> plugins = mockStatic(ReColorfulMap.class);
             MockedStatic<Notification> notifications = mockStatic(Notification.class)) {
            new ToMap().onCommand(mock(CommandSender.class), mock(Command.class), "tomap", new String[]{"url"});
            new ToMap().onCommand(mock(Player.class), mock(Command.class), "tomap", new String[]{});
            plugins.verifyNoInteractions();
        }
    }
}
