package me.profelements.dynatech.utils;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Location;

public final class BlockDataUtils {

    private BlockDataUtils() {
    }

    private static com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData getBlockData(Location location) {
        if (location == null) {
            return null;
        }

        var controller = Slimefun.getDatabaseManager().getBlockDataController();
        var data = controller.getBlockData(location);

        if (data != null && !data.isDataLoaded()) {
            controller.loadBlockData(data);
        }

        return data;
    }

    public static String getData(Location location, String key) {
        var data = getBlockData(location);
        if (data == null) {
            return null;
        }

        return "id".equals(key) ? data.getSfId() : data.getData(key);
    }

    public static void setData(Location location, String key, String value) {
        if (location == null) {
            return;
        }

        var controller = Slimefun.getDatabaseManager().getBlockDataController();

        if ("id".equals(key)) {
            controller.createBlock(location, value);
            return;
        }

        var data = getBlockData(location);
        if (data != null) {
            if (value == null) {
                data.removeData(key);
            } else {
                data.setData(key, value);
            }
        }
    }

    public static void storeId(Location location, String id) {
        if (location != null && id != null) {
            Slimefun.getDatabaseManager().getBlockDataController().createBlock(location, id);
        }
    }

    public static String checkId(Location location) {
        return getData(location, "id");
    }

    public static boolean hasBlockInfo(Location location) {
        return getBlockData(location) != null;
    }

    public static BlockMenu getInventory(Location location) {
        var data = getBlockData(location);
        return data == null ? null : data.getBlockMenu();
    }

    public static void clear(Location location) {
        if (location != null) {
            Slimefun.getDatabaseManager().getBlockDataController().removeBlock(location);
        }
    }
}
