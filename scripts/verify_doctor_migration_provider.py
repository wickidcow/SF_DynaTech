#!/usr/bin/env python3
"""Static safety contract for the DynaTech Slimefun Legacy migration provider."""

from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
errors: list[str] = []


def read(path: str) -> str:
    file = root / path
    if not file.is_file():
        errors.append(f"missing required file: {path}")
        return ""
    return file.read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def reject(condition: bool, message: str) -> None:
    if condition:
        errors.append(message)


mappings = read("src/main/java/me/profelements/dynatech/integrations/SlimefunLegacyIdMappings.java")
service = read("src/main/java/me/profelements/dynatech/integrations/DynaTechLegacyMigrationService.java")
provider = read("src/main/java/me/profelements/dynatech/integrations/DynaTechLegacyMigrationProviderBridge.java")
plugin = read("src/main/java/me/profelements/dynatech/DynaTech.java")

require("registerLegacySlimefunItemId" in mappings, "legacy mappings must still publish to Slimefun Doctor")
require('map(mappings, "AUTO_KITCHEN", Items.Keys.KITCHEN_AUTO_CRAFTER.asSlimefunId())' in mappings,
        "old Auto Kitchen must point at the registered Kitchen Auto Crafter successor")
reject('map(mappings, "DRAGON_GENERATOR"' in mappings,
       "Dragon Generator must remain unmapped until a proven successor exists")

require('Set.of("AUTO_KITCHEN")' in service,
        "Auto Kitchen must remain schema-deferred rather than generically rewritten")
require('call(data, "getMenuContents")' in service,
        "block migration must snapshot raw persisted menus before relying on BlockMenu hydration")
require("restoreMenuLosslessly" in service and "Target menu is too small" in service,
        "block migration must reject lossy menu restoration")
require("remove.invoke(controller, location)" in service and "create.invoke(controller, location, targetId)" in service,
        "placed-block identity changes must go through the storage controller")
reject("setSfId" in service,
       "placed-block migration must not use an unproven direct sf-id mutation shortcut")
require('Slimefun.class.getMethod("getItemDataService")' in service,
        "ItemStack migration must use Slimefun's canonical item-data service")
require('getMethod("setItemData", ItemStack.class, String.class)' in service,
        "ItemStack migration must use Slimefun's canonical item-id setter")
reject("getChunkAt(" in service,
       "migration provider must not force-load chunks")
reject("loadChunk(" in service,
       "migration provider must not force-load chunks")
require("getLoadedChunks()" in service,
        "migration provider must remain scoped to already-loaded chunks")
require("rollback" in service.lower(),
        "block migration must retain an explicit rollback path")

require("Proxy.newProxyInstance" in provider,
        "provider bridge must remain reflective/optional for non-Legacy Slimefun runtimes")
require("SlimefunLegacyIdMappings.mappings()" in provider,
        "provider mappings must use the exact same authority as registry publication")
require("scanLoaded(repair)" in provider,
        "provider must delegate scan/repair to DynaTech's addon-owned migration service")
require("Schema-deferred entries" in provider,
        "provider report must disclose schema-deferred entries")
require("fingerprinted execution plan" in provider,
        "dry-run output must preserve Slimefun Doctor's fingerprinted authorization boundary")
require("DynaTechLegacyMigrationProviderBridge.register(this);" in plugin,
        "DynaTech must register the migration provider after publishing mappings")

if errors:
    print("DynaTech Doctor migration verification failed:")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("DynaTech Doctor migration verification passed.")
