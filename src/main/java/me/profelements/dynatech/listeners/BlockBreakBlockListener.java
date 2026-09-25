package me.profelements.dynatech.listeners;

import java.util.List;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import me.profelements.dynatech.utils.SlimefunStorage;

public class BlockBreakBlockListener implements Listener {

    public BlockBreakBlockListener(Plugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onBlockBreakBlock(BlockBreakBlockEvent event) {

        SlimefunItem sfItem = SlimefunStorage.getItem(event.getBlock());

        if (sfItem != null) {
            SlimefunStorage.clear(event.getBlock());

            List<ItemStack> drops = event.getDrops();
            drops.clear();

            drops.add(sfItem.getItem());
        }
    }

}
