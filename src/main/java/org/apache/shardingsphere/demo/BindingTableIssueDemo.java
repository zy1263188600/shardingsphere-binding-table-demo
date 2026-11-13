package org.apache.shardingsphere.demo;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Minimal reproduction for ShardingSphere 5.5.2.
 *
 * <p>Three sharding tables: {@code t_order}, {@code t_order_item}, {@code t_other}.
 * All of them use the same actual data nodes and {@code order_id} as the sharding column
 * (complex strategy). {@code t_order} is put into TWO binding table groups:
 *
 * <pre>
 *   - t_order,t_order_item
 *   - t_order,t_other
 * </pre>
 *
 * <p>The executed SQL uses the right table's sharding key in WHERE:
 *
 * <pre>
 *   SELECT i.item_id FROM t_order_item i
 *   LEFT JOIN t_order o ON o.order_id = i.order_id
 *   WHERE o.order_id = ?
 * </pre>
 *
 * Expected actual SQL:
 * {@code ... FROM t_order_item_1 i LEFT JOIN t_order_1 o ...}
 *
 * <p>Actual SQL on 5.5.2 (mode=dup):
 * {@code ... FROM t_order_item i LEFT JOIN t_order_1 o ...}
 * -> {@code Table "T_ORDER_ITEM" not found}
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code single}: t_order is only in "t_order,t_order_item" -> works</li>
 *   <li>{@code dup}: t_order is in both groups, item group first -> fails</li>
 *   <li>{@code reverse}: t_order is in both groups, other group first -> works</li>
 * </ul>
 */
public final class BindingTableIssueDemo {
    
    public static void main(final String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "dup";
        String bindingTables;
        if ("single".equals(mode)) {
            bindingTables = "      - t_order,t_order_item";
        } else if ("reverse".equals(mode)) {
            bindingTables = "      - t_order,t_other\n      - t_order,t_order_item";
        } else {
            bindingTables = "      - t_order,t_order_item\n      - t_order,t_other";
        }
        String yaml = String.join("\n",
                "dataSources:",
                "  ds_0:",
                "    dataSourceClassName: com.zaxxer.hikari.HikariDataSource",
                "    driverClassName: org.h2.Driver",
                "    jdbcUrl: jdbc:h2:mem:" + mode + "0;DB_CLOSE_DELAY=-1",
                "    username: sa",
                "    password: ''",
                "  ds_1:",
                "    dataSourceClassName: com.zaxxer.hikari.HikariDataSource",
                "    driverClassName: org.h2.Driver",
                "    jdbcUrl: jdbc:h2:mem:" + mode + "1;DB_CLOSE_DELAY=-1",
                "    username: sa",
                "    password: ''",
                "rules:",
                "  - !SHARDING",
                "    tables:",
                tableYaml("t_order"),
                tableYaml("t_order_item"),
                tableYaml("t_other"),
                "    bindingTables:",
                bindingTables,
                "    shardingAlgorithms:",
                "      demo_complex:",
                "        type: CLASS_BASED",
                "        props:",
                "          strategy: COMPLEX",
                "          algorithmClassName: " + DemoComplexAlgorithm.class.getName(),
                "props:",
                "  sql-show: true",
                "");
        DataSource dataSource = org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory
                .createDataSource(yaml.getBytes(StandardCharsets.UTF_8));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t_order (order_id bigint, user_id bigint)");
            statement.execute("CREATE TABLE t_order_item (order_id bigint, item_id bigint)");
            statement.execute("CREATE TABLE t_other (order_id bigint)");
            statement.execute("INSERT INTO t_order (order_id, user_id) VALUES (1, 10)");
            statement.execute("INSERT INTO t_order_item (order_id, item_id) VALUES (1, 100)");
        }
        String sql = "SELECT i.item_id FROM t_order_item i LEFT JOIN t_order o ON o.order_id = i.order_id WHERE o.order_id = ?";
        System.out.println("===== MODE=" + mode);
        System.out.println("===== SQL=" + sql);
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                int count = 0;
                while (resultSet.next()) {
                    count++;
                }
                System.out.println("ROWS=" + count);
            }
        } catch (Exception ex) {
            System.out.println("QUERY FAILED: " + ex.getMessage());
        }
        if (dataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
    
    private static String tableYaml(final String tableName) {
        return String.join("\n",
                "      " + tableName + ":",
                "        actualDataNodes: ds_${0..1}." + tableName + "_${0..1}",
                "        databaseStrategy:",
                "          complex:",
                "            shardingColumns: order_id",
                "            shardingAlgorithmName: demo_complex",
                "        tableStrategy:",
                "          complex:",
                "            shardingColumns: order_id",
                "            shardingAlgorithmName: demo_complex");
    }
}
