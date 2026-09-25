package me.profelements.dynatech.items.misc;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.libraries.dough.data.persistent.PersistentDataAPI;
import me.profelements.dynatech.DynaTech;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class ItemBand extends SlimefunItem {

    public static final NamespacedKey KEY = new NamespacedKey(DynaTech.getInstance(), "item_band");
    private final PotionEffect[] potionEffects;

    public ItemBand(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe, PotionEffect[] potionEffects) {
        super(itemGroup, item, recipeType, recipe);
        
        this.potionEffects = potionEffects;
    }

    public PotionEffect[] getPotionEffects() {
        return potionEffects;
    }

    public static boolean containsItemBand(ItemStack item) {
        if (item != null && item.getType() != Material.AIR && item.hasItemMeta()) {
            return PersistentDataAPI.getString(item.getItemMeta(), KEY) != null;
        } else {
            return false;
        }
    }

    @Nullable
    public ItemStack applyToItem(@Nullable ItemStack item) {
        if (item != null && item.getType() != Material.AIR) {
           

            ItemMeta im = item.getItemMeta();
            List<Component> lore = im.hasLore() ? new ArrayList<>(im.lore()) : new ArrayList<>();
            
            lore.add(Component.text("Bandaid: " + getPotionEffects()[0].getType().getKey().getKey(), NamedTextColor.WHITE));
            PersistentDataAPI.setString(im, KEY, this.getId());

            im.lore(lore);
            item.setItemMeta(im);
            return item;
        }
        return null;
    }

    @Nullable
    public static ItemStack removeFromItem(@Nullable ItemStack item) {
        if (item != null && item.getType() != Material.AIR) {
            ItemMeta im = item.getItemMeta();
            List<Component> lore = im.lore() == null ? new ArrayList<>() : new ArrayList<>(im.lore());
            
            im.getPersistentDataContainer().remove(KEY);

            lore.removeIf(line -> PlainTextComponentSerializer.plainText().serialize(line).contains("Bandaid: "));
    
            im.lore(lore);
            item.setItemMeta(im);

            return item;
        }
        return null;
    }
    
}
