package cn.lunadeer.reColorfulMap.utils;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SendMessageAbstractTest {
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final SendMessageAbstract messages = new SendMessageAbstract(plugin);
    private final Player player = mock(Player.class);
    private final Component message = Component.text("Map delivery failed");

    @Test
    void playerMessagesUseTheNativeComponentAudience() {
        messages.sendMessage(player, message);

        verify(player).sendMessage(message);
        verifyNoInteractions(plugin);
    }

    @Test
    void commandSenderMessagesUseTheNativeComponentAudience() {
        CommandSender sender = mock(CommandSender.class);

        messages.sendMessage(sender, message);

        verify(sender).sendMessage(message);
        verifyNoInteractions(plugin);
    }

    @Test
    void broadcastReachesEachOnlinePlayerAndTheConsole() {
        Server server = mock(Server.class);
        Player second = mock(Player.class);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(plugin.getServer()).thenReturn(server);
        doReturn(List.of(player, second)).when(server).getOnlinePlayers();
        when(server.getConsoleSender()).thenReturn(console);

        messages.broadcast(message);

        verify(player).sendMessage(message);
        verify(second).sendMessage(message);
        verify(console).sendMessage(message);
    }

    @Test
    void actionBarUsesTheNativeComponentAudience() {
        messages.sendActionBar(player, message);

        verify(player).sendActionBar(message);
    }

    @Test
    void titlePreservesBothComponents() {
        Component subtitle = Component.text("Try an empty inventory slot");

        messages.sendTitle(player, message, subtitle);

        verify(player).showTitle(Title.title(message, subtitle));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void bossBarIsHiddenOnThePlayersOwnerSchedulerAfterSixtyTicks() {
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);

        messages.sendBossBar(player, message);

        ArgumentCaptor<BossBar> displayed = ArgumentCaptor.forClass(BossBar.class);
        verify(player).showBossBar(displayed.capture());
        BossBar bar = displayed.getValue();
        assertEquals(message, bar.name());
        assertEquals(1.0f, bar.progress());
        assertEquals(BossBar.Color.BLUE, bar.color());
        assertEquals(BossBar.Overlay.PROGRESS, bar.overlay());
        ArgumentCaptor<Consumer<ScheduledTask>> expiration = ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).runDelayed(eq(plugin), expiration.capture(), isNull(), eq(60L));
        verify(player, never()).hideBossBar(any());
        verifyNoInteractions(plugin);

        expiration.getValue().accept(mock(ScheduledTask.class));

        verify(player).hideBossBar(bar);
    }
}
