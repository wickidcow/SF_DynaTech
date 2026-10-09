package audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.xzavier0722.mc.plugin.slimefun4.storage.common.DataScope;
import com.xzavier0722.mc.plugin.slimefun4.storage.common.FieldKey;
import com.xzavier0722.mc.plugin.slimefun4.storage.common.RecordKey;
import com.xzavier0722.mc.plugin.slimefun4.storage.common.RecordSet;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.ADataController;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.ASlimefunDataContainer;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.BlockDataController;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.LocationUtils;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.DataUtils;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.ItemState;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.items.virtual.VirtualItemHandler;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotConfigurable;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.inventory.InvUtils;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.profelements.dynatech.items.electric.transfer.WirelessItemInput;
import me.profelements.dynatech.items.electric.transfer.WirelessItemOutput;
import me.profelements.dynatech.registries.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Exercises supplied production tickers, real menus and dirty-gated core persistence. */
public final class WirelessItemTransferProbe extends JavaPlugin {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String INPUT_ID = Items.Keys.WIRELESS_ITEM_INPUT.asSlimefunId();
    private static final String OUTPUT_ID = Items.Keys.WIRELESS_ITEM_OUTPUT.asSlimefunId();
    private static final String LINK = "wireless-input-location";
    private static final String SENTINEL = "wireless-probe-sentinel";
    private static final int[] SLOTS = java.util.stream.IntStream.rangeClosed(9, 44).toArray();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final List<Map<String, Object>> restartFixtures = new ArrayList<>();
    private final List<NativeMetadataReadback.Expected> restartMetadata = new ArrayList<>();
    private final List<Map<String, Object>> metadataEvidence = new ArrayList<>();
    private BlockDataController blocks;
    private NativeMetadataReadback metadata;
    private World world;
    private int fixtureNumber;
    private int enabledAtTick;
    private int observedGithubWorkers;
    private int observedQuietTicks;
    private final java.util.Set<Integer> observedGithubTaskIds = new java.util.LinkedHashSet<>();
    private boolean backgroundQuiet;
    private boolean running;

    @Override public void onEnable() {
        enabledAtTick = Bukkit.getCurrentTick();
        getLogger().info("Native helper ready; checks run only after its console command.");
    }

