package me.foesio.foCombatTag.service;

import me.foesio.foCombatTag.FoCombatTag;
import me.foesio.foCombatTag.api.CombatTagCause;
import me.foesio.foCombatTag.api.CombatTagInfo;
import me.foesio.foCombatTag.api.CombatTagRemoveReason;
import me.foesio.foCombatTag.api.event.CombatTagApplyEvent;
import me.foesio.foCombatTag.api.event.CombatTagRemoveEvent;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class CombatTagService {
    private static final long BORDER_REFRESH_INTERVAL_MILLIS = 5_000L;
    private static final long ELYTRA_ENFORCE_INTERVAL_MILLIS = 5_000L;
    private static final long ACTIONBAR_PRECISE_SECONDS = 5L;
    private static final int SCHEDULED_WORK_CLEANUP_MIN_SIZE = 128;
    private static final int SCHEDULED_WORK_CLEANUP_MULTIPLIER = 8;

    private final FoCombatTag plugin;
    private final BorderService borderService;
    private final Map<UUID, TagEntry> tags = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastActionbarMessages = new ConcurrentHashMap<>();
    private final Queue<ScheduledWork> scheduledWork = new PriorityQueue<>(Comparator
            .comparingLong(ScheduledWork::dueAtMillis)
            .thenComparingLong(ScheduledWork::sequence));
    private final AtomicLong tagVersions = new AtomicLong();
    private long scheduleSequence;
    private long scheduledTaskDueAtMillis = Long.MAX_VALUE;
    private BukkitTask scheduledTask;
    private boolean processingScheduledWork;

    public CombatTagService(FoCombatTag plugin, BorderService borderService) {
        this.plugin = plugin;
        this.borderService = borderService;
    }

    public void start() {
        cancelScheduledTask();
        scheduledWork.clear();
        scheduleAllRuntimeWork(System.currentTimeMillis(), false);
    }

    public void reload() {
        clearAllRuntimeState();
        scheduleAllRuntimeWork(System.currentTimeMillis(), true);
        borderService.clearAll();
    }

    public void shutdown() {
        cancelScheduledTask();
        for (UUID playerId : new ArrayList<>(tags.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                untag(player, CombatTagRemoveReason.PLUGIN_DISABLE, false);
            }
        }
        tags.clear();
        clearAllRuntimeState();
        borderService.clearAll();
    }

    public int tagCombat(Player attacker, Player victim) {
        if (!isTaggable(attacker.getLocation()) || !isTaggable(victim.getLocation())) {
            return 0;
        }
        int count = 0;
        if (tag(attacker, victim.getUniqueId(), victim.getName(), CombatTagCause.COMBAT, plugin.settings().combatDurationSeconds())) {
            count++;
        }
        if (tag(victim, attacker.getUniqueId(), attacker.getName(), CombatTagCause.COMBAT, plugin.settings().combatDurationSeconds())) {
            count++;
        }
        return count;
    }

    public boolean tagManual(Player player) {
        return tag(player, null, "Admin", CombatTagCause.COMMAND, plugin.settings().combatDurationSeconds());
    }

    public boolean tagApi(Player player, String sourceName, int durationSeconds) {
        return tag(player, null, sourceName == null || sourceName.isBlank() ? "API" : sourceName, CombatTagCause.API, durationSeconds);
    }

    public boolean refreshFromPearl(Player player) {
        if (!plugin.settings().pearlExtendsCombat() || !isTagged(player)) {
            return false;
        }
        return tag(player, null, "Ender Pearl", CombatTagCause.PEARL, plugin.settings().combatDurationSeconds());
    }

    public int tagAll(Collection<? extends Player> players) {
        int count = 0;
        for (Player player : players) {
            if (tag(player, null, "Admin", CombatTagCause.COMMAND, plugin.settings().combatDurationSeconds())) {
                count++;
            }
        }
        return count;
    }

    public boolean untag(Player player, boolean notify) {
        return untag(player, CombatTagRemoveReason.COMMAND, notify);
    }

    public boolean untag(Player player, CombatTagRemoveReason reason, boolean notify) {
        UUID playerId = player.getUniqueId();
        TagEntry removed = tags.remove(playerId);
        clearRuntimeState(playerId);
        borderService.clear(player);
        if (removed != null) {
            Bukkit.getPluginManager().callEvent(new CombatTagRemoveEvent(player, reason));
        }
        if (removed != null && notify && player.isOnline()) {
            plugin.messages().send(player, "combat-untagged");
        }
        clearSchedulesIfIdle();
        return removed != null;
    }

    public int untagAll(boolean notify) {
        return untagAll(CombatTagRemoveReason.COMMAND, notify);
    }

    public int untagAll(CombatTagRemoveReason reason, boolean notify) {
        List<UUID> playerIds = new ArrayList<>(tags.keySet());
        int count = 0;
        for (UUID playerId : playerIds) {
            TagEntry removed = tags.remove(playerId);
            if (removed == null) {
                continue;
            }
            clearRuntimeState(playerId);
            count++;
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                borderService.clear(player);
                Bukkit.getPluginManager().callEvent(new CombatTagRemoveEvent(player, reason));
                if (notify) {
                    plugin.messages().send(player, "combat-untagged");
                }
            }
        }
        clearSchedulesIfIdle();
        return count;
    }

    public int untagOpponentsOf(Player killedPlayer) {
        int count = 0;
        UUID killedId = killedPlayer.getUniqueId();
        for (TagEntry entry : new ArrayList<>(tags.values())) {
            if (!killedId.equals(entry.opponentId())) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.playerId());
            if (player != null && untag(player, CombatTagRemoveReason.OPPONENT_DEATH, true)) {
                count++;
            }
        }
        return count;
    }

    public boolean isTagged(Player player) {
        return isTagged(player.getUniqueId());
    }

    public boolean isTagged(UUID playerId) {
        TagEntry entry = tags.get(playerId);
        if (entry == null) {
            return false;
        }
        if (entry.expiresAtMillis() <= System.currentTimeMillis()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                untag(player, CombatTagRemoveReason.EXPIRE, true);
            } else {
                tags.remove(playerId, entry);
                clearRuntimeState(playerId);
                clearSchedulesIfIdle();
            }
            return false;
        }
        return true;
    }

    public long remainingSeconds(Player player) {
        return remainingSeconds(player.getUniqueId());
    }

    public long remainingSeconds(UUID playerId) {
        TagEntry entry = tags.get(playerId);
        return entry == null ? 0L : remainingSeconds(entry);
    }

    public List<TagView> snapshot() {
        return tags.values().stream()
                .filter(entry -> entry.expiresAtMillis() > System.currentTimeMillis())
                .map(entry -> new TagView(entry.playerName(), entry.opponentName(), remainingSeconds(entry), entry.cause()))
                .sorted(Comparator.comparing(TagView::playerName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public TagView view(Player player) {
        TagEntry entry = tags.get(player.getUniqueId());
        if (entry == null || entry.expiresAtMillis() <= System.currentTimeMillis()) {
            return null;
        }
        return new TagView(entry.playerName(), entry.opponentName(), remainingSeconds(entry), entry.cause());
    }

    public List<CombatTagInfo> apiSnapshot() {
        return tags.values().stream()
                .filter(entry -> entry.expiresAtMillis() > System.currentTimeMillis())
                .map(entry -> new CombatTagInfo(entry.playerId(), entry.playerName(), entry.opponentName(), remainingSeconds(entry), entry.cause()))
                .sorted(Comparator.comparing(CombatTagInfo::playerName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private boolean tag(Player player, UUID opponentId, String opponentName, CombatTagCause cause, int durationSeconds) {
        if (player.isDead() || !player.isOnline() || player.getHealth() <= 0.0D) {
            return false;
        }
        if (!isTaggable(player.getLocation())) {
            return false;
        }
        long now = System.currentTimeMillis();
        TagEntry previous = tags.get(player.getUniqueId());
        boolean wasTagged = previous != null && previous.expiresAtMillis() > now;
        CombatTagApplyEvent event = new CombatTagApplyEvent(
                player,
                opponentName,
                cause,
                Math.max(1, durationSeconds),
                wasTagged
        );
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }

        long durationMillis = event.getDurationSeconds() * 1000L;
        UUID playerId = player.getUniqueId();
        TagEntry entry = new TagEntry(playerId, player.getName(), opponentId, opponentName, now + durationMillis, cause, tagVersions.incrementAndGet());
        tags.put(playerId, entry);
        lastActionbarMessages.remove(playerId);
        scheduleRuntimeWork(entry, now, false);
        if (!wasTagged) {
            plugin.messages().send(player, "combat-tagged", Map.of("{seconds}", String.valueOf(event.getDurationSeconds())));
        }
        plugin.elytraGuard().enforce(player);
        borderService.render(player);
        borderService.pushOutOfNoPvp(player);
        return true;
    }

    private void runScheduledWork() {
        scheduledTask = null;
        scheduledTaskDueAtMillis = Long.MAX_VALUE;
        processingScheduledWork = true;
        long now = System.currentTimeMillis();
        try {
            while (!scheduledWork.isEmpty()) {
                ScheduledWork work = scheduledWork.peek();
                if (work.dueAtMillis() > now) {
                    break;
                }
                scheduledWork.poll();
                processScheduledWork(work, now);
                now = System.currentTimeMillis();
            }
        } finally {
            processingScheduledWork = false;
            scheduleNextWork();
        }
    }

    private void processScheduledWork(ScheduledWork work, long now) {
        TagEntry entry = tags.get(work.playerId());
        if (entry == null || entry.version() != work.version()) {
            return;
        }
        if (entry.expiresAtMillis() <= now) {
            expire(entry, now);
            return;
        }
        switch (work.type()) {
            case EXPIRE -> scheduleWork(entry, WorkType.EXPIRE, entry.expiresAtMillis());
            case ACTIONBAR -> processActionbar(entry, now);
            case BORDER_REFRESH -> processBorderRefresh(entry, now);
            case ELYTRA_ENFORCE -> processElytraEnforce(entry, now);
        }
    }

    private void processActionbar(TagEntry entry, long now) {
        if (!plugin.settings().actionbarEnabled()) {
            lastActionbarMessages.remove(entry.playerId());
            return;
        }
        Player player = Bukkit.getPlayer(entry.playerId());
        if (player != null && player.isOnline()) {
            sendActionbarIfDue(player, entry, now);
            scheduleNextActionbar(entry, now, false);
        }
    }

    private void processBorderRefresh(TagEntry entry, long now) {
        Player player = Bukkit.getPlayer(entry.playerId());
        if (player != null && player.isOnline()) {
            borderService.pushOutOfNoPvp(player);
            borderService.render(player);
            scheduleWork(entry, WorkType.BORDER_REFRESH, now + BORDER_REFRESH_INTERVAL_MILLIS);
        }
    }

    private void processElytraEnforce(TagEntry entry, long now) {
        Player player = Bukkit.getPlayer(entry.playerId());
        if (player != null && player.isOnline()) {
            plugin.elytraGuard().enforce(player);
            scheduleWork(entry, WorkType.ELYTRA_ENFORCE, now + ELYTRA_ENFORCE_INTERVAL_MILLIS);
        }
    }

    private void expire(TagEntry entry, long now) {
        if (entry.expiresAtMillis() > now || !tags.remove(entry.playerId(), entry)) {
            return;
        }
        clearRuntimeState(entry.playerId());
        Player player = Bukkit.getPlayer(entry.playerId());
        if (player != null) {
            borderService.clear(player);
            Bukkit.getPluginManager().callEvent(new CombatTagRemoveEvent(player, CombatTagRemoveReason.EXPIRE));
            plugin.messages().send(player, "combat-untagged");
        }
        clearSchedulesIfIdle();
    }

    private void scheduleAllRuntimeWork(long now, boolean refreshNow) {
        for (TagEntry entry : new ArrayList<>(tags.values())) {
            if (entry.expiresAtMillis() <= now) {
                expire(entry, now);
                continue;
            }
            scheduleRuntimeWork(entry, now, refreshNow);
        }
    }

    private void scheduleRuntimeWork(TagEntry entry, long now, boolean refreshNow) {
        scheduleWork(entry, WorkType.EXPIRE, entry.expiresAtMillis());
        scheduleNextActionbar(entry, now, true);
        scheduleWork(entry, WorkType.BORDER_REFRESH, refreshNow ? now : now + BORDER_REFRESH_INTERVAL_MILLIS);
        scheduleWork(entry, WorkType.ELYTRA_ENFORCE, refreshNow ? now : now + ELYTRA_ENFORCE_INTERVAL_MILLIS);
    }

    private void scheduleNextActionbar(TagEntry entry, long now, boolean includeCurrentSecond) {
        if (!plugin.settings().actionbarEnabled()) {
            return;
        }
        long remainingSeconds = remainingSeconds(entry, now);
        if (remainingSeconds <= 0L) {
            return;
        }
        long targetSeconds = nextActionbarSeconds(remainingSeconds);
        long dueAtMillis = entry.expiresAtMillis() - (targetSeconds * 1000L);
        if (!includeCurrentSecond && dueAtMillis <= now) {
            remainingSeconds = targetSeconds - 1L;
            if (remainingSeconds <= 0L) {
                return;
            }
            targetSeconds = nextActionbarSeconds(remainingSeconds);
            dueAtMillis = entry.expiresAtMillis() - (targetSeconds * 1000L);
        }
        scheduleWork(entry, WorkType.ACTIONBAR, Math.max(now, dueAtMillis));
    }

    private void scheduleWork(TagEntry entry, WorkType type, long dueAtMillis) {
        scheduledWork.add(new ScheduledWork(entry.playerId(), entry.version(), type, dueAtMillis, ++scheduleSequence));
        cleanupScheduledWorkIfNeeded();
        if (!processingScheduledWork) {
            scheduleNextWork();
        }
    }

    private void cleanupScheduledWorkIfNeeded() {
        int maxExpectedWork = Math.max(SCHEDULED_WORK_CLEANUP_MIN_SIZE, Math.max(1, tags.size()) * SCHEDULED_WORK_CLEANUP_MULTIPLIER);
        if (scheduledWork.size() <= maxExpectedWork) {
            return;
        }
        scheduledWork.removeIf(work -> {
            TagEntry entry = tags.get(work.playerId());
            return entry == null || entry.version() != work.version();
        });
    }

    private void scheduleNextWork() {
        if (scheduledWork.isEmpty()) {
            cancelScheduledTask();
            return;
        }
        long nextDueAtMillis = scheduledWork.peek().dueAtMillis();
        if (scheduledTask != null && scheduledTaskDueAtMillis <= nextDueAtMillis) {
            return;
        }
        cancelScheduledTask();
        scheduledTaskDueAtMillis = nextDueAtMillis;
        scheduledTask = Bukkit.getScheduler().runTaskLater(plugin, this::runScheduledWork, delayTicksUntil(nextDueAtMillis));
    }

    private long delayTicksUntil(long dueAtMillis) {
        long delayMillis = dueAtMillis - System.currentTimeMillis();
        if (delayMillis <= 50L) {
            return 1L;
        }
        return Math.max(1L, (delayMillis + 49L) / 50L);
    }

    private void cancelScheduledTask() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
        scheduledTaskDueAtMillis = Long.MAX_VALUE;
    }

    private boolean isTaggable(Location location) {
        if (!plugin.settings().isWorldEnabled(location.getWorld())) {
            return false;
        }
        return !plugin.worldGuardHook().hasAnyRegion(location, plugin.settings().disabledWorldGuardRegions());
    }

    private void sendActionbarIfDue(Player player, TagEntry entry, long now) {
        long remainingSeconds = remainingSeconds(entry, now);
        if (!shouldSendActionbar(remainingSeconds)) {
            return;
        }
        String message = plugin.messages().render("actionbar-tagged", "{bad}Combat {muted}| {theme}{seconds}s", Map.of(
                "seconds", String.valueOf(remainingSeconds)
        ));
        UUID playerId = player.getUniqueId();
        if (message.equals(lastActionbarMessages.get(playerId))) {
            return;
        }
        lastActionbarMessages.put(playerId, message);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }

    private boolean shouldSendActionbar(long remainingSeconds) {
        return remainingSeconds <= ACTIONBAR_PRECISE_SECONDS || remainingSeconds % 2L == 0L;
    }

    private long nextActionbarSeconds(long remainingSeconds) {
        if (shouldSendActionbar(remainingSeconds)) {
            return remainingSeconds;
        }
        return Math.max(ACTIONBAR_PRECISE_SECONDS, remainingSeconds - 1L);
    }

    private void clearRuntimeState(UUID playerId) {
        lastActionbarMessages.remove(playerId);
    }

    private void clearSchedulesIfIdle() {
        if (tags.isEmpty()) {
            clearAllRuntimeState();
        }
    }

    private void clearAllRuntimeState() {
        lastActionbarMessages.clear();
        scheduledWork.clear();
        cancelScheduledTask();
    }

    private long remainingSeconds(TagEntry entry) {
        return remainingSeconds(entry, System.currentTimeMillis());
    }

    private long remainingSeconds(TagEntry entry, long now) {
        return Math.max(0L, (entry.expiresAtMillis() - now + 999L) / 1000L);
    }

    private enum WorkType {
        EXPIRE,
        ACTIONBAR,
        BORDER_REFRESH,
        ELYTRA_ENFORCE
    }

    private record ScheduledWork(UUID playerId, long version, WorkType type, long dueAtMillis, long sequence) {
    }

    private record TagEntry(UUID playerId, String playerName, UUID opponentId, String opponentName, long expiresAtMillis, CombatTagCause cause, long version) {
    }

    public record TagView(String playerName, String opponentName, long remainingSeconds, CombatTagCause cause) {
    }
}
