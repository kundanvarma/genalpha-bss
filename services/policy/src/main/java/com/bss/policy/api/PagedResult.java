package com.bss.policy.api;

import java.util.List;

/**
 * A page of results plus the total row count, for TMF X-Total-Count headers.
 */
public record PagedResult<T>(List<T> items, long totalCount) {
}
