# Example: savings account with compound interest

A datapack built on OPP's [variables](../../docs/08-variables.md). Every player with the account has their own balance. The balance grows exponentially: each minute it's multiplied by `1 + rate`. Whole coins are **rounded down** into a `long`, and the leftover decimal is **kept in a separate `double`** so it keeps compounding instead of being thrown away.

## Install

1. Copy `opp_savings/` into `<world>/datapacks/`. Needs Origins and Origins: Powerful Powers.
2. `/reload`, then `/function opp_savings:grant`. `/function opp_savings:revoke` removes the account, and its balance with it.

| Input | Effect |
|---|---|
| Primary key, holding an emerald (not sneaking) | Deposit it. 1 emerald = 10 coins. |
| Secondary key | Withdraw 10 coins as an emerald. Costs up to 3 streak periods and half of the fractional coin. |
| Sneak + secondary key | Freeze or unfreeze. Frozen accounts earn nothing and can't send or receive gifts. Freezing halves the streak. |
| Hit another account holder | Gift them 1% of your coins, rounded down. |
| Sneak + primary key | Show your last 12 interest payouts as particles above your head: green for 25+ coins, grey smoke below that. |

## The algorithm (`interest.json`, every 1200 ticks)

```java
streakBonus = Math.min(streak, 10) * 0.001;   // +0.1% per unbroken period, capped at +1%
fraction += coins;                            // the whole balance, as a double
fraction *= 1 + baseRate + streakBonus;       // compound
coins = (long) Math.floor(fraction);          // round down: whole coins...
fraction -= coins;                            // ...and the remainder stays behind
periods += 1;
streak += 1;
streak = Math.min(streak, 100);

// one execute block from here on
tier = coins >= 10_000L ? "gold" : coins >= 1_000L ? "silver" : "basic";
reached1000  = reached1000  || coins >= 1_000L;   // milestones stay true once reached
reached10000 = reached10000 || coins >= 10_000L;
fullYear     = fullYear     || periods >= 12;
```

In `interest.json`, the first eight lines are `modify_variable` actions, and each one is exactly the statement shown. For example, `{"variable": "fraction", "operation": "multiply", "value": "1 + baseRate + streakBonus"}` is `fraction *= 1 + baseRate + streakBonus;`. The tier and milestones are one `execute` block, because a `?:` chain reads better as code.

`fraction` briefly holds the whole balance during steps 2–5. That's how the code works without local variables. After step 5 it's back to being just the part below one coin.

### Worked run

These numbers come from running the pack's own code strings. 100 emeralds deposited, then 12 periods with no withdrawals:

| Period | Rate | `coins` | `fraction` |
|---|---|---|---|
| 0 | | 1000 | 0.000000 |
| 1 | 2.0% | 1020 | 0.000000 |
| 2 | 2.1% | 1041 | 0.420000 |
| 3 | 2.2% | 1064 | 0.331240 |
| 6 | 2.5% | 1142 | 0.815877 |
| 10 | 2.9% | 1273 | 0.803856 |
| 12 | 3.0% | 1351 | 0.378511 |

Each period's `fraction` carries into the next. Period 11 lands on `1312.017972` only because the earlier remainders built up.

## Payout history: a list and a read loop

`history.json` is a [`origins-powerful-powers:list`](../../docs/11-lists.md) of `long` with `max_size` 12 and `"when_full": "drop_oldest"`, so it always holds the last 12 payouts. The interest code ends with `history.add(coins - lastBalance);`, and `lastBalance` is set to `coins` at the start of each period.

`report.json` (sneak + primary) first prints a header with [`origins-powerful-powers:with_variables`](../../docs/09-variables-everywhere.md#with-variables), whose command text uses `${coins}`, `${tier}` and `${history.size()}`. Then it loops over the history with `origins-powerful-powers:for_each_read`. For each payout it prints a line, `#${index + 1}: +${value} coins`, coloured `${value >= 25 ? "green" : "gray"}`, and its nested `origins:if_else` checks `value >= 25` and spawns a green or a smoke puff. In the worked run above the history is `[20, 21, 23, 24, 26, 28, 30, 32, 33, 36, 39, 39]`: 8 green puffs and 4 smoke. The chat shows:

```
Savings: 1351 coins (silver), last 12 payouts:
  #1: +20 coins
  #2: +21 coins
  ...
```

## Where each operation is used

| Operation | File: code |
|---|---|
| `+=` `-=` `*=` `/=` | interest: `fraction += coins`, `*= 1 + …`, `-= coins`. withdraw: `coins -= 10`, `fraction /= 2` |
| `(long)` cast, `Math.floor`, `Math.min` | interest: rounding down, streak cap |
| int `/` | gift: `coins / 100` (rounds down). withdraw: `streak / 2` |
| `\|\|` to latch a boolean | interest: `reached1000 = reached1000 \|\| coins >= 1_000L`. Once true, it stays true |
| `xor` on a boolean | withdraw: `modify_variable` `frozen` xor `true` toggles it |
| nested `?:` | tier, freeze streak penalty |
| `&&` `\|\|` `!`, `==` `>=` `>` | every condition |
| `long` literals with `_` | `10_000L` |
| `String` + `.equals` | `tier`, gold_perk |
| `self.` / `other.` | gift (a bientity action and condition) |
| Arrays of lines + `// comments` | every file: `fields` and `code` are written one statement per element |
| `origins-powerful-powers:list`, `for_each_read`, `value` | history.json. interest: `history.add(…)`. report: `value >= 25` for each element |
| `modify_variable` (the JSON form) | interest: `set`, `add`, `multiply`, `subtract`, `min`. deposit: all of it. withdraw: `frozen` xor `true`. gift: `other.coins` add `"coins / 100"` (bientity) |
| `with_variables` (`${…}` in other Origins fields) | report: `${coins}`, `${tier}`, `${history.size()}` in a command, then `${index + 1}`, `${value}` and a `?:` colour per payout |
| Apoli resource interop | loyalty: `origins:resource` and `origins:change_resource` on `balance_bar` write straight into `coins` |

## Status

*(2026-09-11)* The OPP side is verified outside the game. All 29 code blocks, `modify_variable`s, list references and `with_variables` templates parse with the mod's parser in the right modes. The report's filled-in commands were checked for all 12 payouts, and the interest matched an independent Java implementation for 12 periods.
