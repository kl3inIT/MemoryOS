package io.memoryos.chat.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.jspecify.annotations.NullMarked;

/** Preserves the HTTP offset/limit contract, including offsets that are not page-size multiples. */
@NullMarked
public record ChatPage(int offset, int size) implements Pageable {
    public ChatPage {
        if (offset < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page");
    }
    @Override public int getPageNumber() { return offset / size; }
    @Override public int getPageSize() { return size; }
    @Override public long getOffset() { return offset; }
    @Override public Sort getSort() { return Sort.unsorted(); }
    @Override public Pageable next() { return new ChatPage(Math.addExact(offset, size), size); }
    @Override public Pageable previousOrFirst() { return new ChatPage(Math.max(0, offset - size), size); }
    @Override public Pageable first() { return new ChatPage(0, size); }
    @Override public Pageable withPage(int pageNumber) { return new ChatPage(Math.multiplyExact(pageNumber, size), size); }
    @Override public boolean hasPrevious() { return offset > 0; }
}
