package me.profelements.dynatech.integrations;

import me.profelements.dynatech.DynaTech;
import org.bukkit.plugin.ServicePriority;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Optional reflective bridge into Slimefun Legacy's migration-provider API. */
public final class DynaTechLegacyMigrationProviderBridge {

    private static final String MIGRATION_NAME = "DynaTech Legacy ID Migration";
    private static final String PROVIDER_CLASS =
            "io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyItemMigrationProvider";
    private static final String REPORT_CLASS =
            "io.github.thebusybiscuit.slimefun4.api.diagnostics.AddonDoctorReport";

    private static volatile boolean registered;

    private DynaTechLegacyMigrationProviderBridge() {
    }

    public static void register(DynaTech plugin) {
        if (registered) {
            return;
        }

        var slimefun = plugin.getServer().getPluginManager().getPlugin("Slimefun");
        if (slimefun == null) {
            return;
        }

        ClassLoader loader = slimefun.getClass().getClassLoader();
        try {
            Class<?> providerClass = Class.forName(PROVIDER_CLASS, false, loader);
            Class<?> reportClass = Class.forName(REPORT_CLASS, false, loader);
            Constructor<?> reportConstructor = reportClass.getConstructor(
                    String.class,
                    boolean.class,
                    long.class,
                    long.class,
                    long.class,
                    long.class,
                    List.class);

            Object provider = Proxy.newProxyInstance(providerClass.getClassLoader(), new Class<?>[] { providerClass },
                    (proxy, method, args) -> invokeProvider(plugin, reportConstructor, proxy, method, args));

            @SuppressWarnings({ "unchecked", "rawtypes" })
            Class rawProviderClass = providerClass;
            plugin.getServer().getServicesManager().register(rawProviderClass, provider, plugin, ServicePriority.Normal);
            registered = true;
            plugin.getLogger().info("Registered DynaTech legacy migration with Slimefun Doctor.");
        } catch (ClassNotFoundException ignored) {
            // Expected on Slimefun implementations that do not expose the Legacy provider API.
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not register DynaTech's Slimefun Doctor migration provider", ex);
        }
    }

    private static Object invokeProvider(
            DynaTech plugin,
            Constructor<?> reportConstructor,
            Object proxy,
            Method method,
            Object[] args) throws ReflectiveOperationException {
        return switch (method.getName()) {
            case "getMigrationName" -> MIGRATION_NAME;
            case "getLegacyItemMappings" -> SlimefunLegacyIdMappings.mappings();
            case "runMigration" -> runMigration(plugin, reportConstructor,
                    args != null && args.length > 0 && Boolean.TRUE.equals(args[0]));
            case "toString" -> MIGRATION_NAME + " provider";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            default -> null;
        };
    }

    private static Object runMigration(DynaTech plugin, Constructor<?> reportConstructor, boolean repair)
            throws ReflectiveOperationException {
        DynaTechLegacyMigrationService.MigrationStats stats =
                new DynaTechLegacyMigrationService(plugin).scanLoaded(repair);

        List<String> details = new ArrayList<>();
        details.add("Placed block records scanned: " + stats.blockRecordsScanned
                + "; legacy: " + stats.legacyBlocksFound + "; migrated: " + stats.blocksMigrated
                + "; failures: " + stats.blockFailures);
        details.add("Item stacks scanned: " + stats.itemStacksScanned
                + "; legacy: " + stats.legacyItemsFound + "; migrated: " + stats.itemsMigrated
                + "; failures: " + stats.itemFailures);
        details.add("Loaded inventories scanned: " + stats.inventoriesScanned + '.');
        details.add("Scope is loaded-only: loaded Slimefun data, loaded chunks/entities/containers and online players.");
        details.add("No chunks were force-loaded and no unmapped DynaTech IDs were rewritten.");
        if (!repair) {
            details.add("Read-only scan complete. Slimefun Doctor must approve a fingerprinted execution plan before repair.");
        } else {
            details.add("Migration preserved existing item metadata/PDC and placed-block key/value/menu state.");
            details.add("Run the DynaTech provider scan again after normal exploration to catch legacy content in newly loaded chunks.");
        }
        details.addAll(stats.details);

        return reportConstructor.newInstance(
                MIGRATION_NAME,
                repair,
                stats.scannedEntries(),
                stats.issuesFound(),
                stats.repairedEntries(),
                stats.failures,
                details);
    }
}
