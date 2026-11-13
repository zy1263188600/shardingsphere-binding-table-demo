package org.apache.shardingsphere.demo;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Minimal complex sharding algorithm used by the demo.
 * Routes ds_0/ds_1 and xxx_0/xxx_1 by {@code order_id % 2}.
 */
public final class DemoComplexAlgorithm implements ComplexKeysShardingAlgorithm<Long> {
    
    @Override
    public Collection<String> doSharding(final Collection<String> availableTargetNames, final ComplexKeysShardingValue<Long> shardingValue) {
        Collection<Long> values = shardingValue.getColumnNameAndShardingValuesMap().get("order_id");
        if (values == null || values.isEmpty()) {
            return availableTargetNames;
        }
        Object value = values.iterator().next();
        String suffix = String.valueOf(((Number) value).longValue() % 2);
        Collection<String> result = new ArrayList<>();
        for (String each : availableTargetNames) {
            if (each.endsWith("_" + suffix)) {
                result.add(each);
            }
        }
        return result.isEmpty() ? availableTargetNames : result;
    }
}
