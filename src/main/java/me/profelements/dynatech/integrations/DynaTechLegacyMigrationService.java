package me.profelements.dynatech.integrations;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import me.profelements.dynatech.DynaTech;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

/**
 * Manual, loaded-scope migration for verified pre-namespaced DynaTech IDs.
 *
 * <p>This service deliberately does not register listeners or force-load chunks. Slimefun Legacy's
 * migration-provider workflow owns authorization; DynaTech owns the actual persistence rewrite.</p>
 */
final class DynaTechLegacyMigrationService {

    private static final int MAX_NESTED_DEPTH = 4;
    private static final String MIGRATION_KEY = "dynatech_legacy_migrated";

    private final DynaTech plugin;
    private final Map<String, String> mappings = SlimefunLegacyIdMappings.mappings();
    private final Set<Inventory> seenInventories = Collections.newSetFromMap(new IdentityHashMap<>());
    private Object itemDataService;
    private Method getItemData;
    private Method setItemData;

    DynaTechLegacyMigrationService(@Nonnull DynaTech plugin) {
        this.plugin = plugin;
    }

    @Nonnull
    MigrationStats scanLoaded(boolean repair) {
        MigrationStats stats = new MigrationStats();
        resolveItemDataService(stats);

        scanLoadedSlimefunData(repair, stats);

        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunk(chunk, repair, stats);
            }
        }

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            scanInventory(player.getInventory(), repair, stats, 0);
            scanInventory(player.getEnderChest(), repair, stats, 0);
        }

        return stats;
    }

    private void scanLoadedSlimefunData(boolean repair, MigrationStats stats) {
        Object controller = blockDataController();
        if (controller == null) {
            stats.failures++;
            stats.details.add("Slimefun block-data controller was unavailable; placed DynaTech blocks were not scanned.");
            return;
        }

        for (Object blockData : loadedBlockData(controller)) {
            stats.blockRecordsScanned++;
            String sourceId = stringCall(blockData, "getSfId");
            if (sourceId == null) {
                continue;
            }

            Object menuObject = call(blockData, "getBlockMenu");
            if (menuObject instanceof BlockMenu menu) {
                scanInventory(menu.toInventory(), repair, stats, 0);
            }

            String targetId = mappings.get(sourceId);
            if (targetId == null) {
                continue;
            }

            stats.legacyBlocksFound++;
            if (!repair) {
                continue;
            }

            Location location = (Location) call(blockData, "getLocation");
            if (location == null || !isTargetRegistered(targetId)) {
                stats.blockFailures++;
                stats.failures++;
                stats.details.add("Skipped block " + sourceId + " -> " + targetId
                        + ": location or registered target was unavailable.");
                continue;
            }

            try {
                migrateBlock(controller, blockData, sourceId, targetId, location);
                stats.blocksMigrated++;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                stats.blockFailures++;
                stats.failures++;
                plugin.getLogger().log(Level.WARNING,
                        "DynaTech Doctor could not migrate block " + sourceId + " -> " + targetId + " at " + location,
                        ex);
            }
        }
    }

    private void scanChunk(Chunk chunk, boolean repair, MigrationStats stats) {
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof InventoryHolder holder) {
                scanInventory(holder.getInventory(), repair, stats, 0);
            }
        }

        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player) {
                continue;
            }

            if (entity instanceof Item itemEntity) {
                ItemStack stack = itemEntity.getItemStack();
                if (inspectItem(stack, repair, stats, 0)) {
                    itemEntity.setItemStack(stack);
                }
            } else if (entity instanceof ItemFrame frame) {
                ItemStack stack = frame.getItem();
                if (inspectItem(stack, repair, stats, 0)) {
                    frame.setItem(stack);
                }
            } else if (entity instanceof ItemDisplay display) {
                ItemStack stack = display.getItemStack();
                if (inspectItem(stack, repair, stats, 0)) {
                    display.setItemStack(stack);
                }
            } else if (entity instanceof LivingEntity living && living.getEquipment() != null) {
                var equipment = living.getEquipment();
                ItemStack main = equipment.getItemInMainHand();
                if (inspectItem(main, repair, stats, 0)) {
                    equipment.setItemInMainHand(main);
                }
                ItemStack off = equipment.getItemInOffHand();
                if (inspectItem(off, repair, stats, 0)) {
                    equipment.setItemInOffHand(off);
                }
                ItemStack[] armor = equipment.getArmorContents();
                boolean changed = false;
                for (int i = 0; i < armor.length; i++) {
                    changed |= inspectItem(armor[i], repair, stats, 0);
                }
                if (changed) {
                    equipment.setArmorContents(armor);
                }
            }

            if (entity instanceof InventoryHolder holder) {
                scanInventory(holder.getInventory(), repair, stats, 0);
            }
        }
    }

    private void scanInventory(Inventory inventory, boolean repair, MigrationStats stats, int depth) {
        if (inventory == null || !seenInventories.add(inventory)) {
            return;
        }

        stats.inventoriesScanned++;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (inspectItem(stack, repair, stats, depth)) {
                inventory.setItem(slot, stack);
            }
        }
    }

    private boolean inspectItem(@Nullable ItemStack stack, boolean repair, MigrationStats stats, int depth) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }

        stats.itemStacksScanned++;
        boolean changed = false;
        String sourceId = rawSlimefunId(stack);
        String targetId = sourceId == null ? null : mappings.get(sourceId);
        if (targetId != null) {
            stats.legacyItemsFound++;
            if (repair) {
                if (!isTargetRegistered(targetId) || setItemId(stack, targetId) == false) {
                    stats.itemFailures++;
                    stats.failures++;
                } else {
                    stats.itemsMigrated++;
                    changed = true;
                }
            }
        }

        if (depth >= MAX_NESTED_DEPTH || !stack.hasItemMeta()) {
            return changed;
        }

        var meta = stack.getItemMeta();
        if (meta instanceof BundleMeta bundleMeta) {
            List<ItemStack> items = new ArrayList<>(bundleMeta.getItems());
            boolean nestedChanged = false;
            for (ItemStack nested : items) {
                nestedChanged |= inspectItem(nested, repair, stats, depth + 1);
            }
            if (nestedChanged) {
                bundleMeta.setItems(items);
                stack.setItemMeta(bundleMeta);
                changed = true;
            }
        } else if (meta instanceof BlockStateMeta blockStateMeta
                && blockStateMeta.getBlockState() instanceof InventoryHolder holder) {
            Inventory nestedInventory = holder.getInventory();
            boolean nestedChanged = false;
            for (int slot = 0; slot < nestedInventory.getSize(); slot++) {
                ItemStack nested = nestedInventory.getItem(slot);
                if (inspectItem(nested, repair, stats, depth + 1)) {
                    nestedInventory.setItem(slot, nested);
                    nestedChanged = true;
                }
            }
            if (nestedChanged) {
                blockStateMeta.setBlockState(holder instanceof BlockState state ? state : blockStateMeta.getBlockState());
                stack.setItemMeta(blockStateMeta);
                changed = true;
            }
        }

        return changed;
    }

    private void resolveItemDataService(MigrationStats stats) {
        if (itemDataService != null) {
            return;
        }
        try {
            Method getter = Slimefun.class.getMethod("getItemDataService");
            itemDataService = getter.invoke(null);
            getItemData = itemDataService.getClass().getMethod("getItemData", ItemStack.class);
            setItemData = itemDataService.getClass().getMethod("setItemData", ItemStack.class, String.class);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            stats.failures++;
            stats.details.add("Slimefun item-data service was unavailable; legacy DynaTech ItemStacks cannot be rewritten.");
            plugin.getLogger().log(Level.WARNING, "DynaTech Doctor could not resolve Slimefun item-data service", ex);
        }
    }

    @Nullable
    private String rawSlimefunId(ItemStack stack) {
        if (itemDataService == null || getItemData == null) {
            return null;
        }
        try {
            Object result = getItemData.invoke(itemDataService, stack);
            if (result instanceof Optional<?> optional && optional.orElse(null) instanceof String id) {
                return id;
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().log(Level.FINE, "DynaTech Doctor could not read a Slimefun item ID", ex);
        }
        return null;
    }

    private boolean setItemId(ItemStack stack, String targetId) {
        if (itemDataService == null || setItemData == null) {
            return false;
        }
        try {
            setItemData.invoke(itemDataService, stack, targetId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "DynaTech Doctor could not rewrite an item ID to " + targetId, ex);
            return false;
        }
    }

    private boolean isTargetRegistered(String targetId) {
        return SlimefunItem.getById(targetId) != null;
    }

    private void migrateBlock(Object controller, Object oldData, String sourceId, String targetId, Location location)
            throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        Map<String, String> oldValues = new LinkedHashMap<>((Map<String, String>) call(oldData, "getAllData"));
        ItemStack[] menuContents = snapshotMenu(oldData);

        Method directSetter = findMethod(oldData.getClass(), "setSfId", String.class);
        if (directSetter != null) {
            directSetter.invoke(oldData, targetId);
            Method setData = findMethod(oldData.getClass(), "setData", String.class, String.class);
            if (setData != null) {
                setData.invoke(oldData, MIGRATION_KEY, "legacy:" + sourceId);
            }
            saveBlockInventory(controller, oldData);
            return;
        }

        Method remove = findOneArgMethod(controller.getClass(), "removeBlock", Location.class);
        Method create = findCreateBlock(controller.getClass());
        if (remove == null || create == null) {
            throw new NoSuchMethodException("Slimefun block controller has no compatible remove/create migration path");
        }

        remove.invoke(controller, location);
        Object newData = null;
        try {
            newData = create.invoke(controller, location, targetId);
            if (newData == null) {
                throw new IllegalStateException("Slimefun block controller returned null while creating " + targetId);
            }
            restoreBlockData(newData, oldValues, sourceId);
            restoreMenu(newData, menuContents);
            saveBlockInventory(controller, newData);
        } catch (ReflectiveOperationException | RuntimeException migrationFailure) {
            // Best-effort rollback to the exact stored legacy identity/state. Never change the Bukkit block itself.
            try {
                remove.invoke(controller, location);
                Object restored = create.invoke(controller, location, sourceId);
                if (restored != null) {
                    restoreBlockData(restored, oldValues, null);
                    restoreMenu(restored, menuContents);
                    saveBlockInventory(controller, restored);
                }
            } catch (ReflectiveOperationException | RuntimeException rollbackFailure) {
                migrationFailure.addSuppressed(rollbackFailure);
            }
            throw migrationFailure;
        }
    }

    private void restoreBlockData(Object data, Map<String, String> values, @Nullable String migratedFrom)
            throws ReflectiveOperationException {
        Method setData = findMethod(data.getClass(), "setData", String.class, String.class);
        if (setData == null) {
            if (!values.isEmpty()) {
                throw new NoSuchMethodException("Slimefun block data exposes no setData method");
            }
            return;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            setData.invoke(data, entry.getKey(), entry.getValue());
        }
        if (migratedFrom != null) {
            setData.invoke(data, MIGRATION_KEY, "legacy:" + migratedFrom);
        }
    }

    private void restoreMenu(Object data, @Nullable ItemStack[] contents) {
        if (contents == null) {
            return;
        }
        Object menuObject = call(data, "getBlockMenu");
        if (!(menuObject instanceof BlockMenu menu)) {
            return;
        }
        Inventory inventory = menu.toInventory();
        int length = Math.min(contents.length, inventory.getSize());
        for (int i = 0; i < length; i++) {
            inventory.setItem(i, contents[i] == null ? null : contents[i].clone());
        }
    }

    @Nullable
    private ItemStack[] snapshotMenu(Object data) {
        Object menuObject = call(data, "getBlockMenu");
        if (!(menuObject instanceof BlockMenu menu)) {
            return null;
        }
        ItemStack[] original = menu.toInventory().getContents();
        ItemStack[] copy = new ItemStack[original.length];
        for (int i = 0; i < original.length; i++) {
            copy[i] = original[i] == null ? null : original[i].clone();
        }
        return copy;
    }

    private void saveBlockInventory(Object controller, Object data) {
        Method save = findOneArgMethod(controller.getClass(), "saveBlockInventory", data.getClass());
        if (save == null) {
            for (Method method : controller.getClass().getMethods()) {
                if (method.getName().equals("saveBlockInventory") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isAssignableFrom(data.getClass())) {
                    save = method;
                    break;
                }
            }
        }
        if (save != null) {
            try {
                save.invoke(controller, data);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                plugin.getLogger().log(Level.FINE, "DynaTech Doctor could not explicitly flush a migrated block menu", ex);
            }
        }
    }

    @Nullable
    private Object blockDataController() {
        try {
            Method databaseGetter = Slimefun.class.getMethod("getDatabaseManager");
            Object databaseManager = databaseGetter.invoke(null);
            Method controllerGetter = databaseManager.getClass().getMethod("getBlockDataController");
            return controllerGetter.invoke(databaseManager);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "DynaTech Doctor could not resolve Slimefun block storage", ex);
            return null;
        }
    }

    @Nonnull
    private List<Object> loadedBlockData(Object controller) {
        List<Object> result = new ArrayList<>();
        Object chunks = call(controller, "getAllLoadedChunkData");
        if (chunks instanceof Collection<?> collection) {
            for (Object chunkData : collection) {
                Object blocks = call(chunkData, "getAllBlockData");
                if (blocks instanceof Collection<?> blockCollection) {
                    result.addAll(blockCollection);
                }
            }
            return result;
        }

        // Compatibility fallback for older/forked storage controllers.
        for (Field field : allFields(controller.getClass())) {
            try {
                field.setAccessible(true);
                Object value = field.get(controller);
                if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
                    continue;
                }
                Object sample = map.values().stream().filter(v -> v != null).findFirst().orElse(null);
                if (sample == null || findMethod(sample.getClass(), "getAllBlockData") == null) {
                    continue;
                }
                for (Object chunkData : map.values()) {
                    if (chunkData == null) {
                        continue;
                    }
                    Object blocks = call(chunkData, "getAllBlockData");
                    if (blocks instanceof Collection<?> blockCollection) {
                        result.addAll(blockCollection);
                    }
                }
                break;
            } catch (IllegalAccessException ignored) {
            }
        }
        return result;
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Collections.addAll(fields, current.getDeclaredFields());
        }
        return fields;
    }

    @Nullable
    private static Object call(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = findMethod(target.getClass(), methodName);
            return method == null ? null : method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    @Nullable
    private static String stringCall(Object target, String methodName) {
        Object value = call(target, methodName);
        return value instanceof String string ? string : null;
    }

    @Nullable
    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterTypes.length) {
                    return method;
                }
            }
            return null;
        }
    }

    @Nullable
    private static Method findOneArgMethod(Class<?> type, String name, Class<?> desiredType) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(desiredType)) {
                return method;
            }
        }
        return null;
    }

    @Nullable
    private static Method findCreateBlock(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals("createBlock") && method.getParameterCount() == 2
                    && method.getParameterTypes()[0].isAssignableFrom(Location.class)
                    && method.getParameterTypes()[1] == String.class) {
                return method;
            }
        }
        return null;
    }

    static final class MigrationStats {
        long blockRecordsScanned;
        long inventoriesScanned;
        long itemStacksScanned;
        long legacyBlocksFound;
        long legacyItemsFound;
        long blocksMigrated;
        long itemsMigrated;
        long blockFailures;
        long itemFailures;
        long failures;
        final List<String> details = new ArrayList<>();

        long scannedEntries() {
            return blockRecordsScanned + itemStacksScanned;
        }

        long issuesFound() {
            return legacyBlocksFound + legacyItemsFound;
        }

        long repairedEntries() {
            return blocksMigrated + itemsMigrated;
        }
    }
}
