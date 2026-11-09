package com.qa.autotest.common.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FR-3.2-02：分页归一化 + 排序白名单 + 键集游标的边界。 */
class PageQueryTest {

    private static final Map<String, String> SORTS = Map.of(
            "id", "id",
            "updated_at", "updated_at",
            "title", "title");

    @Test
    void normalizesPageAndSize() {
        PageQuery query = PageQuery.of(0, 0);
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(PageQuery.DEFAULT_SIZE);
        assertThat(query.orderBy()).isEqualTo("id DESC");
        assertThat(query.offset()).isZero();
        assertThat(query.explicitSort()).isFalse();
    }

    @Test
    void capsSizeAtOneHundred() {
        PageQuery query = PageQuery.of(2, 200);
        assertThat(query.size()).isEqualTo(PageQuery.MAX_SIZE);
        assertThat(query.offset()).isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    void computesOffset() {
        assertThat(PageQuery.of(3, 25).offset()).isEqualTo(50);
    }

    @Test
    void acceptsWhitelistedSort() {
        assertThat(PageQuery.of(1, 20, "updated_at", SORTS, "id").orderBy())
                .isEqualTo("updated_at DESC");
        assertThat(PageQuery.of(1, 20, "-updated_at", SORTS, "id").orderBy())
                .isEqualTo("updated_at DESC");
        assertThat(PageQuery.of(1, 20, "+updated_at", SORTS, "id").orderBy())
                .isEqualTo("updated_at ASC");
        assertThat(PageQuery.of(1, 20, "updated_at,asc", SORTS, "id").orderBy())
                .isEqualTo("updated_at ASC");
        assertThat(PageQuery.of(1, 20, "title,desc", SORTS, "id").orderBy())
                .isEqualTo("title DESC");
    }

    @Test
    void rejectsUnknownSortColumn() {
        BusinessException ex = null;
        try {
            PageQuery.of(1, 20, "created_by; DROP TABLE", SORTS, "id");
        } catch (BusinessException caught) {
            ex = caught;
        }
        assertThat(ex).isNotNull();
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(ex.getMessage()).contains("不支持的排序字段");
    }

    @Test
    void rejectsUnknownSortDirection() {
        BusinessException ex = null;
        try {
            PageQuery.of(1, 20, "updated_at,sideways", SORTS, "id");
        } catch (BusinessException caught) {
            ex = caught;
        }
        assertThat(ex).isNotNull();
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void defaultColumnMustBeWhitelisted() {
        IllegalArgumentException ex = null;
        try {
            PageQuery.of(1, 20, null, SORTS, "missing_column");
        } catch (IllegalArgumentException caught) {
            ex = caught;
        }
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("默认排序列必须属于白名单");
    }

    @Test
    void keysetModeUsesCursorInsteadOfOffset() {
        PageQuery query = PageQuery.keyset(30, 500L);
        assertThat(query.keysetMode()).isTrue();
        assertThat(query.beforeId()).isEqualTo(500L);
        assertThat(query.size()).isEqualTo(30);
        assertThat(query.offset()).isZero();
        assertThat(query.orderBy()).isEqualTo("id DESC");
        assertThat(query.explicitSort()).isTrue();
    }

    @Test
    void keysetSizeIsCapped() {
        assertThat(PageQuery.keyset(500, 1L).size()).isEqualTo(PageQuery.MAX_SIZE);
        assertThat(PageQuery.keyset(0, 1L).size()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }
}
