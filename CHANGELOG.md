# 1.1.04

## Fixes

- Run Wireless Item Input and Wireless Item Output inventory handling on the server thread and revalidate both authoritative machine menus before transfers.
- Retain unaccepted source items, mark both changed inventories for saving, and charge energy only when items move.
- Reject locked, stale, removed, or incompletely loaded endpoints and invalid linked locations safely.
- Preserve item IDs, linked-location format, metadata, slot order, whole-stack admission, and the normal eight-energy cost at each endpoint.

## Verification

- Retain the Doctor migration safety contract and Java 21 / Paper 1.21.11 release floor.
- Compile against the existing exact Paper 1.21.11, 26.2, and 26.3 API targets, then run the same release JAR through native wireless transfer and separate-process restart checks on three pinned Paper runtimes.

# 29/4/24

## Additions:
- Add Dragon Egg Mill
- Add Dragon Egg Turbine
- Allow RecipeRegistry to grab recipe via key

## Fixes:
- Regular block now drops if broken before durability = 0 for degrading generators.
