package pro.turnoworld.quests;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class FileStore {
    private final Path root;
    private final Path players;
    private final Path backups;
    private final Path audit;
    private final ConcurrentMap<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    public FileStore(Path root) throws IOException {
        this.root = root;
        this.players = root.resolve("players");
        this.backups = root.resolve("backups");
        this.audit = root.resolve("audit.log");
        Files.createDirectories(players);
        Files.createDirectories(backups);
    }

    public PlayerData getOrCreate(UUID uuid, String name) {
        PlayerData data = cache.computeIfAbsent(uuid, id -> load(id).orElseGet(() -> new PlayerData(id, name)));
        if (name != null && !name.isBlank() && !name.equalsIgnoreCase(data.lastName)) data.lastName = name;
        return data;
    }

    public Optional<PlayerData> find(String uuidOrName) {
        try {
            UUID id = UUID.fromString(uuidOrName);
            return Optional.of(getOrCreate(id, "unknown"));
        } catch (IllegalArgumentException ignored) { }
        for (PlayerData data : cache.values()) if (data.lastName.equalsIgnoreCase(uuidOrName)) return Optional.of(data);
        if (!Files.isDirectory(players)) return Optional.empty();
        try (var stream = Files.list(players)) {
            Optional<Path> found = stream.filter(p -> p.getFileName().toString().endsWith(".properties")).filter(p -> {
                Properties props = read(p);
                return uuidOrName.equalsIgnoreCase(props.getProperty("lastName", ""));
            }).findFirst();
            if (found.isEmpty()) return Optional.empty();
            UUID id = UUID.fromString(found.get().getFileName().toString().replace(".properties", ""));
            return Optional.of(getOrCreate(id, uuidOrName));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Optional<PlayerData> load(UUID uuid) {
        Path path = path(uuid);
        if (!Files.exists(path)) return Optional.empty();
        Properties props = read(path);
        try { return Optional.of(PlayerData.from(props)); } catch (RuntimeException e) { return Optional.empty(); }
    }

    public synchronized void save(PlayerData data) throws IOException {
        atomicProperties(path(data.uuid), data.toProperties(), "TurnoQuests player data");
    }

    public synchronized void saveAll() {
        for (PlayerData data : cache.values()) {
            try { save(data); } catch (IOException ignored) { }
        }
    }

    public List<PlayerData> allKnown() {
        List<PlayerData> list = new ArrayList<>(cache.values());
        try (var stream = Files.list(players)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".properties")).forEach(p -> {
                try {
                    UUID id = UUID.fromString(p.getFileName().toString().replace(".properties", ""));
                    if (cache.containsKey(id)) return;
                    load(id).ifPresent(list::add);
                } catch (RuntimeException ignored) { }
            });
        } catch (IOException ignored) { }
        return list;
    }

    public synchronized void audit(String actor, String action, PlayerData target, String details) {
        String safe = details == null ? "" : details.replace('\n', ' ').replace('\r', ' ');
        String line = LocalDateTime.now() + "\t" + actor + "\t" + action + "\t" + target.uuid + "\t" + target.lastName + "\t" + safe + System.lineSeparator();
        try { Files.writeString(audit, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
        catch (IOException ignored) { }
    }

    public List<String> history(PlayerData data, int limit) {
        if (!Files.exists(audit)) return List.of();
        try {
            String needle = "\t" + data.uuid + "\t";
            List<String> matches = Files.readAllLines(audit, StandardCharsets.UTF_8).stream().filter(s -> s.contains(needle)).toList();
            int from = Math.max(0, matches.size() - Math.max(1, limit));
            return matches.subList(from, matches.size());
        } catch (IOException e) { return List.of(); }
    }

    public synchronized Path backup() throws IOException {
        saveAll();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path zip = backups.resolve("turnoquests-" + stamp + ".zip");
        try (OutputStream raw = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(raw))) {
            if (Files.exists(audit)) addZip(out, audit, "audit.log");
            try (var stream = Files.walk(players)) {
                for (Path path : stream.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList()) {
                    addZip(out, path, "players/" + players.relativize(path).toString().replace('\\', '/'));
                }
            }
        }
        return zip;
    }

    private void addZip(ZipOutputStream out, Path path, String name) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        Files.copy(path, out);
        out.closeEntry();
    }

    private Path path(UUID uuid) { return players.resolve(uuid.toString().toLowerCase(Locale.ROOT) + ".properties"); }

    private static Properties read(Path path) {
        Properties p = new Properties();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) { p.load(in); }
        catch (IOException ignored) { }
        return p;
    }

    private static void atomicProperties(Path target, Properties properties, String comment) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) { properties.store(out, comment); }
        try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
