package com.spin.transactions.controller.response;

import java.util.List;

public record PagedResponse<T>(
        List<T> items,
        int page,
        int limit,
        boolean hasNext
) {
}
