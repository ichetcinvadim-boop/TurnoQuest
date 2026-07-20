package pro.turnoworld.quests;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

public final class GlobalState {
    private final Path file;
    public String weekKey = "";
    public long progress;
    public final Set<UUID> contributors = new HashSet<>();
    public final Set<UUID> claimed = new HashSet<>();

    public GlobalState(Path dataFolder) {
        this.file = dataFolder.resolve("global.properties");
        load();
    }

    public synchronized void normalize(String currentWeek) {
        if (currentWeek.equals(weekKey)) return;
        weekKey = currentWeek;
        progress = 0;
        contributors.clear();
        claimed.clear();
        save();
    }

    public synchronized void add(UUID uuid, long amount) {
        if (amount <= 0) return;
        progress = Math.max(0, progress + amount);
        contributors.add(uuid);
    }

    public synchronized boolean mayClaim(UUID uuid, long required) {
        return progress >= required && contributors.contains(uuid) && !claimed.contains(uuid);
    }

    public synchronized void claim(UUID uuid) { claimed.add(uuid); save(); }

    public synchronized void forceReset(String currentWeek) {
        weekKey = currentWeek;
        progress = 0;
        contributors.clear();
        claimed.clear();
        save();
    }

    public synchronized void save() {
        Properties p = new Properties();
        p.setProperty("weekKey", weekKey);
        p.setProperty("progress", String.valueOf(progress));
        p.setProperty("contributors", join(contributors));
        p.setProperty("claimed", join(claimed));
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) { p.store(out, "TurnoQuests global weekly objective"); }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ignored) { }
    }

    private void load() {
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        catch (IOException ignored) { return; }
        weekKey = p.getProperty("weekKey", "");
        try { progress = Long.parseLong(p.getProperty("progress", "0")); } catch (NumberFormatException ignored) { }
        parse(p.getProperty("contributors", ""), contributors);
        parse(p.getProperty("claimed", ""), claimed);
    }

    private static String join(Set<UUID> values) { return values.stream().sorted().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse(""); }
    private static void parse(String raw, Set<UUID> target) {
        if (raw.isBlank()) return;
        Arrays.stream(raw.split(",")).forEach(v -> { try { target.add(UUID.fromString(v)); } catch (IllegalArgumentException ignored) { } });
    }
}
