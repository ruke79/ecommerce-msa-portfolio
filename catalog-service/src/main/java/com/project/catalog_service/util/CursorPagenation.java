package com.project.catalog_service.util;

import java.util.List;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class CursorPagenation<T> {

    private final List<T> items; // 현재 스크롤의 요소 + 다음 스크롤의 요소 1개 (다음 스크롤이 있는지 확인을 위한)
    private final int countPerScroll;

    public static <T> CursorPagenation<T> of(List<T> itemList, int size) {
        return new CursorPagenation<>(itemList, size);
    }

    public boolean isLastScroll() {
        return this.items.size() <= countPerScroll;
    }

    public List<T> getCurrentScrollItems() {
        if (isLastScroll()) {
            return this.items;
        }
        return this.items.subList(0, countPerScroll);
    }

    public T getNextCursor() {
        return items.get(countPerScroll - 1);
    }

}
