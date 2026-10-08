package me.profelements.dynatech.items.electric.transfer;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Resolves wireless inventories without loading a world, chunk, or block record. */
final class WirelessItemTransferGuard {

    private WirelessItemTransferGuard() {
    }

    static SlimefunBlockData getLiveData(Location location, String machineId) {
        if (location == null || !Bukkit.isPrimaryThread()) {
            return null;
        }

        World world = location.getWorld();
        if (world == null || Bukkit.getWorld(world.getUID()) != world
                || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return null;
        }

        SlimefunBlockData data = StorageCacheUtils.getBlock(location);
        if (data == null || !data.isDataLoaded() || data.isPendingRemove()
                || !machineId.equals(data.getSfId())) {
            return null;
        }

        BlockMenu menu = data.getBlockMenu();
        return menu == null || menu.locked() ? null : data;
    }

    static boolean isCurrent(SlimefunBlockData data, BlockMenu menu, String machineId) {
        return data != null && menu != null && getLiveData(data.getLocation(), machineId) == data
                && data.getBlockMenu() == menu;
    }
}
