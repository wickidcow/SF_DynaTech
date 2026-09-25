package me.profelements.dynatech.items.tools;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import io.github.thebusybiscuit.slimefun4.core.handlers.EntityInteractHandler;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.collections.Pair;
import io.github.thebusybiscuit.slimefun4.libraries.dough.data.persistent.PersistentDataAPI;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import me.profelements.dynatech.DynaTech;
import me.profelements.dynatech.registries.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class LiquidTank extends SlimefunItem implements NotPlaceable, Listener {

    private static final NamespacedKey FLUID_NAME = new NamespacedKey(DynaTech.getInstance(), "liquid-name");
    private static final NamespacedKey FLUID_AMOUNT = new NamespacedKey(DynaTech.getInstance(), "liquid-amount");

    private final int maxLiquidAmount;

    public LiquidTank(ItemGroup itemGroup, SlimefunItemStack item, int maxLiquidAmount, RecipeType recipeType,
            ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);

        this.maxLiquidAmount = maxLiquidAmount;

        Bukkit.getPluginManager().registerEvents(this, DynaTech.getInstance());
        addItemHandler(onEntityClick());
        addItemHandler(onRightClick());
    }

    private final EntityInteractHandler onEntityClick() {
        return (e, item, something) -> {
            if ((e.getRightClicked().getType() == EntityType.COW
                    || e.getRightClicked().getType() == EntityType.MOOSHROOM)
                    && SlimefunUtils.isItemSimilar(item, Items.LIQUID_TANK.stack(), true)) {
                e.setCancelled(true);
            }
        };
    }

    @EventHandler
    private void onBucketChange(PlayerBucketFillEvent e) {
        ItemStack item = e.getPlayer().getEquipment().getItem(e.getHand());
        if (this.isItem(item) && this.canUse(e.getPlayer(), true)
                && SlimefunItem.getByItem(item) instanceof LiquidTank tank) {
            e.setCancelled(true);
            // Check if block == LAVA or WATER
            String fluidName = PersistentDataAPI.getString(item.getItemMeta(), FLUID_NAME, "NO_LIQUID");
            int fluidAmount = PersistentDataAPI.getInt(item.getItemMeta(), FLUID_AMOUNT, 0);
            Block block = e.getBlock();
            if (block.isLiquid() && (fluidName.equals("NO_LIQUID") && fluidAmount == 0)
                    || fluidName.equals(block.getType().toString()) && fluidAmount + 1000 <= getMaxLiquidAmount()
                            && Slimefun.getProtectionManager().hasPermission(e.getPlayer(), block.getLocation(),
                                    Interaction.PLACE_BLOCK)) {
                ItemMeta meta = item.getItemMeta();

                PersistentDataAPI.setString(meta, FLUID_NAME, block.getType().toString());
                PersistentDataAPI.setInt(meta, FLUID_AMOUNT, fluidAmount + 1000);

                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("A Liquid tank holding up to 16 buckets of some liquids", NamedTextColor.GRAY));
                lore.add(Component.empty());
                lore.add(Component.text("Right click to grab a liquid"));
                lore.add(Component.text("Shift right click to place a liquid"));
                lore.add(Component.empty());
                lore.add(Component.text("Fluid Held: " + PersistentDataAPI.getString(meta, FLUID_NAME), NamedTextColor.WHITE));
                lore.add(Component.text("Fluid Amount: " + PersistentDataAPI.getInt(meta, FLUID_AMOUNT), NamedTextColor.WHITE));
                meta.lore(lore);
                item.setItemMeta(meta);
                DynaTech.runSync(() -> block.setType(Material.AIR));
            }
        }
    }

    @EventHandler
    private void onCauldronFill(CauldronLevelChangeEvent e) {
        if (e.getEntity() instanceof Player player) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (this.isItem(item) && this.canUse(player, true) && SlimefunItem.getByItem(item) instanceof LiquidTank) {
                e.setCancelled(true);
            }
        }
    }

    private final ItemUseHandler onRightClick() {
        return e -> {
            if (e.getPlayer().isSneaking() && e.getClickedBlock().isPresent()
                    && !e.getClickedBlock().get().isLiquid()) {
                ItemStack item = e.getItem();
                String fluidName = PersistentDataAPI.getString(item.getItemMeta(), FLUID_NAME, "NO_LIQUID");
                int fluidAmount = PersistentDataAPI.getInt(item.getItemMeta(), FLUID_AMOUNT, 0);
                if (this.canUse(e.getPlayer(), true) && this.isItem(item) && !fluidName.equals("NO_LIQUID")
                        && fluidAmount >= 1000) {
                    Material mat = Material.getMaterial(fluidName);

                    if (mat != null && e.getClickedBlock().isPresent()) {
                        Block block = e.getClickedBlock().get().getRelative(e.getClickedFace());
                        if ((block.isLiquid() || block.getType().isAir()) && block.getWorld().getEnvironment() != World.Environment.NETHER
                                && Slimefun.getProtectionManager().hasPermission(e.getPlayer(), block.getLocation(),
                                        Interaction.PLACE_BLOCK)) {
                            ItemMeta meta = item.getItemMeta();
                            if (fluidAmount - 1000 == 0) {
                                PersistentDataAPI.setString(meta, FLUID_NAME, "NO_LIQUID");
                            } else {
                                PersistentDataAPI.setString(meta, FLUID_NAME, fluidName);
                            }
                            PersistentDataAPI.setInt(meta, FLUID_AMOUNT, fluidAmount - 1000);

                            List<Component> lore = new ArrayList<>();
                            lore.add(Component.text("A Liquid tank holding up to 16 buckets of some liquids", NamedTextColor.GRAY));
                            lore.add(Component.empty());
                            lore.add(Component.text("Right click to grab a liquid"));
                            lore.add(Component.text("Shift right click to place a liquid"));
                            lore.add(Component.empty());
                            lore.add(Component.text("Fluid Held: " + PersistentDataAPI.getString(meta, FLUID_NAME), NamedTextColor.WHITE));
                            lore.add(Component.text("Fluid Amount: " + PersistentDataAPI.getInt(meta, FLUID_AMOUNT), NamedTextColor.WHITE));
                            meta.lore(lore);
                            item.setItemMeta(meta);
                            DynaTech.runSync(() -> block.setType(mat));
                        }
                    }
                }
            }

        };
    }

    public int getMaxLiquidAmount() {
        return maxLiquidAmount;
    }

    public static final List<String> getPlaceableFluids() {
        List<String> placeableFluids = new ArrayList<>();
        placeableFluids.add("WATER");
        placeableFluids.add("LAVA");

        return placeableFluids;
    }

    public void addLiquid(ItemStack item, String fluidName, int fluidAmount) {
        ItemMeta im = item.getItemMeta();

        String itemFluidName = PersistentDataAPI.getString(im, FLUID_NAME);
        int itemFluidAmount = PersistentDataAPI.getInt(im, FLUID_AMOUNT);

        int resultFluidAmount = itemFluidAmount + fluidAmount;
        if (itemFluidName != null && itemFluidName.equals(fluidName) && itemFluidAmount != 0
                && resultFluidAmount <= getMaxLiquidAmount()) {
            setLiquid(item, fluidName, resultFluidAmount);
        } else if (resultFluidAmount >= getMaxLiquidAmount()) {
            setLiquid(item, fluidName, getMaxLiquidAmount());
        } else {
            setLiquid(item, fluidName, fluidAmount);
        }

    }

    public void removeLiquid(ItemStack item, String fluidName, int fluidAmount) {
        ItemMeta im = item.getItemMeta();

        String itemFluidName = PersistentDataAPI.getString(im, FLUID_NAME);
        int itemFluidAmount = PersistentDataAPI.getInt(im, FLUID_AMOUNT);

        int resultFluidAmount = itemFluidAmount - fluidAmount;
        if (itemFluidName != null && itemFluidName.equals(fluidName) && itemFluidAmount != 0 && resultFluidAmount > 0) {
            setLiquid(item, fluidName, resultFluidAmount);
        } else {
            setLiquid(item, "NO_FLUID", 0);
        }
    }

    public void setLiquid(ItemStack item, String fluidName, int fluidAmount) {
        ItemMeta im = item.getItemMeta();

        PersistentDataAPI.setString(im, FLUID_NAME, fluidName);
        PersistentDataAPI.setInt(im, FLUID_AMOUNT, fluidAmount);

        item.setItemMeta(im);
    }

    public Pair<String, Integer> getLiquid(ItemStack item) {
        String fluidName = PersistentDataAPI.getString(item.getItemMeta(), FLUID_NAME);
        int fluidAmount = PersistentDataAPI.getInt(item.getItemMeta(), FLUID_AMOUNT);
        if (item.hasItemMeta() && fluidName != null && fluidAmount != 0) {
            return new Pair<>(fluidName, fluidAmount);
        }
        return new Pair<>("NO_FLUID", 0);
    }

    public void updateLore(ItemStack item) {
        String fluidName = PersistentDataAPI.getString(item.getItemMeta(), FLUID_NAME);
        int fluidAmount = PersistentDataAPI.getInt(item.getItemMeta(), FLUID_AMOUNT);

        ItemMeta im = item.getItemMeta();
        List<Component> lore = im.lore() == null ? new ArrayList<>() : new ArrayList<>(im.lore());

        if (fluidName == null) {
            return;
        }

        for (int i = 0; i < lore.size(); i++) {
            String plain = PlainTextComponentSerializer.plainText().serialize(lore.get(i));
            if (plain.contains("Fluid Held: ")) {
                lore.set(i, Component.text("Fluid Held: " + fluidName, NamedTextColor.WHITE));
            }

            if (plain.contains("Amount: ")) {
                lore.set(i, Component.text("Amount: " + fluidAmount + "mb / " + getMaxLiquidAmount(), NamedTextColor.WHITE));
            }
        }

        im.lore(lore);
        item.setItemMeta(im);
    }

}
