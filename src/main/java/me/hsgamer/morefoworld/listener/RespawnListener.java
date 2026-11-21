package me.hsgamer.morefoworld.listener;

import io.github.projectunified.minelib.plugin.base.BasePlugin;
import io.github.projectunified.minelib.plugin.listener.ListenerComponent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.earthme.luminol.api.entity.player.PostPlayerRespawnEvent;
import me.hsgamer.morefoworld.config.RespawnConfig;
import me.hsgamer.morefoworld.config.WorldSpawnConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public class RespawnListener implements ListenerComponent {
    private final BasePlugin plugin;
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private volatile boolean active = true;

    public RespawnListener(BasePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public BasePlugin getPlugin() {
        return plugin;
    }

    @Override
    public void enable() {
        active = true;
        ListenerComponent.super.enable();
    }

    @Override
    public void disable() {
        active = false;
        ListenerComponent.super.disable();
        pending.values().forEach(Pending::close);
        pending.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!active || event.isCancelled()) return;
        Player player = event.getEntity();
        UUID id = player.getUniqueId();
        Pending old = pending.remove(id);
        if (old != null) old.close();

        World world = player.getWorld();
        World respawnWorld = plugin.get(RespawnConfig.class).getRespawnWorld(world).orElse(null);
        Location location;
        if (respawnWorld != null) {
            location = plugin.get(WorldSpawnConfig.class).getSpawn(respawnWorld)
                    .map(position -> position.toLocation(respawnWorld)).orElseGet(respawnWorld::getSpawnLocation);
        } else {
            location = plugin.get(WorldSpawnConfig.class).getSpawn(world)
                    .map(position -> position.toLocation(world)).orElse(null);
        }
        if (location == null || location.getWorld() == null) return;
        Target target = Target.of(location);
        if (!target.isFinite()) {
            plugin.getLogger().warning("忽略无效重生坐标，玩家 UUID: " + id);
            return;
        }
        Pending request = new Pending(target);
        pending.put(id, request);
        if (!active) remove(id, request);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPostRespawn(PostPlayerRespawnEvent event) {
        if (!active) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Pending request = pending.get(id);
        if (request == null || !request.scheduled.compareAndSet(false, true)) return;
        // Check beds/anchors after vanilla has resolved the player's respawn.
        if (player.getRespawnLocation(false) != null) {
            remove(id, request);
            return;
        }
        try {
            ScheduledTask task = player.getScheduler().runDelayed(plugin, scheduledTask -> {
                if (!active || !pending.remove(id, request)) return;
                request.close();
                if (!player.isOnline() || player.isDead() || player.getRespawnLocation(false) != null) return;
                World destination = Bukkit.getWorld(request.target.world());
                if (destination == null) {
                    plugin.getLogger().warning("重生目标世界已卸载，取消传送，玩家 UUID: " + id);
                    return;
                }
                try {
                    if (!active) return;
                    player.teleportAsync(request.target.toLocation(destination)).whenComplete((success, error) -> {
                        if (!active) return;
                        if (error != null) {
                            plugin.getLogger().log(Level.WARNING, "重生传送失败，玩家 UUID: " + id, error);
                        } else if (!Boolean.TRUE.equals(success)) {
                            plugin.getLogger().warning("重生传送未成功，玩家 UUID: " + id);
                        }
                    });
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "无法启动重生传送，玩家 UUID: " + id, exception);
                }
            }, () -> remove(id, request), 1L);
            if (task == null) remove(id, request);
            else request.attach(task);
        } catch (RuntimeException exception) {
            remove(id, request);
            if (active) plugin.getLogger().log(Level.WARNING, "无法安排重生传送，玩家 UUID: " + id, exception);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Pending request = pending.remove(event.getPlayer().getUniqueId());
        if (request != null) request.close();
    }

    private void remove(UUID id, Pending request) {
        pending.remove(id, request);
        request.close();
    }

    private static final class Pending {
        final Target target;
        final AtomicBoolean scheduled = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicReference<ScheduledTask> task = new AtomicReference<>();

        Pending(Target target) {
            this.target = target;
        }

        void attach(ScheduledTask task) {
            this.task.set(task);
            if (closed.get()) cancel();
        }

        void close() {
            closed.set(true);
            cancel();
        }

        private void cancel() {
            ScheduledTask scheduledTask = task.getAndSet(null);
            if (scheduledTask != null) scheduledTask.cancel();
        }
    }

    // Waiting requests retain coordinates, not worlds or players.
    private record Target(UUID world, double x, double y, double z, float yaw, float pitch) {
        static Target of(Location location) {
            return new Target(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch());
        }

        boolean isFinite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Float.isFinite(yaw) && Float.isFinite(pitch);
        }

        Location toLocation(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }
    }
}
