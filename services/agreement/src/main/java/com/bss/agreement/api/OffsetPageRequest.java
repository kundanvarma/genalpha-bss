package com.bss.agreement.api;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pageable with a row offset that need not be a multiple of the page size,
 * matching TMF630 offset/limit semantics. Results are ordered by id so that
 * consecutive pages are stable.
 *
 * <p>Only {@link #getOffset()}, {@link #getPageSize()} and {@link #getSort()} drive query
 * execution. Page-number navigation ({@code withPage}, {@code Page.hasNext()}) is
 * approximate when the offset is not a multiple of the page size.
 */
public class OffsetPageRequest implements Pageable {

    private final long offset;
    private final int limit;

    public OffsetPageRequest(long offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        this.offset = offset;
        this.limit = limit;
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return Sort.by(Sort.Direction.ASC, "id");
    }

    @Override
    public Pageable next() {
        return new OffsetPageRequest(offset + limit, limit);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetPageRequest(Math.max(0, offset - limit), limit) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetPageRequest(0, limit);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageRequest((long) pageNumber * limit, limit);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