    @Override public void onDisable() {
        for (World loadedWorld : Bukkit.getWorlds()) loadedWorld.removePluginChunkTickets(this);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || running || !(args[0].equals("run") || args[0].equals("read"))) {
            sender.sendMessage("Usage: dynatechwirelessprobe run|read (one active phase)");
            return true;
        }
        running = true;
        blocks = Slimefun.getDatabaseManager().getBlockDataController();
        metadata = new NativeMetadataReadback(blocks);
        world = Bukkit.getWorlds().getFirst();
        Slimefun.getTickerTask().pauseItemTicker(INPUT_ID);
        Slimefun.getTickerTask().pauseItemTicker(OUTPUT_ID);
        CompletableFuture<Void> phase = (args[0].equals("run") ? runChecks() : readChecks())
                .thenCompose(ignored -> awaitGithubIdle(System.nanoTime() + 120_000_000_000L, -1));
        phase.whenComplete((ignored, failure) -> owner(() -> {
            finish(args[0], failure);
            return null;
        }));
        return true;
    }

    private CompletableFuture<Void> runChecks() {
        List<Scenario> cases = List.of(
                new Scenario("normal-empty-output", () -> normal(false, false)),
                new Scenario("normal-merge-output", () -> normal(true, false)),
                new Scenario("source-slot-order", this::slotOrder),
                new Scenario("insufficient-input-energy", () -> noTransfer("input-energy")),
                new Scenario("insufficient-output-energy", () -> noTransfer("output-energy")),
                new Scenario("full-output", () -> noTransfer("full")),
                new Scenario("distributed-capacity-waits", () -> noTransfer("distributed")),
                new Scenario("unlinked-output", () -> noTransfer("unlinked")),
                new Scenario("non-input-endpoint", () -> noTransfer("wrong-id")),
                new Scenario("missing-input", () -> noTransfer("missing")),
                new Scenario("legacy-coordinate-link", () -> normal(false, true)),
                new Scenario("async-chunk-reload", this::chunkReload),
                new Scenario("persist-empty-output", () -> persistence(false)),
                new Scenario("persist-merge-output", () -> persistence(true)),
                new Scenario("locked-input", () -> guarded("input-lock")),
                new Scenario("locked-output", () -> guarded("output-lock")),
                new Scenario("absent-input-menu", () -> guarded("input-menu")),
                new Scenario("absent-output-menu", () -> guarded("output-menu")),
                new Scenario("pending-input", () -> guarded("input-pending")),
                new Scenario("pending-output", () -> guarded("output-pending")),
                new Scenario("off-thread-output", this::offThread),
                new Scenario("unavailable-world-link", () -> invalidLink(false)),
                new Scenario("malformed-link", () -> invalidLink(true)),
                new Scenario("insertion-denied", () -> virtualInsertion(false)),
                new Scenario("insertion-remainder", () -> virtualInsertion(true)));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Scenario scenario : cases) {
            chain = chain.thenCompose(ignored -> ownerCompose(scenario.body))
                    .thenCompose(check -> owner(() -> {
                        Map<String, Object> row = check.result(scenario.name);
                        results.add(row);
                        getLogger().info("DYNATECH_NATIVE_CASE " + scenario.name + " " + row.get("passed"));
                        return (Void) null;
                    }));
        }
        return chain.thenCompose(ignored -> ownerCompose(this::prepareRestartControls))
                .thenCompose(ignored -> awaitQueuedDataWrites())
                .thenCompose(ignored -> requireStoredMetadata("before-stop", restartMetadata, 100))
                .thenCompose(ignored -> owner(() -> {
                    writeJson(getDataFolder().toPath().resolve("restart-fixtures.json"), restartFixtures);
                    return (Void) null;
                }));
    }

    private CompletableFuture<Check> normal(boolean merge, boolean decimalLink) {
        Pair p = pair(false);
        ItemStack payload = rich(Material.DIAMOND, merge ? 5 : 17, "normal-" + merge + "-" + decimalLink);
        p.input.menu.replaceExistingItem(9, payload.clone());
        if (merge) fillMergeOutput(p, payload, 59);
        if (decimalLink) p.output.data.setData(LINK, world.getName() + ";" + p.input.location.getX()
                + ";" + p.input.location.getY() + ";" + p.input.location.getZ());
        String originalLink = p.output.data.getData(LINK);
        Check check = new Check();
        check.noException(tick(p.output), "normal production tick returns normally");
        check.item(null, p.input.menu.getItemInSlot(9), "normal transfer clears selected input");
        check.item(amount(payload, merge ? 64 : 17), p.output.menu.getItemInSlot(9), "exact metadata reaches first output slot");
        check.energy(p, 24, 24, "one stack costs eight energy at each endpoint");
        check.that(originalLink.equals(p.output.data.getData(LINK)), "stored link representation is preserved");
        return CompletableFuture.completedFuture(check);
    }

    private CompletableFuture<Check> slotOrder() {
        Pair p = pair(false);
        ItemStack first = rich(Material.COPPER_INGOT, 7, "first-source");
        ItemStack second = rich(Material.IRON_INGOT, 11, "second-source");
        ItemStack waiting = rich(Material.GOLD_INGOT, 13, "waiting-source");
        p.input.menu.replaceExistingItem(9, first.clone());
        p.input.menu.replaceExistingItem(11, second.clone());
        p.input.menu.replaceExistingItem(12, waiting.clone());
        charge(p, 16, 16);
        Check check = new Check();
        check.noException(tick(p.output), "source-order tick returns normally");
        check.item(first, p.output.menu.getItemInSlot(9), "first source enters first output");
        check.item(second, p.output.menu.getItemInSlot(10), "second source enters next output");
        check.item(null, p.input.menu.getItemInSlot(9), "first source cleared");
        check.item(null, p.input.menu.getItemInSlot(11), "second source cleared");
        check.item(waiting, p.input.menu.getItemInSlot(12), "exhausted budget retains next source stack");
        check.energy(p, 0, 0, "two source stacks consume sixteen at each endpoint");
        return CompletableFuture.completedFuture(check);
    }

    private CompletableFuture<Check> noTransfer(String kind) {
        Pair p = pair(false);
        ItemStack payload = rich(Material.EMERALD, 10, "no-transfer-" + kind);
        p.input.menu.replaceExistingItem(9, payload.clone());
        switch (kind) {
            case "input-energy" -> charge(p, 7, 32);
            case "output-energy" -> charge(p, 32, 7);
            case "full" -> fillOutput(p, 9);
            case "distributed" -> {
                fillOutput(p, 11);
                p.output.menu.replaceExistingItem(9, amount(payload, 59));
                p.output.menu.replaceExistingItem(10, amount(payload, 59));
            }
            case "unlinked" -> p.output.data.removeData(LINK);
            case "wrong-id" -> p.output.data.setData(LINK, locationString(p.output.location));
            case "missing" -> p.output.data.setData(LINK, locationString(p.input.location.clone().add(4, 0, 0)));
            default -> throw new IllegalArgumentException(kind);
        }
        State before = state(p);
        Check check = new Check();
        if (kind.equals("distributed")) check.that(!InvUtils.fitAll(p.output.menu.toInventory(),
                new ItemStack[] {payload}, SLOTS), "historical admission does not aggregate two partial spaces");
        check.noException(tick(p.output), "non-transfer control returns normally");
        check.unchanged(before, p, "non-transfer control");
        return CompletableFuture.completedFuture(check);
    }

    private CompletableFuture<Check> persistence(boolean merge) {
        Pair p = pair(false);
        ItemStack payload = rich(Material.AMETHYST_SHARD, merge ? 5 : 17, "persist-" + merge);
        p.input.menu.replaceExistingItem(9, payload.clone());
        if (merge) fillMergeOutput(p, payload, 59);
        return checkpoint(p).thenCompose(ignored -> ownerCompose(() -> {
            Check check = new Check();
            requireClean(p); // Fixture failure, not a tolerated old-code assertion failure.
            check.that(true, "both menus were persisted and clean before the actual production tick");
            Throwable failure = tick(p.output);
            if (failure != null || !empty(p.input.menu.getItemInSlot(9))
                    || !amount(payload, merge ? 64 : 17).equals(p.output.menu.getItemInSlot(9))
                    || p.input.energy.getChargeLong(p.input.location) != 24
                    || p.output.energy.getChargeLong(p.output.location) != 24)
                throw new IllegalStateException("Persistence fixture did not complete the expected in-memory transfer", failure);
            check.that(true, "persisted-fixture transfer returns normally with exact in-memory result");
            check.item(null, p.input.menu.getItemInSlot(9), "source removed in memory");
            check.item(amount(payload, merge ? 64 : 17), p.output.menu.getItemInSlot(9), "destination exact in memory");
            check.energy(p, 24, 24, "persisted transfer has exact eight-plus-eight cost");
            check.that(p.input.menu.isDirty(), "source removal participates in normal persistence");
            check.that(p.output.menu.isDirty(), "destination insertion or merge participates in normal persistence");
            remember(merge ? "persist-merge-output" : "persist-empty-output", p);
            // This is deliberately dirty-gated. Force-saving here would hide the regression.
            blocks.saveAllBlockInventories();
            return waitUntil(() -> !p.input.menu.isDirty() && !p.output.menu.isDirty(), 240)
                    .thenApply(done -> check);
        }));
    }

    private CompletableFuture<Check> guarded(String kind) {
        Pair p = pair(false);
        p.input.menu.replaceExistingItem(9, rich(Material.QUARTZ, 13, "guard-" + kind));
        State before = state(p);
        Machine target = kind.startsWith("input") ? p.input : p.output;
        Check check = new Check();
        try {
            if (kind.endsWith("lock")) { target.menu.lock(); if (!target.menu.locked()) throw new IllegalStateException("Lock fixture failed"); }
            else if (kind.endsWith("menu")) { setMenu(target.data, null); if (target.data.getBlockMenu() != null) throw new IllegalStateException("Detach fixture failed"); }
            else { target.data.setPendingRemove(true); if (!target.data.isPendingRemove()) throw new IllegalStateException("Pending fixture failed"); }
            check.noException(tick(p.output), "invalid endpoint is rejected without exception");
        } finally {
            if (kind.endsWith("lock")) target.menu.unlock();
            else if (kind.endsWith("menu")) setMenu(target.data, target.menu);
            else target.data.setPendingRemove(false);
        }
        check.unchanged(before, p, "invalid endpoint");
        return CompletableFuture.completedFuture(check);
    }

    private CompletableFuture<Check> offThread() {
        Pair p = pair(false);
        p.input.menu.replaceExistingItem(9, rich(Material.LAPIS_LAZULI, 9, "off-thread"));
        State before = state(p);
        AtomicBoolean primary = new AtomicBoolean(true);
        CompletableFuture<Throwable> invocation = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                primary.set(Bukkit.isPrimaryThread());
                invocation.complete(tick(p.output));
            } catch (Throwable failure) {
                invocation.completeExceptionally(failure);
            }
        });
        return invocation.thenCompose(failure -> owner(() -> {
            Check check = new Check();
            if (primary.get()) throw new IllegalStateException("Off-thread fixture did not cross the server thread boundary");
            check.that(true, "production ticker was actually invoked off the server thread");
            check.noException(failure, "off-thread invocation returns safely");
            check.unchanged(before, p, "off-thread invocation");
            return check;
        }));
    }

    private CompletableFuture<Check> invalidLink(boolean malformed) {
        Pair p = pair(false);
        p.input.menu.replaceExistingItem(9, rich(Material.REDSTONE, 6, "invalid-link"));
        List<String> links = malformed ? List.of("broken", world.getName() + ";not-a-number;64;1",
                world.getName() + ";NaN;64;NaN") : List.of("absent-wireless-fixture-world;1;64;1");
        Check check = new Check();
        for (String link : links) {
            p.output.data.setData(LINK, link);
            State before = state(p);
            check.noException(tick(p.output), "unusable link returns safely: " + link);
            check.unchanged(before, p, "unusable link retains contents, charges and original string");
        }
        return CompletableFuture.completedFuture(check);
    }

    private CompletableFuture<Check> virtualInsertion(boolean partial) {
        Pair p = pair(false);
        try (VirtualTransferFixture fixture = new VirtualTransferFixture(this, partial
                ? VirtualTransferFixture.Mode.CAP_INSERT_AT_EIGHT : VirtualTransferFixture.Mode.DENY_INSERT)) {
            ItemStack payload = fixture.payload(6);
            p.input.menu.replaceExistingItem(9, payload.clone());
            fillOutput(p, 10);
            if (partial) p.output.menu.replaceExistingItem(9, fixture.payload(5));
            if (!InvUtils.fitAll(p.output.menu.toInventory(), new ItemStack[] {payload}, SLOTS))
                throw new IllegalStateException("Virtual fixture did not satisfy unchanged whole-stack admission");
            fixture.handler.resetCounters();
            Check check = new Check();
            Throwable failure = tick(p.output);
            if (failure != null) throw new IllegalStateException("Virtual insertion fixture threw before valid evidence", failure);
            if (!(partial ? fixture.handler.insertMaxStackCalls > 0 : fixture.handler.insertAdmissionCalls > 0))
                throw new IllegalStateException("Real core insertion did not invoke the registered test handler");
            check.that(true, "real core insertion invoked the registered handler and returned normally");
            check.item(fixture.payload(partial ? 3 : 6), p.input.menu.getItemInSlot(9), "exact uninserted source is retained");
            check.item(partial ? fixture.payload(8) : null, p.output.menu.getItemInSlot(9), "destination equals actual accepted quantity");
            check.energy(p, partial ? 24 : 32, partial ? 24 : 32, "only a positive insertion consumes eight-plus-eight");
            check.that(count(p.input.menu) + (empty(p.output.menu.getItemInSlot(9)) ? 0 : p.output.menu.getItemInSlot(9).getAmount())
                    == (partial ? 11 : 6), "virtual transfer conserves the exact payload count");
            return CompletableFuture.completedFuture(check);
        }
    }

    private CompletableFuture<Check> chunkReload() {
        Pair original = pair(true);
        ItemStack payload = rich(Material.NETHERITE_SCRAP, 19, "remote-chunk-reload");
        original.input.menu.replaceExistingItem(9, payload.clone());
        String link = original.output.data.getData(LINK);
        return checkpoint(original).thenCompose(ignored -> ownerCompose(() -> {
            int cx = original.input.location.getBlockX() >> 4;
            int cz = original.input.location.getBlockZ() >> 4;
            String key = LocationUtils.getChunkKey(original.input.location);
            if (blocks.getAllLoadedChunkData().stream().noneMatch(c -> c.getKey().equals(key)))
                throw new IllegalStateException("Remote fixture was absent from the loaded core cache");
            world.removePluginChunkTicket(cx, cz, this);
            if (world.getPluginChunkTickets(cx, cz).contains(this))
                throw new IllegalStateException("Remote fixture ticket was not removed");
            world.unloadChunkRequest(cx, cz);
            return waitUntil(() -> !world.isChunkLoaded(cx, cz)
                    && blocks.getAllLoadedChunkData().stream().noneMatch(c -> c.getKey().equals(key)), 240)
                    .exceptionallyCompose(failure -> ownerCompose(() -> CompletableFuture.failedFuture(new IllegalStateException(
                            "Remote unload failed: worldLoaded=" + world.isChunkLoaded(cx, cz) + ", coreCached="
                                    + blocks.getAllLoadedChunkData().stream().anyMatch(c -> c.getKey().equals(key)), failure))))
                    .thenCompose(done -> ownerCompose(() -> {
                        Check check = new Check();
                        check.that(true, "actual world unload and core chunk-cache eviction were observed");
                        if (!world.isChunkLoaded(original.output.location.getBlockX() >> 4, original.output.location.getBlockZ() >> 4)
                                || StorageCacheUtils.getBlock(original.output.location) != original.output.data
                                || !original.output.data.isDataLoaded() || original.output.data.isPendingRemove()
                                || original.output.data.getBlockMenu() != original.output.menu || original.output.menu.locked()
                                || !link.equals(original.output.data.getData(LINK)))
                            throw new IllegalStateException("Output was not live and linked after remote input eviction");
                        AtomicInteger observedLoads = new AtomicInteger();
                        AtomicReference<Throwable> observerFailure = new AtomicReference<>();
                        Listener observer = new Listener() {
                            @EventHandler(priority = EventPriority.MONITOR)
                            public void loaded(ChunkLoadEvent event) {
                                Chunk chunk = event.getChunk();
                                if (chunk.getWorld() != world || chunk.getX() != cx || chunk.getZ() != cz) return;
                                try {
                                    if (!Bukkit.isPrimaryThread() || !chunk.isLoaded())
                                        throw new IllegalStateException("Observed input load was not an already loaded owner-thread chunk");
                                    // Paper removes its temporary asynchronous request ticket
                                    // after completion. Retain only the chunk actually loaded
                                    // by production, allowing core's normal async data read.
                                    chunk.addPluginChunkTicket(WirelessItemTransferProbe.this);
                                    if (!chunk.getPluginChunkTickets().contains(WirelessItemTransferProbe.this))
                                        throw new IllegalStateException("Could not retain observed input chunk");
                                    observedLoads.incrementAndGet();
                                } catch (Throwable failure) { observerFailure.set(failure); }
                            }
                        };
                        Bukkit.getPluginManager().registerEvents(observer, this);
                        Throwable initialFailure = tick(original.output);
                        if (initialFailure != null) {
                            HandlerList.unregisterAll(observer);
                            throw new IllegalStateException("Initial remote request failed", initialFailure);
                        }
                        check.that(true, "live production output requests its unloaded linked chunk safely");
                        // Do not load this chunk or its data from the fixture: the real
                        // production PaperLib request and core chunk listener must do it.
                        return waitUntil(() -> {
                            if (observerFailure.get() != null)
                                throw new IllegalStateException("Observed input-load fixture failed", observerFailure.get());
                            // Ambient tickers are paused. Reproduce their normal retry
                            // cadence while waiting; never substitute a fixture chunk load.
                            if (Bukkit.getCurrentTick() % 10 == 0) {
                                Throwable retryFailure = tick(original.output);
                                if (retryFailure != null) throw new IllegalStateException("Remote production retry failed", retryFailure);
                            }
                            if (observedLoads.get() == 0 || !world.isChunkLoaded(cx, cz)) return false;
                            SlimefunBlockData live = StorageCacheUtils.getBlock(original.input.location);
                            return live != null && live.isDataLoaded() && live.getBlockMenu() != null;
                        }, 240).exceptionallyCompose(failure -> ownerCompose(() -> {
                            SlimefunBlockData live = StorageCacheUtils.getBlock(original.input.location);
                            return CompletableFuture.failedFuture(new IllegalStateException("Remote normal reload failed: worldLoaded="
                                    + world.isChunkLoaded(cx, cz) + ", dataPresent=" + (live != null) + ", dataLoaded="
                                    + (live != null && live.isDataLoaded()) + ", menuPresent="
                                    + (live != null && live.getBlockMenu() != null), failure));
                        })).thenCompose(loaded -> owner(() -> {
                            SlimefunBlockData data = StorageCacheUtils.getBlock(original.input.location);
                            Machine input = new Machine(original.input.item, original.input.energy,
                                    original.input.location.getBlock(), original.input.location, data, data.getBlockMenu());
                            Pair current = new Pair(input, original.output);
                            check.that(observedLoads.get() > 0, "production request caused a real input chunk-load event");
                            check.that(input.block.getChunk().getPluginChunkTickets().contains(this),
                                    "fixture retained only the already loaded chunk while core initialized its menu");
                            check.that(data != original.input.data && input.menu != original.input.menu,
                                    "normal core reload supplied distinct authoritative data and menu");
                            holdChunk(input.location);
                            check.noException(tick(current.output), "next production tick resumes after normal reload");
                            check.item(null, current.input.menu.getItemInSlot(9), "reloaded source transfers exactly once");
                            check.item(payload, current.output.menu.getItemInSlot(9), "remote output retains exact metadata and amount");
                            check.energy(current, 24, 24, "chunk reload retains the normal eight-plus-eight energy cost");
                            check.that(link.equals(current.output.data.getData(LINK)), "chunk reload preserves stored legacy link");
                            return check;
                        })).whenComplete((completedCheck, failure) -> owner(() -> { HandlerList.unregisterAll(observer); return null; }));
                    }));
        }));
    }

    private Pair pair(boolean remote) {
        int x = 1536 + ++fixtureNumber * 256;
        Machine input = machine(new Location(world, x, 64, 1536), true);
        Machine output = machine(new Location(world, x + (remote ? 128 : 2), 64, 1536), false);
        Pair pair = new Pair(input, output);
        output.data.setData(LINK, locationString(input.location));
        charge(pair, 32, 32);
        return pair;
    }

    private Machine machine(Location location, boolean input) {
        Chunk chunk = holdChunk(location);
        blocks.getChunkData(chunk);
        SlimefunItem item = SlimefunItem.getById(input ? INPUT_ID : OUTPUT_ID);
        if (input && !(item instanceof WirelessItemInput) || !input && !(item instanceof WirelessItemOutput))
            throw new IllegalStateException("Actual production wireless registration unavailable");
        Block block = location.getBlock();
        block.setType(item.getItem().getType(), false);
        SlimefunBlockData data = blocks.createBlock(location, item.getId());
        BlockMenu menu = data.getBlockMenu();
        if (menu == null || !data.isDataLoaded() || StorageCacheUtils.getBlock(location) != data)
            throw new IllegalStateException("Fixture is not a loaded authoritative menu");
        data.setData(SENTINEL, "retained-" + fixtureNumber + "-" + item.getId());
        return new Machine(item, (EnergyNetComponent) item, block, location, data, menu);
    }

    private Chunk holdChunk(Location location) {
        Chunk chunk = world.getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        chunk.addPluginChunkTicket(this);
        if (!chunk.getPluginChunkTickets().contains(this))
            throw new IllegalStateException("Could not hold native fixture chunk");
        return chunk;
    }

    private void charge(Pair p, long input, long output) {
        p.input.energy.setCharge(p.input.location, input);
        p.output.energy.setCharge(p.output.location, output);
        if (p.input.energy.getChargeLong(p.input.location) != input || p.output.energy.getChargeLong(p.output.location) != output)
            throw new IllegalStateException("Fixture charge did not initialize exactly");
    }

    private static Throwable tick(Machine machine) {
        // Match core TickerTask's storage-neutral dispatch. Calling the narrower
        // legacy overload would bypass DynaTech's registered modern override.
        try { machine.item.getBlockTicker().tick(machine.block, machine.item, (ASlimefunDataContainer) machine.data); return null; }
        catch (Exception failure) { return failure; }
    }

    private static void fillOutput(Pair p, int start) {
        for (int slot = start; slot <= 44; slot++) p.output.menu.replaceExistingItem(slot, new ItemStack(Material.STONE, 64));
    }

    private static void fillMergeOutput(Pair p, ItemStack payload, int quantity) {
        fillOutput(p, 10);
        p.output.menu.replaceExistingItem(9, amount(payload, quantity));
    }

    private CompletableFuture<Void> checkpoint(Pair p) {
        List<NativeMetadataReadback.Expected> expected = List.of(
                NativeMetadataReadback.capture(p.input.data, p.input.energy.getChargeLong(p.input.location), SENTINEL),
                NativeMetadataReadback.capture(p.output.data, p.output.energy.getChargeLong(p.output.location), SENTINEL));
        return CompletableFuture.allOf(blocks.saveBlockInventoryAsync(p.input.data), blocks.saveBlockInventoryAsync(p.output.data))
                .thenCompose(ignored -> waitUntil(() -> !p.input.menu.isDirty() && !p.output.menu.isDirty(), 240))
                .thenCompose(ignored -> awaitQueuedDataWrites())
                .thenCompose(ignored -> requireStoredMetadata("initial-seed", expected, 100));
    }

    private CompletableFuture<Void> awaitQueuedDataWrites() {
        // The fixture represents existing persisted machines. Let the normal
        // delayed KV writer and database queue finish; do not force-save menus.
        return waitUntil(() -> blocks.getPendingDelayedWriteTaskCount() == 0
                && blocks.getPendingWriteTaskCount() == 0, 500);
    }

    private CompletableFuture<List<NativeMetadataReadback.Observation>> readMetadata(List<NativeMetadataReadback.Expected> expected) {
        CompletableFuture<List<NativeMetadataReadback.Observation>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { future.complete(expected.stream().map(metadata::read).toList()); }
            catch (Throwable failure) { future.completeExceptionally(failure); }
        });
        return future;
    }

    private CompletableFuture<Void> requireStoredMetadata(String stage, List<NativeMetadataReadback.Expected> expected, int attempts) {
        return readMetadata(expected).thenCompose(observed -> ownerCompose(() -> {
            boolean matches = observed.stream().allMatch(NativeMetadataReadback.Observation::passed);
            if (matches || attempts <= 0) {
                metadataEvidence.add(Map.of("stage", stage, "observations", observed.stream().map(NativeMetadataReadback.Observation::evidence).toList()));
                if (!matches) return CompletableFuture.failedFuture(new IllegalStateException("Actual database metadata readback failed: " + stage));
                return CompletableFuture.completedFuture(null);
            }
            return delay(2).thenCompose(ignored -> requireStoredMetadata(stage, expected, attempts - 1));
        }));
    }

    private CompletableFuture<Void> awaitGithubIdle(long deadline, int quietSinceTick) {
        return ownerCompose(() -> {
            if (System.nanoTime() > deadline)
                return CompletableFuture.failedFuture(new IllegalStateException("Core GitHub workers did not become idle before ordinary shutdown"));
            var core = Bukkit.getPluginManager().getPlugin("Slimefun");
            var active = Bukkit.getScheduler().getActiveWorkers().stream()
                    .filter(worker -> worker.getOwner() == core)
                    .filter(worker -> Arrays.stream(worker.getThread().getStackTrace()).anyMatch(frame ->
                            frame.getClassName().startsWith("io.github.thebusybiscuit.slimefun4.core.services.github.")))
                    .toList();
            active.forEach(worker -> observedGithubTaskIds.add(worker.getTaskId()));
            observedGithubWorkers = Math.max(observedGithubWorkers, active.size());
            // Core starts its contributor refresh at tick600. Observe beyond
            // that eligibility before accepting an idle window; cancel nothing.
            int currentTick = Bukkit.getCurrentTick();
            int quietStart = active.isEmpty() && currentTick - enabledAtTick >= 620
                    ? (quietSinceTick < 0 ? currentTick : quietSinceTick) : -1;
            observedQuietTicks = quietStart < 0 ? 0 : currentTick - quietStart;
            if (observedQuietTicks >= 40) {
                backgroundQuiet = true;
                return CompletableFuture.completedFuture(null);
            }
            return delay(5).thenCompose(ignored -> awaitGithubIdle(deadline, quietStart));
        });
    }

    private static void requireClean(Pair p) {
        if (p.input.menu.isDirty() || p.output.menu.isDirty())
            throw new IllegalStateException("Pre-transfer menus were not clean after acknowledged fixture save");
    }

    private static void setMenu(SlimefunBlockData data, BlockMenu menu) {
        try {
            Method method = SlimefunBlockData.class.getDeclaredMethod("setBlockMenu", BlockMenu.class);
            method.setAccessible(true);
            method.invoke(data, menu);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot install the explicit absent-menu fixture", failure);
        }
    }

    private CompletableFuture<Void> prepareRestartControls() {
        Pair untouched = pair(false);
        untouched.input.menu.replaceExistingItem(9, rich(Material.PRISMARINE_CRYSTALS, 23, "restart-no-transfer"));
        charge(untouched, 0, 0);
        Throwable failure = tick(untouched.output);
        if (failure != null) return CompletableFuture.failedFuture(failure);
        return checkpoint(untouched).thenCompose(ignored -> ownerCompose(() -> {
            requireClean(untouched);
            remember("no-transfer-control", untouched);
            Pair identity = pair(false);
            ItemStack item = ItemStack.deserializeBytes(SlimefunItem.getById(OUTPUT_ID).getItem().serializeAsBytes());
            var meta = item.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.text("Preserve existing DynaTech item identity and link data"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(new NamespacedKey("dynatech", LINK), PersistentDataType.STRING,
                    identity.output.data.getData(LINK));
            meta.getPersistentDataContainer().set(new NamespacedKey(this, "wide"), PersistentDataType.LONG, 9_007_199_254_741_111L);
            item.setItemMeta(meta);
            identity.input.menu.replaceExistingItem(9, item);
            identity.output.menu.replaceExistingItem(12, rich(Material.ENDER_PEARL, 7, "existing-destination"));
            charge(identity, 0, 0);
            return checkpoint(identity).thenCompose(done -> owner(() -> {
                requireClean(identity);
                remember("item-identity-control", identity);
                return (Void) null;
            }));
        }));
    }

    private void remember(String name, Pair p) {
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("name", name);
        fixture.put("input", describe(p.input));
        fixture.put("output", describe(p.output));
        restartFixtures.add(fixture);
        restartMetadata.add(NativeMetadataReadback.capture(p.input.data, p.input.energy.getChargeLong(p.input.location), SENTINEL));
        restartMetadata.add(NativeMetadataReadback.capture(p.output.data, p.output.energy.getChargeLong(p.output.location), SENTINEL));
    }

    private static Map<String, Object> describe(Machine m) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", m.item.getId());
        value.put("world", m.location.getWorld().getName());
        value.put("x", m.location.getBlockX()); value.put("y", m.location.getBlockY()); value.put("z", m.location.getBlockZ());
        value.put("contents", encodeItems(contents(m.menu)));
        value.put("charge", m.energy.getChargeLong(m.location));
        value.put("link", m.data.getData(LINK));
        value.put("sentinel", m.data.getData(SENTINEL));
        value.put("metadata", new LinkedHashMap<>(m.data.getAllData()));
        return value;
    }

    private CompletableFuture<Void> readChecks() {
        Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
        final List<Map<String, Object>> fixtures;
        try { fixtures = JSON.fromJson(Files.readString(getDataFolder().toPath().resolve("restart-fixtures.json")), type); }
        catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
        if (fixtures == null || fixtures.size() != 4)
            return CompletableFuture.failedFuture(new IllegalStateException("Expected exactly four saved restart fixtures"));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Map<String, Object> fixture : fixtures) {
            chain = chain.thenCompose(ignored -> ownerCompose(() -> {
                Check check = new Check();
                @SuppressWarnings("unchecked") Map<String, Object> input = (Map<String, Object>) fixture.get("input");
                @SuppressWarnings("unchecked") Map<String, Object> output = (Map<String, Object>) fixture.get("output");
                return readMachine(input, check).thenCompose(done -> readMachine(output, check))
                        .thenCompose(done -> owner(() -> {
                            results.add(check.result("restart-" + fixture.get("name")));
                            return (Void) null;
                        }));
            }));
        }
        return chain;
    }

    private CompletableFuture<Void> readMachine(Map<String, Object> fixture, Check check) {
        return ownerCompose(() -> {
            if (!world.getName().equals(fixture.get("world"))) throw new IllegalStateException("Restart world differs");
            Location location = new Location(world, ((Number) fixture.get("x")).intValue(),
                    ((Number) fixture.get("y")).intValue(), ((Number) fixture.get("z")).intValue());
            holdChunk(location);
            return waitUntil(() -> {
                SlimefunBlockData data = blocks.getBlockData(location);
                if (data == null) return false;
                if (!data.isDataLoaded()) blocks.loadBlockData(data);
                return data.getBlockMenu() != null;
            }, 240).thenCompose(done -> ownerCompose(() -> {
                @SuppressWarnings("unchecked") Map<String, String> values = (Map<String, String>) fixture.get("metadata");
                NativeMetadataReadback.Expected expected = new NativeMetadataReadback.Expected(
                        LocationUtils.getLocKey(location), (String) fixture.get("id"), values);
                return requireStoredMetadata("restart", List.of(expected), 0).thenCompose(verified -> owner(() -> {
                SlimefunBlockData data = blocks.getBlockData(location);
                check.that(true, "actual stored metadata rows, including explicit zero charge, survive restart exactly");
                check.items(decodeItems(fixture.get("contents")), contents(data.getBlockMenu()), "exact item slots and metadata survive separate process");
                check.that(fixture.get("id").equals(data.getSfId()), "saved machine identity survives restart");
                EnergyNetComponent energy = (EnergyNetComponent) SlimefunItem.getById(data.getSfId());
                check.that(energy.getChargeLong(location) == ((Number) fixture.get("charge")).longValue(), "saved endpoint charge survives restart");
                check.that(java.util.Objects.equals(fixture.get("link"), data.getData(LINK)), "stored link representation survives restart");
                check.that(java.util.Objects.equals(fixture.get("sentinel"), data.getData(SENTINEL)), "unrelated existing block data survives restart");
                return (Void) null;
                }));
            }));
        });
    }

    private void finish(String phase, Throwable failure) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("phase", phase); report.put("at", Instant.now().toString());
        report.put("server", Bukkit.getVersion());
        report.put("core", Bukkit.getPluginManager().getPlugin("Slimefun").getPluginMeta().getVersion());
        report.put("addon", Bukkit.getPluginManager().getPlugin("DynaTech").getPluginMeta().getVersion());
        report.put("pending_delayed_data_writes", blocks.getPendingDelayedWriteTaskCount());
        report.put("pending_database_writes", blocks.getPendingWriteTaskCount());
        report.put("metadata_readbacks", metadataEvidence);
        report.put("github_background_quiescence", Map.of("observed", backgroundQuiet,
                "maximum_matching_workers", observedGithubWorkers, "initial_eligibility_ticks", 620,
                "quiet_window_ticks", 40, "observed_quiet_ticks", observedQuietTicks,
                "enabled_at_tick", enabledAtTick, "finished_at_tick", Bukkit.getCurrentTick(),
                "observed_task_ids", List.copyOf(observedGithubTaskIds)));
        report.put("registered_synchronized_tickers", Map.of(
                INPUT_ID, SlimefunItem.getById(INPUT_ID).getBlockTicker().isSynchronized(),
                OUTPUT_ID, SlimefunItem.getById(OUTPUT_ID).getBlockTicker().isSynchronized()));
        report.put("cases", results);
        report.put("fatal", failure == null ? null : unwrap(failure).toString());
        report.put("passed", failure == null && !results.isEmpty() && results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("passed"))));
        writeJson(getDataFolder().toPath().resolve(phase + "-result.json"), report);
        getLogger().info("DYNATECH_NATIVE_PHASE " + phase + " " + report.get("passed"));
        if (failure != null) unwrap(failure).printStackTrace();
        running = false;
    }

    private CompletableFuture<Void> delay(long ticks) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(this, () -> future.complete(null), ticks);
        return future;
    }

    private CompletableFuture<Void> waitUntil(BooleanSupplier condition, int ticks) {
        return owner(condition::getAsBoolean).thenCompose(done -> {
            if (done) return CompletableFuture.completedFuture(null);
            if (ticks <= 0) return CompletableFuture.failedFuture(new IllegalStateException("Native fixture condition timed out"));
            return delay(1).thenCompose(ignored -> waitUntil(condition, ticks - 1));
        });
    }

    private <T> CompletableFuture<T> owner(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable run = () -> { try { result.complete(action.get()); } catch (Throwable failure) { result.completeExceptionally(failure); } };
        if (Bukkit.isPrimaryThread()) run.run(); else Bukkit.getScheduler().runTask(this, run);
        return result;
    }

    private <T> CompletableFuture<T> ownerCompose(Supplier<CompletableFuture<T>> action) {
        return owner(action).thenCompose(value -> value);
    }

    private ItemStack rich(Material material, int amount, String marker) {
        ItemStack item = new ItemStack(material, amount);
        var meta = item.getItemMeta();
        meta.displayName(Component.text("Native fixture " + marker));
        meta.lore(List.of(Component.text("Preserve this exact wireless payload")));
        meta.getPersistentDataContainer().set(new NamespacedKey(this, "marker"), PersistentDataType.STRING, marker);
        meta.getPersistentDataContainer().set(new NamespacedKey(this, "wide"), PersistentDataType.LONG, 9_007_199_254_741_111L);
        item.setItemMeta(meta);
        return item;
    }

    private static State state(Pair p) {
        return new State(contents(p.input.menu), contents(p.output.menu), p.input.energy.getChargeLong(p.input.location),
                p.output.energy.getChargeLong(p.output.location), p.output.data.getData(LINK));
    }
    private static ItemStack[] contents(BlockMenu menu) { return Arrays.stream(SLOTS).mapToObj(slot -> { ItemStack i = menu.getItemInSlot(slot); return empty(i) ? null : i.clone(); }).toArray(ItemStack[]::new); }
    private static int count(BlockMenu menu) { return Arrays.stream(contents(menu)).filter(i -> !empty(i)).mapToInt(ItemStack::getAmount).sum(); }
    private static boolean empty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() == 0; }
    private static ItemStack amount(ItemStack item, int quantity) { ItemStack result = item.clone(); result.setAmount(quantity); return result; }
    private static String locationString(Location location) { return location.getWorld().getName() + ";" + location.getBlockX() + ";" + location.getBlockY() + ";" + location.getBlockZ(); }
    private static List<String> encodeItems(ItemStack[] items) { return Arrays.stream(items).map(i -> empty(i) ? "" : Base64.getEncoder().encodeToString(i.serializeAsBytes())).toList(); }
    private static ItemStack[] decodeItems(Object value) { return ((List<?>) value).stream().map(v -> ((String) v).isEmpty() ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode((String) v))).toArray(ItemStack[]::new); }
    private static Throwable unwrap(Throwable failure) { while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause(); return failure; }
    private static void writeJson(Path target, Object value) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, JSON.toJson(value) + "\n");
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) { throw new IllegalStateException("Cannot write native evidence", failure); }
    }

    private record Machine(SlimefunItem item, EnergyNetComponent energy, Block block, Location location, SlimefunBlockData data, BlockMenu menu) {}
    private record Pair(Machine input, Machine output) {}
    private record Scenario(String name, Supplier<CompletableFuture<Check>> body) {}
    private record State(ItemStack[] input, ItemStack[] output, long inputCharge, long outputCharge, String link) {}
    private static final class Check {
        final List<Map<String, Object>> assertions = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        void that(boolean pass, String detail) { assertions.add(Map.of("passed", pass, "detail", detail)); }
        void item(ItemStack expected, ItemStack actual, String detail) { that(empty(expected) && empty(actual) || expected != null && expected.equals(actual), detail); }
        void items(ItemStack[] expected, ItemStack[] actual, String detail) { that(Arrays.equals(expected, actual), detail); }
        void noException(Throwable failure, String detail) { that(failure == null, detail); if (failure != null) notes.add(failure.toString()); }
        void energy(Pair p, long input, long output, String detail) { that(p.input.energy.getChargeLong(p.input.location) == input && p.output.energy.getChargeLong(p.output.location) == output, detail); }
        void unchanged(State before, Pair p, String detail) {
            items(before.input, contents(p.input.menu), detail + ": input exact");
            items(before.output, contents(p.output.menu), detail + ": output exact");
            energy(p, before.inputCharge, before.outputCharge, detail + ": charge exact");
            that(java.util.Objects.equals(before.link, p.output.data.getData(LINK)), detail + ": link exact");
        }
        Map<String, Object> result(String name) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name); row.put("passed", !assertions.isEmpty() && assertions.stream().allMatch(a -> Boolean.TRUE.equals(a.get("passed"))));
            row.put("assertions", assertions); row.put("notes", notes); return row;
        }
    }
}

