package me.profelements.dynatech.integrations;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import me.profelements.dynatech.DynaTech;
import me.profelements.dynatech.registries.Items;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Publishes verified historical DynaTech item IDs to Slimefun Legacy's optional diagnostic registry.
 *
 * <p>The old IDs in this table come from the pre-namespaced DynaTech registry. The targets are derived from the
 * current {@link Items.Keys} entries, so the mapping cannot drift silently when a current key is refactored.
 * Reflection keeps this addon loadable on Slimefun variants that do not expose Legacy's diagnostic API.</p>
 */
public final class SlimefunLegacyIdMappings {

    private static final Map<String, String> MAPPINGS = buildMappings();

    private SlimefunLegacyIdMappings() {
    }

    public static Map<String, String> mappings() {
        return MAPPINGS;
    }

    public static void publish(DynaTech plugin) {
        Object registry = Slimefun.getRegistry();
        Method register = findRegistrationMethod(registry);
        if (register == null) {
            return;
        }

        int published = 0;
        int failed = 0;
        for (Map.Entry<String, String> entry : MAPPINGS.entrySet()) {
            try {
                register.invoke(registry, entry.getKey(), entry.getValue());
                published++;
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException ex) {
                failed++;
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not publish DynaTech legacy item mapping " + entry.getKey() + " -> " + entry.getValue(),
                        ex);
            }
        }

