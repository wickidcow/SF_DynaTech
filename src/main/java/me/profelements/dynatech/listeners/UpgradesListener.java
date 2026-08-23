package me.profelements.dynatech.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import io.github.thebusybiscuit.slimefun4.api.events.AsyncMachineOperationFinishEvent;
import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import io.github.thebusybiscuit.slimefun4.libraries.paperlib.PaperLib;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.AContainer;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.profelements.dynatech.DynaTech;
import me.profelements.dynatech.items.tools.AutoOutputUpgrade;
import me.profelements.dynatech.registries.Items;

public class UpgradesListener implements Listener {

    public UpgradesListener(DynaTech plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onMachineFinish(AsyncMachineOperationFinishEvent e) {
        if (!(e.getOperation() instanceof CraftingOperation)) {
            return;
        }

        checkInputUpgrade(e);

        Location l = e.getPosition().toLocation();
        String upgrades = BlockStorage.getLocationInfo(l, "upgrades");
        if (upgrades == null) {
            return;
        }

        int upgradeIdx = upgrades.indexOf("{id:auto_output");
        if (upgradeIdx == -1) {
            return;
        }

        int upgradeIdx2 = upgrades.indexOf("}", upgradeIdx);
        String upgradeString = upgrades.substring(upgradeIdx, upgradeIdx2 + 1);

        if (upgrades.contains("id:auto_output")) {
            int index = upgradeString.indexOf("face:");
            int index2 = upgradeString.indexOf("}");
            BlockFace face = AutoOutputUpgrade.stringToBlockFace(upgradeString.substring(index, index2));

            if (e.getProcessor().getOwner() instanceof AContainer cont
                    && e.getOperation() instanceof CraftingOperation op && op.isFinished()) {
                int[] outputSlots = cont.getOutputSlots();
                ItemStack[] outputItems = op.getResults();

                if (l.getBlock().getRelative(face).getType().equals(Material.CHEST)) {
                    // The finish event is async. Deposit and consume in one main-thread task so
                    // players cannot race the transfer and a full chest never causes item loss.
                    DynaTech.runSync(() -> depositOutputIntoChest(l, face, outputSlots, outputItems));
                }
            }
        }
    }

    private static void depositOutputIntoChest(
            Location machineLocation, BlockFace face, int[] outputSlots, ItemStack[] outputItems) {
        BlockState state = PaperLib.getBlockState(machineLocation.getBlock().getRelative(face), false).getState();
        if (!(state instanceof Chest chest)) {
            return;
        }

        BlockMenu menu = BlockStorage.getInventory(machineLocation);
        if (menu == null) {
            return;
        }

        Inventory inventory = chest.getBlockInventory();
        boolean moved = false;

        for (int slot : outputSlots) {
            ItemStack item = menu.getItemInSlot(slot);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }

            boolean matchesResult = false;
            for (ItemStack outputItem : outputItems) {
                if (outputItem != null && SlimefunUtils.isItemSimilar(item, outputItem, true)) {
                    matchesResult = true;
                    break;
                }
            }
            if (!matchesResult) {
                continue;
            }

            int available = item.getAmount();
            int leftover = 0;
            for (ItemStack rest : inventory.addItem(item.clone()).values()) {
                leftover += rest.getAmount();
            }

            int accepted = available - leftover;
            if (accepted > 0) {
                menu.consumeItem(slot, accepted);
                moved = true;
            }
        }

        if (moved) {
            chest.update(true, false);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Location l = e.getBlock().getLocation();
        String upgrades = BlockStorage.getLocationInfo(l, "upgrades");

        if (upgrades != null && upgrades.contains("auto_output")) {
            l.getWorld().dropItemNaturally(l, Items.AUTO_OUTPUT_UPGRADE.stack());
        }

        if (upgrades != null && upgrades.contains("auto_input")) {
            l.getWorld().dropItemNaturally(l, Items.AUTO_INPUT_UPGRADE.stack());
        }
    }

    private static void checkInputUpgrade(AsyncMachineOperationFinishEvent e) {
        Location l = e.getPosition().toLocation();
        String upgrades = BlockStorage.getLocationInfo(l, "upgrades");
        if (upgrades == null) {
            return;
        }

        int upgradeIdx = upgrades.indexOf("{id:auto_input");
        if (upgradeIdx == -1) {
            return;
        }

        int upgradeIdx2 = upgrades.indexOf("}", upgradeIdx);
        String upgradeString = upgrades.substring(upgradeIdx, upgradeIdx2 + 1);

        if (upgradeString.contains("id:auto_input")) {
            int index = upgradeString.indexOf("face:");
            int index2 = upgradeString.indexOf("}");
            BlockFace face = AutoOutputUpgrade.stringToBlockFace(upgradeString.substring(index, index2));
            if (face == BlockFace.SELF) {
                return;
            }

            DynaTech.runSync(() -> {
                BlockState state = PaperLib.getBlockState(l.getBlock().getRelative(face), false).getState();
                if (state instanceof Chest chest && e.getProcessor().getOwner() instanceof AContainer acont) {
                    BlockMenu inv = BlockStorage.getInventory(l);
                    if (inv == null) {
                        return;
                    }

                    int[] slots = acont.getInputSlots();
                    for (int slot : slots) {
                        Inventory chestInventory = chest.getBlockInventory();
                        ItemStack inputStack = inv.getItemInSlot(slot);
                        for (ItemStack stack : chestInventory.getContents()) {
                            if (inputStack == null && stack != null
                                    || inputStack != null && stack != null && stack.isSimilar(inputStack)) {
                                int chestAmount = stack.getAmount();

                                if (inputStack == null) {
                                    inv.pushItem(stack, acont.getInputSlots());
                                    chestInventory.remove(stack);
                                } else if (inputStack.getAmount() != inputStack.getMaxStackSize()) {
                                    int diff = inputStack.getMaxStackSize() - inputStack.getAmount();
                                    if (diff >= chestAmount) {
                                        inputStack.setAmount(inputStack.getAmount() + chestAmount);
                                        chestInventory.remove(stack);
                                    } else {
                                        inputStack.setAmount(inputStack.getAmount() + diff);
                                        stack.setAmount(chestAmount - diff);
                                    }
                                }
                            }
                        }
                    }
                }
            });
        }
    }
}
