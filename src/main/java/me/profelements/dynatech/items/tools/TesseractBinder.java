package me.profelements.dynatech.items.tools;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.data.persistent.PersistentDataAPI;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import me.profelements.dynatech.utils.SlimefunStorage;
import me.profelements.dynatech.items.electric.transfer.Tesseract;
import me.profelements.dynatech.registries.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Optional;

public class TesseractBinder extends SlimefunItem {
    public TesseractBinder(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);

        addItemHandler(bindTesseract());
    }

    private ItemUseHandler bindTesseract() {
        return e -> {
            e.cancel();

            Optional<Block> block = e.getClickedBlock();
            Optional<SlimefunItem> sfBlock = e.getSlimefunBlock();
            if (block.isPresent() && sfBlock.isPresent()) {
                Location blockLocation = block.get().getLocation();
                SlimefunItem sfItem = sfBlock.get();
                ItemStack item = e.getItem();
                Boolean hasPermision = Slimefun.getProtectionManager().hasPermission(e.getPlayer(), blockLocation,
                        Interaction.INTERACT_BLOCK);

                if (e.getPlayer().isSneaking()) {
                    String locString = PersistentDataAPI.getString(item.getItemMeta(), Tesseract.WIRELESS_LOCATION_KEY);
                    String blockId = SlimefunStorage.getData(blockLocation, "id");
                    if (item != null && Boolean.TRUE.equals(hasPermision)
                            && Items.TESSERACT.stack().getItemId().equals(blockId)
                            && item.hasItemMeta() && locString != null) {
                        SlimefunStorage.setData(blockLocation, "tesseract-pair-location", locString);
                        e.getPlayer().sendActionBar(Component.text("Tesseract Connected!", NamedTextColor.WHITE));
                    }
                } else if (Boolean.TRUE.equals(hasPermision)
                        && sfItem.getId().equals(Items.TESSERACT.stack().getItemId()) && blockLocation != null) {
                    ItemMeta im = item.getItemMeta();
                    String locString = Tesseract.locationToString(blockLocation);

                    PersistentDataAPI.setString(im, Tesseract.WIRELESS_LOCATION_KEY, locString);
                    item.setItemMeta(im);
                    Tesseract.setItemLore(item, blockLocation);
                }

            }
        };
    }
}
