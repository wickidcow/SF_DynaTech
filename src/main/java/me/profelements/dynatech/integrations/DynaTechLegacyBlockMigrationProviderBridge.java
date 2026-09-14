package me.profelements.dynatech.integrations;

import me.profelements.dynatech.DynaTech;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/** Optional reflective bridge into Slimefun Legacy's exact placed-machine migration API. */
public final class DynaTechLegacyBlockMigrationProviderBridge {
    private static final String NAME = "DynaTech Legacy Machine Migration";
    private static final String PROVIDER = "io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyBlockMigrationProvider";
    private static final String CANDIDATE = "io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyBlockMigrationCandidate";
    private static final String RESULT = "io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyBlockMigrationResult";
    private static final Set<String> DEFERRED = Set.of("AUTO_KITCHEN");
    private static volatile boolean registered;

    private DynaTechLegacyBlockMigrationProviderBridge() {}

    public static void register(DynaTech plugin) {
        if (registered) return;
        var slimefun = plugin.getServer().getPluginManager().getPlugin("Slimefun");
        if (slimefun == null) return;
        try {
            ClassLoader loader = slimefun.getClass().getClassLoader();
            Class<?> providerClass = Class.forName(PROVIDER, false, loader);
            Class<?> candidateClass = Class.forName(CANDIDATE, false, loader);
            Class<?> resultClass = Class.forName(RESULT, false, loader);
            Runtime runtime = new Runtime(plugin, candidateClass, resultClass);
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(providerClass.getClassLoader(), new Class<?>[]{providerClass},
                    (self, method, args) -> switch (method.getName()) {
                        case "getMigrationName" -> NAME;
                        case "getLegacyBlockMappings" -> executableMappings();
                        case "scanLoadedCandidates" -> runtime.scan();
                        case "isCandidateStillValid" -> runtime.valid(args == null ? null : args[0]);
                        case "migrate" -> runtime.migrate(args == null ? null : args[0]);
                        case "toString" -> NAME + " provider";
                        case "hashCode" -> System.identityHashCode(self);
                        case "equals" -> self == (args == null || args.length == 0 ? null : args[0]);
                        default -> null;
                    });
            @SuppressWarnings({"rawtypes", "unchecked"}) Class raw = providerClass;
            plugin.getServer().getServicesManager().register(raw, proxy, plugin, ServicePriority.Normal);
            registered = true;
            plugin.getLogger().info("Registered exact DynaTech machine migration with Slimefun Doctor.");
        } catch (ClassNotFoundException ignored) {
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not register DynaTech exact machine migration", ex);
        }
    }

    private static Map<String, String> executableMappings() {
        Map<String, String> map = new LinkedHashMap<>(SlimefunLegacyIdMappings.mappings());
        DEFERRED.forEach(map::remove);
        return Map.copyOf(map);
    }

    private static final class Runtime {
        private final DynaTech plugin;
        private final Class<?> candidateClass;
        private final Constructor<?> candidateCtor;
        private final Method worldId, x, y, z, sourceId, targetId, stateClaim;
        private final Method migrated, skipped, blocked, failed;
        private final Method controller, loadedData, snapshotData, snapshotMenu, migrateBlock;

        Runtime(DynaTech plugin, Class<?> candidateClass, Class<?> resultClass) throws ReflectiveOperationException {
            this.plugin = plugin;
            this.candidateClass = candidateClass;
            candidateCtor = candidateClass.getConstructor(UUID.class, int.class, int.class, int.class,
                    String.class, String.class, String.class);
            worldId = candidateClass.getMethod("worldId"); x = candidateClass.getMethod("x");
            y = candidateClass.getMethod("y"); z = candidateClass.getMethod("z");
            sourceId = candidateClass.getMethod("sourceId"); targetId = candidateClass.getMethod("targetId");
            stateClaim = candidateClass.getMethod("stateClaim");
            migrated = resultClass.getMethod("migrated", String.class); skipped = resultClass.getMethod("skipped", String.class);
            blocked = resultClass.getMethod("blocked", String.class); failed = resultClass.getMethod("failed", String.class);
            controller = privateMethod("blockDataController"); loadedData = privateMethod("loadedBlockData", Object.class);
            snapshotData = privateMethod("snapshotData", Object.class); snapshotMenu = privateMethod("snapshotMenu", Object.class);
            migrateBlock = privateMethod("migrateBlock", Object.class, Object.class, String.class, String.class, Location.class);
        }

        private Method privateMethod(String name, Class<?>... types) throws NoSuchMethodException {
            Method method = DynaTechLegacyMigrationService.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method;
        }

