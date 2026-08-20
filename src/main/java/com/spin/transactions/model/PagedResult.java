package com.spin.transactions.model;

import java.util.List;

public record PagedResult<T>(
        List<T> items,
        int page,
        int limit,
        boolean hasNext
) {
}
