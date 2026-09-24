package me.profelements.dynatech.utils;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import javax.annotation.Nullable;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;

/**
 * Modern DynaTech storage access backed by Slimefun's BlockDataController.
 *
 * <p>This deliberately mirrors the old BlockStorage behavior used by DynaTech:
 * synchronous reads load the block data first, "id" maps to the Slimefun id,
 * and writes preserve the same per-block string keys.</p>
 */
public final class SlimefunStorage {

    private SlimefunStorage() {
    }

    @Nullable
    public static SlimefunBlockData getBlockData(Location location) {
        if (location == null) {
            return null;
        }

        var controller = Slimefun.getDatabaseManager().getBlockDataController();
        SlimefunBlockData data = controller.getBlockData(location);

        if (data != null && !data.isDataLoaded()) {
            controller.loadBlockData(data);
        }

        return data;
    }

    @Nullable
    public static SlimefunItem getItem(Location location) {
        SlimefunBlockData data = getBlockData(location);
        return data == null ? null : SlimefunItem.getById(data.getSfId());
    }

    @Nullable
    public static SlimefunItem getItem(Block block) {
        return block == null ? null : getItem(block.getLocation());
    }

    @Nullable
    public static String getId(Block block) {
        SlimefunItem item = getItem(block);
        return item == null ? null : item.getId();
    }

    @Nullable
    public static String getData(Location location, String key) {
        SlimefunBlockData data = getBlockData(location);
        if (data == null) {
            return null;
        }

        return "id".equals(key) ? data.getSfId() : data.getData(key);
    }

    public static void setData(Location location, String key, String value) {
        if (location == null || key == null) {
            return;
        }

        var controller = Slimefun.getDatabaseManager().getBlockDataController();

        if ("id".equals(key)) {
            if (value != null) {
                controller.createBlock(location, value);
            }
            return;
        }

        SlimefunBlockData data = getBlockData(location);
        if (data == null) {
            return;
        }

        if (value == null) {
            data.removeData(key);
        } else {
            data.setData(key, value);
        }
    }

    public static void setData(Block block, String key, String value) {
        if (block != null) {
            setData(block.getLocation(), key, value);
        }
    }

    public static boolean hasBlockData(Location location) {
        return getBlockData(location) != null;
    }

    public static boolean hasBlockData(Block block) {
        return block != null && hasBlockData(block.getLocation());
    }

    @Nullable
    public static BlockMenu getMenu(Location location) {
        SlimefunBlockData data = getBlockData(location);
        return data == null ? null : data.getBlockMenu();
    }

    @Nullable
    public static BlockMenu getMenu(Block block) {
        return block == null ? null : getMenu(block.getLocation());
    }

    public static void store(Block block, ItemStack item) {
        if (block == null || item == null) {
            return;
        }

        SlimefunItem slimefunItem = SlimefunItem.getByItem(item);
        if (slimefunItem != null) {
            Slimefun.getDatabaseManager()
                    .getBlockDataController()
                    .createBlock(block.getLocation(), slimefunItem.getId());
        }
    }

    public static void clear(Location location) {
        if (location != null) {
            Slimefun.getDatabaseManager().getBlockDataController().removeBlock(location);
        }
    }

    public static void clear(Block block) {
        if (block != null) {
            clear(block.getLocation());
        }
    }
}
