# ShardingSphere 5.5.2 binding-table issue demo

Minimal reproduction for a binding-table rewrite issue in ShardingSphere-JDBC 5.5.2.

## Requirement

- JDK 17+
- Maven 3.6+

## Run

```bash
# Reproduce the issue
mvn -q compile exec:java -Dexec.args="dup"

# Control: t_order only belongs to one binding group
mvn -q compile exec:java -Dexec.args="single"

# Control: same duplicate groups, but the other group is registered first
mvn -q compile exec:java -Dexec.args="reverse"
```

## Configuration

Three sharding tables, all with the same actual data nodes and `order_id` as the sharding column
(complex strategy):

```yaml
tables:
  t_order:      ds_${0..1}.t_order_${0..1}
  t_order_item: ds_${0..1}.t_order_item_${0..1}
  t_other:      ds_${0..1}.t_other_${0..1}
```

`t_order` is intentionally registered in two binding table groups:

```yaml
bindingTables:
  - t_order,t_order_item
  - t_order,t_other
```

Executed SQL:

```sql
SELECT i.item_id
FROM t_order_item i
LEFT JOIN t_order o ON o.order_id = i.order_id
WHERE o.order_id = ?
```

## Expected

Both logical tables are rewritten to the same shard:

```text
Actual SQL: ds_1 ::: SELECT i.item_id FROM t_order_item_1 i LEFT JOIN t_order_1 o ...
ROWS=1
```

## Actual (5.5.2)

Only `t_order` is rewritten; `t_order_item` keeps its logical name:

```text
Actual SQL: ds_1 ::: SELECT i.item_id FROM t_order_item i LEFT JOIN t_order_1 o ...
QUERY FAILED: Table "T_ORDER_ITEM" not found
```

## Control results

| mode | bindingTables order | Actual SQL | Result |
| --- | --- | --- | --- |
| `single` | `t_order,t_order_item` | `t_order_item_1 ... t_order_1` | ROWS=1 |
| `dup` | `t_order,t_order_item` then `t_order,t_other` | `t_order_item ... t_order_1` | table not found |
| `reverse` | `t_order,t_other` then `t_order,t_order_item` | `t_order_item_1 ... t_order_1` | ROWS=1 |

## Root cause

1. `ShardingRule#createBindingTableRules` builds a `Map<String, BindingTableRule>` and puts every
   logic table of every group into it. When the same table appears in two groups, the **last group
   overwrites the previous one**.
2. In this demo, `findBindingTableRule("t_order")` returns the `t_order,t_other` group, which does
   not contain `t_order_item`.
3. `ShardingRouteEngineFactory#getLogicTableName` selects the table name from the first sharding
   condition. Here the only condition is `o.order_id = ?`, so the routing anchor becomes `t_order`.
4. `ShardingStandardRouteEngine` only creates a route unit for `t_order` (`t_order_1`). During SQL
   rewrite, the binding-table rule of `t_order` (`t_order,t_other`) cannot map `t_order_item`, so
   `t_order_item` stays as a logical table name and MySQL/H2 reports "table not found".

## Version comparison

A production service on ShardingSphere-JDBC **5.0.0** uses the same Nacos sharding configuration
(the same `bindingTables` groups) and the same SQL, and it works. The behavior regressed in later
5.5.x versions because the binding-table route/rewrite logic changed.

The 5.5.3 source still contains the same overwrite logic in `createBindingTableRules` and the same
`getLogicTableName` logic, so this demo targets 5.5.2 but the issue is expected to affect 5.5.3 as
well (source inspection only).

## Suggested fix

Fail fast at startup when one logic table is registered in more than one `bindingTables` group,
for example:

```text
Invalid binding table configuration: table `t_order` is referenced by multiple binding table groups.
```

Alternatively, keep all groups of a table (merge instead of overwrite) when building
`BindingTableRule`.