final class VirtualTransferFixture implements AutoCloseable {
    enum Mode { DENY_INSERT, CAP_INSERT_AT_EIGHT }

    private final String id;
    private final ItemGroup group;
    private final ProbeItem item;
    final ProbeHandler handler;
    private boolean closed;

    VirtualTransferFixture(JavaPlugin owner, Mode mode) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Fixture must be installed on owner thread");
        String token = UUID.randomUUID().toString().replace("-", "");
        id = "NATIVE_WIRELESS_VIRTUAL_" + token.toUpperCase(java.util.Locale.ROOT);
        if (SlimefunItem.getById(id) != null) throw new IllegalStateException("Fixture id collision");

        NamespacedKey marker = new NamespacedKey(owner, "wireless-virtual-fixture");
        group = new ItemGroup(new NamespacedKey(owner, "wireless_fixture_" + token), new ItemStack(Material.DIAMOND));
        SlimefunItemStack template = new SlimefunItemStack(id, Material.DIAMOND, meta -> {
            meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, token);
        });
        handler = new ProbeHandler(marker, token, mode);
        item = new ProbeItem(group, template);
        item.setHidden(true);
        item.addItemHandler(handler); // Public registration: MUST precede item.register(...).

        SlimefunAddon addon = new SlimefunAddon() {
            @Override public JavaPlugin getJavaPlugin() { return owner; }
            @Override public String getBugTrackerURL() { return null; }
        };
        try {
            item.register(addon);
            // register catches registration exceptions internally; verify its actual outcome.
            if (SlimefunItem.getById(id) != item || item.getState() != ItemState.ENABLED
                    || !item.getHandlers().contains(handler)
                    || !Slimefun.getItemStackService().isVirtualItem(payload(1))) {
                throw new IllegalStateException("Virtual fixture did not register and resolve");
            }
            handler.resetCounters();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    ItemStack payload(int amount) {
        ItemStack result = item.getItem().clone();
        result.setAmount(amount);
        return result;
    }

    @Override
    public void close() {
        if (closed) return;
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Fixture must be removed on owner thread");
        closed = true;
        // There is no public SlimefunItem.unregister(). These exact public collection removals
        // undo this unique, non-ticking, non-global, recipe-free test item's registration.
        // Do not replace a production item's handler or restore an entire registry snapshot.
        item.getHandlers().remove(handler);
        try {
            item.disable();
        } finally {
            Slimefun.getRegistry().getSlimefunItemIds().remove(id, item);
            Slimefun.getRegistry().getAllSlimefunItems().remove(item);
            Slimefun.getRegistry().getEnabledSlimefunItems().remove(item);
            group.remove(item);
            Slimefun.getRegistry().getAllItemGroups().remove(group);
        }
        if (SlimefunItem.getById(id) != null || item.getHandlers().contains(handler)) {
            throw new IllegalStateException("Virtual fixture cleanup failed");
        }
    }

    private static final class ProbeItem extends SlimefunItem implements NotConfigurable {
        ProbeItem(ItemGroup group, SlimefunItemStack stack) {
            super(group, stack, RecipeType.NULL, new ItemStack[9]);
        }

        @Override public void load() {
            // No guide/recipe registration; this item exists only for real ItemStackService lookup.
        }
    }

    static final class ProbeHandler implements VirtualItemHandler {
        private final NamespacedKey marker;
        private final String token;
        private final Mode mode;
        int recognitions;
        int insertAdmissionCalls;
        int insertMaxStackCalls;

        ProbeHandler(NamespacedKey marker, String token, Mode mode) {
            this.marker = marker;
            this.token = token;
            this.mode = mode;
        }

        private boolean owns(ItemStack stack) {
            return stack != null && stack.getType() == Material.DIAMOND && stack.hasItemMeta()
                    && token.equals(stack.getItemMeta().getPersistentDataContainer()
                            .get(marker, PersistentDataType.STRING));
        }

        @Override public boolean isVirtualItem(ItemStack stack) {
            boolean result = owns(stack);
            if (result) recognitions++;
            return result;
        }

        @Override public AdmissionResult allows(ItemStack stack, InventoryContext context) {
            if (!owns(stack) || context != InventoryContext.MENU_INSERT) return AdmissionResult.NOT_HANDLED;
            insertAdmissionCalls++;
            return mode == Mode.DENY_INSERT ? AdmissionResult.DENY : AdmissionResult.ALLOW;
        }

        @Override public int getMaxStackSize(ItemStack stack, InventoryContext context, int defaultMaxStackSize) {
            if (!owns(stack) || context != InventoryContext.MENU_INSERT) return defaultMaxStackSize;
            insertMaxStackCalls++;
            return mode == Mode.CAP_INSERT_AT_EIGHT ? 8 : defaultMaxStackSize;
        }

        void resetCounters() {
            recognitions = 0;
            insertAdmissionCalls = 0;
            insertMaxStackCalls = 0;
        }
    }
}

