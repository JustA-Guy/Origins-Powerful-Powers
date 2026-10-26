# Example: reading a player's powers with `power_list`

A small datapack showing [`origins-powerful-powers:power_list`](../../docs/11-lists.md#power-list): a read-only list of the power ids a player has **at the moment it's read**.

## Install

Copy `opp_power_census/` into `<world>/datapacks/`, `/reload`, then `/function opp_power_census:grant`.

## What's in it

| File | Does |
|---|---|
| `power_list.json` | Declares the list: `"variable": "powers"`. Hidden powers (like `origins:multiple` sub-powers) are left out by default. |
| `stats.json` | Three variables: `total`, `fromOrigins`, `canFly`. |
| `census.json` | Every 5 seconds: `total = powers.size();` and `canFly = powers.contains("origins:elytra");`, then a `for_each_read` over `powers` whose nested `modify_variable` adds 1 to `fromOrigins` for each id starting with `origins:`. |
| `hoarder.json` | Glowing for anyone with 10 or more powers and no `origins:invisibility`. It reads `powers` directly in its condition. |

The list is sorted alphabetically, so `powers.get(0)` is stable as long as the player's powers don't change.

## Status

*(2026-09-11)* All 6 OPP blocks parse with the mod's parser. `census.json` was run against a fake 14-power list and the counts came out right.