        Collection<Object> scan() throws ReflectiveOperationException {
            DynaTechLegacyMigrationService service = new DynaTechLegacyMigrationService(plugin);
            Object c = controller.invoke(service);
            if (c == null) throw new IllegalStateException("Slimefun block-data controller unavailable");
            List<Object> result = new ArrayList<>();
            for (Object data : blocks(service, c)) {
                String from = stringCall(data, "getSfId");
                String to = from == null ? null : executableMappings().get(from);
                Location loc = locationCall(data);
                if (to == null || loc == null || loc.getWorld() == null || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
                result.add(candidateCtor.newInstance(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), from, to,
                        claim(service, data, from, to)));
            }
            return List.copyOf(result);
        }

        boolean valid(Object candidate) {
            try {
                View view = view(candidate);
                Live live = live(view);
                return live != null && view.claim.equals(claim(live.service, live.data, view.from, view.to));
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return false;
            }
        }

        Object migrate(Object candidate) throws ReflectiveOperationException {
            if (!candidateClass.isInstance(candidate)) return blocked.invoke(null, "Unrecognized candidate type.");
            View view = view(candidate);
            if (!view.to.equals(executableMappings().get(view.from))) return blocked.invoke(null, "Legacy mapping changed.");
            Live live = live(view);
            if (live == null) return skipped.invoke(null, "Machine is no longer loaded with the approved legacy ID.");
            if (!view.claim.equals(claim(live.service, live.data, view.from, view.to)))
                return skipped.invoke(null, "Machine state changed after authorization; nothing was modified.");
            try {
                migrateBlock.invoke(live.service, live.controller, live.data, view.from, view.to, live.location);
                return migrated.invoke(null, view.from + " -> " + view.to);
            } catch (InvocationTargetException ex) {
                plugin.getLogger().log(Level.WARNING, "Exact DynaTech machine migration failed at " + live.location,
                        ex.getCause() == null ? ex : ex.getCause());
                return failed.invoke(null, "Migration failed; DynaTech attempted rollback to the original machine.");
            }
        }

        private Live live(View view) throws ReflectiveOperationException {
            World world = plugin.getServer().getWorld(view.world);
            if (world == null || !world.isChunkLoaded(view.x >> 4, view.z >> 4)) return null;
            Location loc = new Location(world, view.x, view.y, view.z);
            DynaTechLegacyMigrationService service = new DynaTechLegacyMigrationService(plugin);
            Object c = controller.invoke(service);
            if (c == null) return null;
            for (Object data : blocks(service, c)) {
                Location found = locationCall(data);
                if (sameBlock(loc, found) && view.from.equals(stringCall(data, "getSfId"))) return new Live(service, c, data, loc);
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        private List<Object> blocks(DynaTechLegacyMigrationService service, Object c) throws ReflectiveOperationException {
            return (List<Object>) loadedData.invoke(service, c);
        }

        private String claim(DynaTechLegacyMigrationService service, Object data, String from, String to) throws ReflectiveOperationException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                update(digest, "dynatech-block-v2\n" + from + "\n" + to + "\n");
                @SuppressWarnings("unchecked") Map<String, String> values = (Map<String, String>) snapshotData.invoke(service, data);
                for (var entry : new TreeMap<>(values).entrySet()) update(digest, "kv:" + entry.getKey() + "=" + entry.getValue() + "\n");
                ItemStack[] menu = (ItemStack[]) snapshotMenu.invoke(service, data);
                if (menu == null) update(digest, "menu:none\n");
                else for (int i = 0; i < menu.length; i++) {
                    ItemStack stack = menu[i];
                    if (stack == null || stack.getType().isAir()) continue;
                    update(digest, "slot:" + i + ":" + stack.getType() + ":" + stack.getAmount() + ":" + stack.serialize() + "\n");
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
        }

        private View view(Object candidate) throws ReflectiveOperationException {
            return new View((UUID) worldId.invoke(candidate), (int) x.invoke(candidate), (int) y.invoke(candidate), (int) z.invoke(candidate),
                    (String) sourceId.invoke(candidate), (String) targetId.invoke(candidate), (String) stateClaim.invoke(candidate));
        }

        private static boolean sameBlock(Location a, Location b) {
            return b != null && a.getWorld() != null && b.getWorld() != null && a.getWorld().getUID().equals(b.getWorld().getUID())
                    && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
        }
        private static Location locationCall(Object target) { Object value = call(target, "getLocation"); return value instanceof Location l ? l : null; }
        private static String stringCall(Object target, String name) { Object value = call(target, name); return value instanceof String s ? s : null; }
        private static Object call(Object target, String name) {
            try { return target == null ? null : target.getClass().getMethod(name).invoke(target); }
            catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
        }
        private static void update(MessageDigest digest, String text) { digest.update(text.getBytes(StandardCharsets.UTF_8)); }
        private record View(UUID world, int x, int y, int z, String from, String to, String claim) {}
        private record Live(DynaTechLegacyMigrationService service, Object controller, Object data, Location location) {}
    }
}