        if (published > 0) {
            plugin.getLogger().info("Published " + published + " verified legacy DynaTech item ID mappings to Slimefun Doctor.");
        }
        if (failed > 0) {
            plugin.getLogger().warning(failed + " DynaTech legacy item ID mapping(s) could not be published.");
        }
    }

    private static Method findRegistrationMethod(Object registry) {
        for (Method method : registry.getClass().getMethods()) {
            if (method.getName().equals("registerLegacySlimefunItemId")
                    && method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == String.class
                    && method.getParameterTypes()[1] == String.class) {
                return method;
            }
        }
        return null;
    }

    private static Map<String, String> buildMappings() {
        Map<String, String> mappings = new LinkedHashMap<>();

        // Historical resources and components.
        map(mappings, "STAINLESS_STEEL", Items.Keys.STAINLESS_STEEL_INGOT.asSlimefunId());
        map(mappings, "STAINLESS_STEEL_ROTOR", Items.Keys.STAINLESS_STEEL_ROTOR.asSlimefunId());
        map(mappings, "ANCIENT_MACHINE_CORE", Items.Keys.ANCIENT_MACHINE_CORE.asSlimefunId());
        map(mappings, "VEX_GEM", Items.Keys.VEX_GEM.asSlimefunId());
        map(mappings, "MACHINE_SCRAP", Items.Keys.MACHINE_SCRAP.asSlimefunId());
        map(mappings, "ADVANCED_MACHINE_SCRAP", Items.Keys.ADVANCED_MACHINE_SCRAP.asSlimefunId());
        map(mappings, "STAR_DUST", Items.Keys.STAR_DUST.asSlimefunId());
        map(mappings, "GHOSTLY_ESSENCE", Items.Keys.GHOSTLY_ESSENCE.asSlimefunId());
        map(mappings, "TESSERACTING_OBJ", Items.Keys.TESSERACTING_OBJ.asSlimefunId());
        map(mappings, "BEE", Items.Keys.BEE.asSlimefunId());
        map(mappings, "ROBOTIC_BEE", Items.Keys.ROBOTIC_BEE.asSlimefunId());
        map(mappings, "ADVANCED_ROBOTIC_BEE", Items.Keys.ADVANCED_ROBOTIC_BEE.asSlimefunId());

        // Historical tools and portable items.
        map(mappings, "PICNIC_BASKET", Items.Keys.PICNIC_BASKET.asSlimefunId());
        map(mappings, "SOULBOUND_PICNIC_BASKET", Items.Keys.SOUL_BOUND_PICNIC_BASKET.asSlimefunId());
        map(mappings, "INVENTORY_FILTER", Items.Keys.INVENTORY_FILTER.asSlimefunId());
        map(mappings, "ELECTRICAL_STIMULATOR", Items.Keys.ELECTRICAL_STIMULATOR.asSlimefunId());
        map(mappings, "ANGEL_GEM", Items.Keys.ANGEL_GEM.asSlimefunId());
        map(mappings, "SCOOP", Items.Keys.SCOOP.asSlimefunId());
        map(mappings, "DIMENSIONAL_HOME", Items.Keys.DIMENSIONAL_HOME.asSlimefunId());
        map(mappings, "ITEM_BAND_HEALTH", Items.Keys.ITEM_BAND_HEALTH.asSlimefunId());
        map(mappings, "ITEM_BAND_HASTE", Items.Keys.ITEM_BAND_HASTE.asSlimefunId());
        map(mappings, "TESSERACT_BINDER", Items.Keys.TESSERACT_BINDER.asSlimefunId());
        map(mappings, "LIQUID_TANK", Items.Keys.LIQUID_TANK.asSlimefunId());
        map(mappings, "WITHER_GOLEM", Items.Keys.WITHER_SKELETON_GOLEM.asSlimefunId());

        // Historical machines and transfer blocks.
        map(mappings, "AUTO_KITCHEN", Items.Keys.AUTO_KITCHEN.asSlimefunId());
        map(mappings, "GROWTH_CHAMBER", Items.Keys.GROWTH_CHAMBER.asSlimefunId());
        map(mappings, "GROWTH_CHAMBER_MK2", Items.Keys.GROWTH_CHAMBER_MK2.asSlimefunId());
        map(mappings, "GROWTH_CHAMBER_END", Items.Keys.GROWTH_CHAMBER_END.asSlimefunId());
        map(mappings, "GROWTH_CHAMBER_END_MK2", Items.Keys.GROWTH_CHAMBER_MK2_END.asSlimefunId());
        map(mappings, "GROWTH_CHAMBER_NETHER", Items.Keys.GROWTH_CHAMBER_NETHER.asSlimefunId());
        map(mappings, "GROWTH_CHAMBER_NETHER_MK2", Items.Keys.GROWTH_CHAMBER_MK2_NETHER.asSlimefunId());
        map(mappings, "GROWTH_CHAMBER_OCEAN", Items.Keys.GROWTH_CHAMBER_OCEAN.asSlimefunId());
        map(mappings, "GROWTH_CHAMBER_OCEAN_MK2", Items.Keys.GROWTH_CHAMBER_MK2_OCEAN.asSlimefunId());
        map(mappings, "ANTIGRAVITY_BUBBLE", Items.Keys.ANTIGRAVITY_BUBBLE.asSlimefunId());
        map(mappings, "WEATHER_CONTROLLER", Items.Keys.WEATHER_CONTROLLER.asSlimefunId());
        map(mappings, "POTION_SPRINKLER", Items.Keys.POTION_SPRINKLER.asSlimefunId());
        map(mappings, "BARBED_WIRE", Items.Keys.BARBED_WIRE.asSlimefunId());
        map(mappings, "MATERIAL_HIVE", Items.Keys.MATERIAL_HIVE.asSlimefunId());
        map(mappings, "WIRELESS_CHARGER", Items.Keys.WIRELESS_CHARGER.asSlimefunId());
        map(mappings, "SEED_PLUCKER", Items.Keys.SEED_PLUCKER.asSlimefunId());
        map(mappings, "BANDAID_MANAGER", Items.Keys.BANDAID_MANAGER.asSlimefunId());
        map(mappings, "ORECHID", Items.Keys.ORECHID.asSlimefunId());
        map(mappings, "WIRELESS_ENERGY_POINT", Items.Keys.WIRELESS_ENERGY_POINT.asSlimefunId());
        map(mappings, "WIRELESS_ENERGY_BANK", Items.Keys.WIRELESS_ENERGY_BANK.asSlimefunId());
        map(mappings, "WIRELESS_ITEM_INPUT", Items.Keys.WIRELESS_ITEM_INPUT.asSlimefunId());
        map(mappings, "WIRELESS_ITEM_OUTPUT", Items.Keys.WIRELESS_ITEM_OUTPUT.asSlimefunId());
        map(mappings, "TESSERACT", Items.Keys.TESSERACT.asSlimefunId());
        map(mappings, "DT_EXTERNAL_HEATER", Items.Keys.EXTERNAL_HEATER.asSlimefunId());

        // Historical generators that have a proven one-to-one successor.
        map(mappings, "WATER_MILL", Items.Keys.WATER_MILL.asSlimefunId());
        map(mappings, "WATER_TURBINE", Items.Keys.WATER_MILL_2.asSlimefunId());
        map(mappings, "CHIPPING_GENERATOR", Items.Keys.DURABILITY_GENERATOR.asSlimefunId());
        map(mappings, "CULINARY_GENERATOR", Items.Keys.FOOD_GENERATOR.asSlimefunId());
        map(mappings, "STARDUST_REACTOR", Items.Keys.STARDUST_GENERATOR.asSlimefunId());

        // DRAGON_GENERATOR intentionally has no mapping: there is no proven one-to-one current successor.
        return Collections.unmodifiableMap(mappings);
    }

    private static void map(Map<String, String> mappings, String legacyId, String currentId) {
        String previous = mappings.put(legacyId, currentId);
        if (previous != null && !previous.equals(currentId)) {
            throw new IllegalStateException("Conflicting DynaTech legacy mapping for " + legacyId);
        }
    }
}
