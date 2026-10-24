package com.confluo.common.mapper;

import com.confluo.common.dto.Paginated;
import com.confluo.common.dto.PaginationMetaData;
import com.confluo.common.entity.BaseEntity;
import com.confluo.common.exception.InvalidFilterException;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.repository.BaseRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link PaginationMapper}.
 */
class PaginationMapperTest {

    /** a stand-in entity: the mapper only needs the BaseEntity bound, never a persistence context */
    private static class TestEntity extends BaseEntity {

        private final String name;

        private TestEntity(String name) {
            this.name = name;
        }
    }

    /** the repository shape the mapper reads through */
    private interface TestRepository extends BaseRepository<TestEntity> {
    }

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
        Page<TestEntity> page = new PageImpl<>(
                List.of(new TestEntity("c"), new TestEntity("d")), PageRequest.of(1, 2), 7);

        PaginationMetaData metaData = PaginationMapper.toMetaData(page);

        assertThat(metaData).isEqualTo(new PaginationMetaData(7L, 2, 2, 4));
    }

    @Test
    void toPaginatedMapsTheContentInOrder() {
        Page<TestEntity> page = new PageImpl<>(
                List.of(new TestEntity("first"), new TestEntity("second")), PageRequest.of(0, 2), 2);

        Paginated<String> paginated = PaginationMapper.toPaginated(page, entity -> entity.name);

        assertThat(paginated.data()).containsExactly("first", "second");
        assertThat(paginated.metaData()).isEqualTo(new PaginationMetaData(2L, 2, 1, 1));
    }

    @Test
    void toPaginatedHandlesAnEmptyPage() {
        Page<TestEntity> page = new PageImpl<>(List.of(), PageRequest.of(2, 10), 0);

        Paginated<String> paginated = PaginationMapper.toPaginated(page, entity -> entity.name);

        assertThat(paginated.data()).isEmpty();
        assertThat(paginated.metaData()).isEqualTo(new PaginationMetaData(0L, 10, 3, 0));
    }

    @Test
    void findAllPassesThePageableThroughAndMapsTheResult() {
        TestRepository repository = mock(TestRepository.class);
        Page<TestEntity> page = new PageImpl<>(
                List.of(new TestEntity("only")), PageRequest.of(1, 5), 6);
        given(repository.findAll(any(Specification.class), any(Pageable.class))).willReturn(page);

        Paginated<String> paginated = PaginationMapper.findAll(
                repository, filter(2, 5, null, "-createdAt"), entity -> entity.name);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.desc("createdAt"));
        assertThat(paginated.data()).containsExactly("only");
        assertThat(paginated.metaData().currentPage()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void findAllExcludesSoftDeletedRows() {
        TestRepository repository = mock(TestRepository.class);
        given(repository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        PaginationMapper.findAll(repository, filter(1, 10, null, null), entity -> entity.name);

        ArgumentCaptor<Specification<TestEntity>> specification = ArgumentCaptor.forClass(Specification.class);
        verify(repository).findAll(specification.capture(), any(Pageable.class));

        Root<TestEntity> root = mock(Root.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path deletedAt = mock(Path.class);
        Predicate isNull = mock(Predicate.class);
        given(root.get("deletedAt")).willReturn(deletedAt);
        given(criteriaBuilder.isNull(deletedAt)).willReturn(isNull);

        Predicate predicate = specification.getValue()
                .toPredicate(root, mock(CriteriaQuery.class), criteriaBuilder);

        assertThat(predicate).isSameAs(isNull);
    }

    @Test
    void findAllReportsAnUnknownSortFieldAsAnInvalidFilter() {
        TestRepository repository = mock(TestRepository.class);
        given(repository.findAll(any(Specification.class), any(Pageable.class)))
                .willThrow(new PropertyReferenceException(
                        "bogus", TypeInformation.of(TestEntity.class), List.of()));

        assertThatThrownBy(() -> PaginationMapper.findAll(
                repository, filter(1, 10, null, "bogus"), entity -> entity.name))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Unknown field");
    }

    @Test
    void findAllReportsAnUnknownFilterFieldAsAnInvalidFilter() {
        TestRepository repository = mock(TestRepository.class);
        given(repository.findAll(any(Specification.class), any(Pageable.class)))
                .willThrow(new InvalidDataAccessApiUsageException("Unable to locate attribute"));

        assertThatThrownBy(() -> PaginationMapper.findAll(
                repository, filter(1, 10, "bogus==" + UUID.randomUUID(), null), entity -> entity.name))
                .isInstanceOf(InvalidFilterException.class)
                .hasMessageContaining("Unknown field");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void findAllCombinesTheSoftDeleteCheckWithTheRequestedFilter() {
        TestRepository repository = mock(TestRepository.class);
        given(repository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        UUID companyId = UUID.randomUUID();
        PaginationMapper.findAll(
                repository, filter(1, 10, "companyId==" + companyId, null), entity -> entity.name);

        ArgumentCaptor<Specification<TestEntity>> specification = ArgumentCaptor.forClass(Specification.class);
        verify(repository).findAll(specification.capture(), any(Pageable.class));

        Root<TestEntity> root = mock(Root.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path deletedAt = mock(Path.class);
        Path company = mock(Path.class);
        Predicate notDeleted = mock(Predicate.class);
        Predicate matchesCompany = mock(Predicate.class);
        Predicate both = mock(Predicate.class);
        given(root.get("deletedAt")).willReturn(deletedAt);
        given(root.get("companyId")).willReturn(company);
        given(company.getJavaType()).willReturn((Class) UUID.class);
        given(criteriaBuilder.isNull(deletedAt)).willReturn(notDeleted);
        given(criteriaBuilder.equal(company, companyId)).willReturn(matchesCompany);
        given(criteriaBuilder.and(notDeleted, matchesCompany)).willReturn(both);

        Predicate predicate = specification.getValue()
                .toPredicate(root, mock(CriteriaQuery.class), criteriaBuilder);

        assertThat(predicate).isSameAs(both);
    }
}
