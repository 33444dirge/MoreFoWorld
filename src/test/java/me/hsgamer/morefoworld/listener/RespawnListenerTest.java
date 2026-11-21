package me.hsgamer.morefoworld.listener;

import io.github.projectunified.minelib.plugin.base.BasePlugin;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.earthme.luminol.api.entity.player.PostPlayerRespawnEvent;
import me.hsgamer.morefoworld.config.RespawnConfig;
import me.hsgamer.morefoworld.config.WorldSpawnConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RespawnListenerTest {
    private static Server server;
    private BasePlugin plugin;
    private Player player;
    private World world;
    private World destination;
    private RespawnConfig respawns;
    private EntityScheduler scheduler;
    private ScheduledTask task;
    private PlayerDeathEvent death;
    private RespawnListener listener;
    private Consumer<ScheduledTask> callback;
    private Runnable retired;

    @BeforeAll
    static void server() throws Exception {
        server = mock(Server.class);
        when(server.getLogger()).thenReturn(Logger.getLogger("respawn-test"));
        // Avoid Bukkit's startup banner, which requires a running server's build-info provider.
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, server);
    }

    @BeforeEach
    void setup() {
        reset(server);
        plugin = mock(BasePlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("respawn-test"));
        player = mock(Player.class);
        world = mock(World.class);
        destination = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(destination.getUID()).thenReturn(worldId);
        when(server.getWorld(worldId)).thenReturn(destination);
        when(destination.getSpawnLocation()).thenReturn(new Location(destination, 12, 80, 34, 90, 0));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
        respawns = mock(RespawnConfig.class);
        when(respawns.getRespawnWorld(world)).thenReturn(Optional.of(destination));
        WorldSpawnConfig spawns = mock(WorldSpawnConfig.class);
        when(spawns.getSpawn(any(World.class))).thenReturn(Optional.empty());
        when(plugin.get(RespawnConfig.class)).thenReturn(respawns);
        when(plugin.get(WorldSpawnConfig.class)).thenReturn(spawns);
        scheduler = mock(EntityScheduler.class);
        task = mock(ScheduledTask.class);
        when(player.getScheduler()).thenReturn(scheduler);
        when(scheduler.runDelayed(eq(plugin), any(), any(), eq(1L))).thenAnswer(invocation -> {
            callback = invocation.getArgument(1);
            retired = invocation.getArgument(2);
            return task;
        });
        death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(player);
        listener = new RespawnListener(plugin);
    }

    private void respawn() {
        listener.onPostRespawn(new PostPlayerRespawnEvent(player));
    }

    private Map<?, ?> pending() throws Exception {
        Field field = RespawnListener.class.getDeclaredField("pending");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(listener);
    }

    @Test
    void waitsForPostRespawnAndTeleportsOnlyOnce() throws Exception {
        listener.onDeath(death);
        verifyNoInteractions(scheduler);
        respawn();
        respawn();
        verify(scheduler, times(1)).runDelayed(eq(plugin), any(), any(), eq(1L));
        callback.accept(task);
        callback.accept(task);
        verify(player).teleportAsync(new Location(destination, 12, 80, 34, 90, 0));
        assertTrue(pending().isEmpty());
    }

    @Test
    void bedBecomesValidDuringRespawn() throws Exception {
        listener.onDeath(death);
        when(player.getRespawnLocation(false)).thenReturn(new Location(world, 1, 2, 3));
        respawn();
        verifyNoInteractions(scheduler);
        assertTrue(pending().isEmpty());
    }

    @Test
    void invalidBedAtDeathCanFallBackToLinkedWorld() {
        when(player.getRespawnLocation(false)).thenReturn(new Location(world, 1, 2, 3));
        listener.onDeath(death);
        when(player.getRespawnLocation(false)).thenReturn(null);
        respawn();
        callback.accept(task);
        verify(player).teleportAsync(any(Location.class));
    }

    @Test
    void bedEstablishedBeforeDelayedTaskHasPriority() throws Exception {
        listener.onDeath(death);
        respawn();
        when(player.getRespawnLocation(false)).thenReturn(new Location(world, 1, 2, 3));
        callback.accept(task);
        verify(player, never()).teleportAsync(any(Location.class));
        assertTrue(pending().isEmpty());
    }

    @Test
    void cancelledDeathDoesNothing() throws Exception {
        when(death.isCancelled()).thenReturn(true);
        listener.onDeath(death);
        assertTrue(pending().isEmpty());
        verifyNoInteractions(scheduler);
    }

    @Test
    void noConfiguredSpawnDoesNothing() throws Exception {
        when(respawns.getRespawnWorld(world)).thenReturn(Optional.empty());
        listener.onDeath(death);
        assertTrue(pending().isEmpty());
    }

    @Test
    void rejectsNonFiniteCoordinates() throws Exception {
        when(destination.getSpawnLocation()).thenReturn(new Location(destination, Double.NaN, 80, 0));
        listener.onDeath(death);
        assertTrue(pending().isEmpty());
    }

    @Test
    void newDeathInvalidatesOldCallback() throws Exception {
        listener.onDeath(death);
        respawn();
        listener.onDeath(death);
        verify(task).cancel();
        callback.accept(task);
        verify(player, never()).teleportAsync(any(Location.class));
        assertEquals(1, pending().size());
    }

    @Test
    void quitCancelsAndClearsRequest() throws Exception {
        listener.onDeath(death);
        respawn();
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        listener.onQuit(quit);
        callback.accept(task);
        verify(task).cancel();
        verify(player, never()).teleportAsync(any(Location.class));
        assertTrue(pending().isEmpty());
    }

    @Test
    void retiredEntityClearsRequest() throws Exception {
        listener.onDeath(death);
        respawn();
        retired.run();
        assertTrue(pending().isEmpty());
        verify(task).cancel();
    }

    @Test
    void retirementBeforeTaskAttachmentStillCancelsTask() throws Exception {
        when(scheduler.runDelayed(eq(plugin), any(), any(), eq(1L))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return task;
        });
        listener.onDeath(death);
        respawn();
        verify(task).cancel();
        assertTrue(pending().isEmpty());
    }

    @Test
    void rejectedAndThrowingSchedulerClearRequests() throws Exception {
        when(scheduler.runDelayed(eq(plugin), any(), any(), eq(1L))).thenReturn(null);
        listener.onDeath(death);
        respawn();
        assertTrue(pending().isEmpty());
        when(scheduler.runDelayed(eq(plugin), any(), any(), eq(1L))).thenThrow(new IllegalStateException("disabled"));
        listener.onDeath(death);
        respawn();
        assertTrue(pending().isEmpty());
    }

    @Test
    void unloadedWorldSkipsTeleportAndClearsRequest() throws Exception {
        listener.onDeath(death);
        respawn();
        when(server.getWorld(destination.getUID())).thenReturn(null);
        callback.accept(task);
        verify(player, never()).teleportAsync(any(Location.class));
        assertTrue(pending().isEmpty());
    }

    @Test
    void disableCancelsOutstandingTasksAndIgnoresNewEvents() throws Exception {
        listener.onDeath(death);
        respawn();
        listener.disable();
        callback.accept(task);
        listener.onDeath(death);
        respawn();
        verify(task).cancel();
        verify(player, never()).teleportAsync(any(Location.class));
        assertTrue(pending().isEmpty());
    }
}