final class NativeMetadataReadback {
    // This expectation owns a copy. Do not retain the live getAllData() map.
    record Expected(String locationKey, String slimefunId, Map<String, String> values) {
        Expected {
            Objects.requireNonNull(locationKey);
            Objects.requireNonNull(slimefunId);
            values = Map.copyOf(values);
        }
    }

    record Observation(Expected expected, int recordCount, String actualId,
                       Map<String, String> actualValues, List<String> mismatches) {
        Observation {
            actualValues = Map.copyOf(actualValues);
            mismatches = List.copyOf(mismatches);
        }

        boolean passed() { return mismatches.isEmpty(); }

        Map<String, Object> evidence() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("location", expected.locationKey());
            row.put("expected_id", expected.slimefunId());
            row.put("actual_record_count", recordCount);
            row.put("actual_id", actualId);
            row.put("expected_values", expected.values());
            row.put("actual_values", actualValues);
            row.put("mismatches", mismatches);
            row.put("passed", passed());
            return row;
        }
    }

    private final BlockDataController controller;
    private final Method getData;

    NativeMetadataReadback(BlockDataController controller) {
        this.controller = Objects.requireNonNull(controller);
        try {
            getData = ADataController.class.getDeclaredMethod("getData", RecordKey.class);
            getData.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native database readback API is unavailable", failure);
        }
    }

    // Called only on the owner thread, after the fixture's ordinary metadata setup.
    // An explicit zero-charge KV row must exist in the expectation: default zero
    // returned by getChargeLong is insufficient to prove that any row was stored.
    static Expected capture(SlimefunBlockData data, long charge, String sentinelKey) {
        Map<String, String> values = new LinkedHashMap<>(data.getAllData());
        if (!Long.toString(charge).equals(values.get("energy-charge"))
                || !values.containsKey(sentinelKey)) {
            throw new IllegalStateException("Native metadata expectation was not initialized: " + data.getKey());
        }
        return new Expected(data.getKey(), data.getSfId(), values);
    }

    // Goes directly to the adapter SELECT path. It does not consult or initialize
    // block/menu caches, submit writes, force inventories, or change controller state.
    // These separate SELECTs are a point-in-time readback, not a transaction barrier.
    Observation read(Expected expected) {
        RecordKey recordKey = new RecordKey(DataScope.BLOCK_RECORD);
        recordKey.addCondition(FieldKey.LOCATION, expected.locationKey());
        recordKey.addField(FieldKey.SLIMEFUN_ID);
        List<RecordSet> records = query(recordKey);
        String actualId = records.size() == 1 ? records.get(0).getString(FieldKey.SLIMEFUN_ID) : null;

        RecordKey dataKey = new RecordKey(DataScope.BLOCK_DATA);
        dataKey.addCondition(FieldKey.LOCATION, expected.locationKey());
        dataKey.addField(FieldKey.DATA_KEY);
        dataKey.addField(FieldKey.DATA_VALUE);
        Map<String, String> actual = new LinkedHashMap<>();
        for (RecordSet row : query(dataKey)) {
            String key = Objects.requireNonNull(row.getString(FieldKey.DATA_KEY), "Missing stored metadata key");
            String encoded = Objects.requireNonNull(row.getString(FieldKey.DATA_VALUE), "Missing stored metadata value");
            if (actual.put(key, DataUtils.blockDataDebase64(encoded)) != null) {
                throw new IllegalStateException("Duplicate stored metadata key: " + expected.locationKey() + "/" + key);
            }
        }

        List<String> mismatches = new ArrayList<>();
        if (records.size() != 1 || !expected.slimefunId().equals(actualId)) {
            mismatches.add("Stored machine identity does not match the seeded fixture");
        }
        for (var entry : expected.values().entrySet()) {
            if (!actual.containsKey(entry.getKey())) mismatches.add("Missing stored key: " + entry.getKey());
            else if (!entry.getValue().equals(actual.get(entry.getKey()))) mismatches.add("Stored value differs: " + entry.getKey());
        }
        for (String key : actual.keySet()) {
            if (!expected.values().containsKey(key)) mismatches.add("Unexpected stored key: " + key);
        }
        return new Observation(expected, records.size(), actualId, actual, mismatches);
    }

    @SuppressWarnings("unchecked")
    private List<RecordSet> query(RecordKey key) {
        try {
            return (List<RecordSet>) getData.invoke(controller, key);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error error) throw error;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Native database SELECT failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot invoke native database SELECT", failure);
        }
    }
}
