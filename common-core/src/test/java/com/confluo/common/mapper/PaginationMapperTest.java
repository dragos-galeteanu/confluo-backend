package com.confluo.common.mapper;

import com.confluo.common.dto.Paginated;
import com.confluo.common.dto.PaginationMetaData;
import com.confluo.common.entity.StubEntity;
import com.confluo.common.exception.InvalidFilterException;
import com.confluo.common.filter.PaginationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link PaginationMapper}.
 */
class PaginationMapperTest {

    private static PaginationFilter filter(Integer page, Integer pageSize, String filter, String sort) {
        return new PaginationFilter(page, pageSize, filter, sort);
    }

    @Test
    void toPageableConvertsTheOneBasedPageToAZeroBasedIndex() {
        Pageable pageable = PaginationMapper.toPageable(filter(3, 25, null, null));

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(25);
        assertThat(pageable.getSort()).isEqualTo(Sort.unsorted());
    }

    @Test
    void toPageableAppliesTheRecordDefaultsWhenNothingWasAskedFor() {
        Pageable pageable = PaginationMapper.toPageable(filter(null, null, null, null));

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(PaginationFilter.DEFAULT_PAGE_SIZE);
    }

    @Test
    void toPageableRejectsAPageBelowOne() {
        assertThatThrownBy(() -> PaginationMapper.toPageable(filter(0, 10, null, null)))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Page must be at least 1");
    }

    @Test
    void toPageableRejectsAPageSizeBelowOne() {
        assertThatThrownBy(() -> PaginationMapper.toPageable(filter(1, 0, null, null)))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Page size must be at least 1");
    }

    @Test
    void toSortReadsTheDescendingPrefixAndKeepsTheOrderOfTheFields() {
        Sort sort = PaginationMapper.toSort("-createdAt,name");

        assertThat(sort).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.asc("name"));
    }

    @Test
    void toSortTrimsWhitespaceAndSkipsEmptyFields() {
        Sort sort = PaginationMapper.toSort(" -createdAt , , name ");

        assertThat(sort).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.asc("name"));
    }

    @Test
    void toSortTreatsNothingAsUnsorted() {
        assertThat(PaginationMapper.toSort(null)).isEqualTo(Sort.unsorted());
        assertThat(PaginationMapper.toSort("  ")).isEqualTo(Sort.unsorted());
        assertThat(PaginationMapper.toSort(",,")).isEqualTo(Sort.unsorted());
    }

    @Test
    void toSortRejectsAFieldThatIsNothingButThePrefix() {
        assertThatThrownBy(() -> PaginationMapper.toSort("-"))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Malformed sort field");
    }

    @Test
    void toMetaDataReportsTheOneBasedPageAndTheSizeThePageActuallyHas() {
        Page<StubEntity> page = new PageImpl<>(
                List.of(new StubEntity("c"), new StubEntity("d")), PageRequest.of(1, 2), 7);

        PaginationMetaData metaData = PaginationMapper.toMetaData(page);

        assertThat(metaData).isEqualTo(new PaginationMetaData(7L, 2, 2, 4));
    }

    @Test
    void toPaginatedMapsTheContentInOrder() {
        Page<StubEntity> page = new PageImpl<>(
                List.of(new StubEntity("first"), new StubEntity("second")), PageRequest.of(0, 2), 2);

        Paginated<String> paginated = PaginationMapper.toPaginated(page, StubEntity::getName);

        assertThat(paginated.data()).containsExactly("first", "second");
        assertThat(paginated.metaData()).isEqualTo(new PaginationMetaData(2L, 2, 1, 1));
    }

    @Test
    void toPaginatedHandlesAnEmptyPage() {
        Page<StubEntity> page = new PageImpl<>(List.of(), PageRequest.of(2, 10), 0);

        Paginated<String> paginated = PaginationMapper.toPaginated(page, StubEntity::getName);

        assertThat(paginated.data()).isEmpty();
        assertThat(paginated.metaData()).isEqualTo(new PaginationMetaData(0L, 10, 3, 0));
    }
}
