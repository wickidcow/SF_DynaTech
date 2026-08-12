package me.profelements.dynatech.integrations;

import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.profelements.dynatech.DynaTech;
import me.profelements.dynatech.registries.Items;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Optional bridge for InfinityExpansion2.
 *
 * <p>DynaTech historically compiled directly against InfinityExpansion 1's mob-data classes.
 * IE2 has a different Kotlin API. Keeping this bridge reflective prevents an optional addon from
 * becoming a hard class-loader dependency while still preserving DynaTech's Vex and Phantom cards
 * when IE2 is installed.</p>
 */
public final class InfinityExpansion2Integration {

    private static final String PLUGIN_NAME = "InfinityExpansion2";
    private static final String API_CLASS = "net.guizhanss.infinityexpansion2.api.InfinityExpansion2API";
    private static final String PROPS_CLASS = "net.guizhanss.infinityexpansion2.api.mobsim.MobDataCardProps";
    private static final String ITEMS_CLASS = "net.guizhanss.infinityexpansion2.implementation.IEItems";

    private InfinityExpansion2Integration() {
    }

    public static void register(DynaTech addon) {
        Plugin ie2 = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (ie2 == null || !ie2.isEnabled()) {
            return;
        }

        try {
            ClassLoader loader = ie2.getClass().getClassLoader();
            Class<?> apiClass = Class.forName(API_CLASS, true, loader);
            Class<?> propsClass = Class.forName(PROPS_CLASS, true, loader);
            Class<?> pairClass = Class.forName("kotlin.Pair", true, loader);

            Constructor<?> pairConstructor = pairClass.getConstructor(Object.class, Object.class);
            Constructor<?> propsConstructor = findPropsConstructor(propsClass);
            Method registerMethod = apiClass.getMethod("registerMobDataCard", propsClass, SlimefunAddon.class);

            ItemStack emptyCard = resolveEmptyDataCard(loader);
            if (emptyCard == null) {
                addon.getLogger().warning("InfinityExpansion2 is installed, but its empty mob data card could not be resolved; DynaTech IE2 cards were skipped.");
                return;
            }

            registerVex(addon, pairConstructor, propsConstructor, registerMethod, emptyCard);
            registerPhantom(addon, pairConstructor, propsConstructor, registerMethod, emptyCard);
            addon.getLogger().info("InfinityExpansion2 bridge enabled: registered DynaTech Vex and Phantom mob simulation cards.");
        } catch (ReflectiveOperationException | LinkageError ex) {
            addon.getLogger().log(Level.WARNING,
                    "InfinityExpansion2 was detected, but the optional DynaTech integration could not be initialized. DynaTech will continue without IE2 mob cards.", ex);
        }
    }

    private static Constructor<?> findPropsConstructor(Class<?> propsClass) throws NoSuchMethodException {
        for (Constructor<?> constructor : propsClass.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 7
                    && parameterTypes[0] == String.class
                    && parameterTypes[1] == String.class
                    && ItemStack.class.isAssignableFrom(parameterTypes[2])
                    && parameterTypes[3] == int.class
                    && parameterTypes[4] == int.class
                    && List.class.isAssignableFrom(parameterTypes[5])
                    && parameterTypes[6].isArray()) {
                return constructor;
            }
        }
        throw new NoSuchMethodException("Compatible MobDataCardProps constructor not found");
    }

    private static ItemStack resolveEmptyDataCard(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> itemsClass = Class.forName(ITEMS_CLASS, true, loader);
        Object instance = itemsClass.getField("INSTANCE").get(null);
        Object value = itemsClass.getMethod("getMOB_DATA_CARD_EMPTY").invoke(instance);

        if (value instanceof SlimefunItem slimefunItem) {
            return slimefunItem.getItem().clone();
        }
        if (value instanceof ItemStack itemStack) {
            return itemStack.clone();
        }
        return null;
    }

    private static void registerVex(DynaTech addon, Constructor<?> pairConstructor, Constructor<?> propsConstructor,
                                    Method registerMethod, ItemStack emptyCard) throws ReflectiveOperationException {
        ItemStack[] recipe = new ItemStack[] {
                amount(Items.VEX_GEM.stack(), 16), amount(Items.GHOSTLY_ESSENCE.stack(), 16), amount(Items.VEX_GEM.stack(), 16),
                amount(Items.GHOSTLY_ESSENCE.stack(), 16), emptyCard.clone(), amount(Items.GHOSTLY_ESSENCE.stack(), 16),
                amount(Items.VEX_GEM.stack(), 16), amount(Items.GHOSTLY_ESSENCE.stack(), 16), amount(Items.VEX_GEM.stack(), 16)
        };

        List<Object> drops = new ArrayList<>();
        drops.add(pairConstructor.newInstance(Items.VEX_GEM.stack().clone(), 0.10D));
        drops.add(pairConstructor.newInstance(Items.GHOSTLY_ESSENCE.stack().clone(), 0.90D));

        Object props = propsConstructor.newInstance(
                "dynatech_vex", "Vex", Items.VEX_GEM.stack().clone(), 600, 4, drops, recipe);
        registerMethod.invoke(null, props, addon);
    }

    private static void registerPhantom(DynaTech addon, Constructor<?> pairConstructor, Constructor<?> propsConstructor,
                                        Method registerMethod, ItemStack emptyCard) throws ReflectiveOperationException {
        ItemStack membrane = new ItemStack(Material.PHANTOM_MEMBRANE, 16);
        ItemStack[] recipe = new ItemStack[] {
                membrane.clone(), membrane.clone(), membrane.clone(),
                membrane.clone(), emptyCard.clone(), membrane.clone(),
                membrane.clone(), membrane.clone(), membrane.clone()
        };

        List<Object> drops = new ArrayList<>();
        drops.add(pairConstructor.newInstance(new ItemStack(Material.PHANTOM_MEMBRANE), 0.25D));

        Object props = propsConstructor.newInstance(
                "dynatech_phantom", "Phantom", new ItemStack(Material.PHANTOM_MEMBRANE), 300, 2, drops, recipe);
        registerMethod.invoke(null, props, addon);
    }

    private static ItemStack amount(ItemStack source, int amount) {
        ItemStack copy = source.clone();
        copy.setAmount(amount);
        return copy;
    }
}
