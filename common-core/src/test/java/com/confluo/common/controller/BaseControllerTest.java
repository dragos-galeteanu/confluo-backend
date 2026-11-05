package com.confluo.common.controller;

import com.confluo.common.dto.Paginated;
import com.confluo.common.dto.PaginationMetaData;
import com.confluo.common.entity.StubEntity;
import com.confluo.common.exception.GlobalExceptionHandler;
import com.confluo.common.exception.ResourceNotFoundException;
import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.service.BaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link BaseController}: the HTTP contract every resource inherits - the routes, the
 * status codes, the binding of the pagination query parameters, and how failures surface once
 * {@link GlobalExceptionHandler} is in the chain.
 */
class BaseControllerTest {

    /** the entity stand-in; the controller never touches it, but the type parameter demands one */
    private static final class Widget extends StubEntity {

        private Widget() {
            super("widget");
        }
    }

    private record CreateWidget(@NotBlank String name) {
    }

    private record UpdateWidget(UUID id, String name) {
    }

    private record WidgetResponse(UUID id, String name) {
    }

    @RestController
    @RequestMapping("/widgets")
    private static final class WidgetController
            extends BaseController<Widget, CreateWidget, UpdateWidget, WidgetResponse> {

        private final BaseService<Widget, CreateWidget, UpdateWidget, WidgetResponse> service;

        private WidgetController(BaseService<Widget, CreateWidget, UpdateWidget, WidgetResponse> service) {
            this.service = service;
        }

        @Override
        protected BaseService<Widget, CreateWidget, UpdateWidget, WidgetResponse> getService() {
            return service;
        }
    }

    @SuppressWarnings("unchecked")
    private final BaseService<Widget, CreateWidget, UpdateWidget, WidgetResponse> service =
            mock(BaseService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID id = UUID.randomUUID();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new WidgetController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void creatingAResourceAnswersCreated() throws Exception {
        given(service.create(new CreateWidget("widget"))).willReturn(new WidgetResponse(id, "widget"));

        mockMvc.perform(post("/widgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateWidget("widget"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("widget"));
    }

    @Test
    void anInvalidCreateRequestIsRejectedBeforeTheServiceIsCalled() throws Exception {
        mockMvc.perform(post("/widgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateWidget("  "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.validationErrors.name").exists());

        verify(service, org.mockito.Mockito.never()).create(any());
    }

    @Test
    void gettingAResourceByIdAnswersOk() throws Exception {
        given(service.getById(id)).willReturn(new WidgetResponse(id, "widget"));

        mockMvc.perform(get("/widgets/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("widget"));
    }

    @Test
    void aMissingResourceAnswersNotFound() throws Exception {
        willThrow(new ResourceNotFoundException("Widget not found")).given(service).getById(id);

        mockMvc.perform(get("/widgets/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Widget not found"));
    }

    @Test
    void anIdThatIsNotAUuidAnswersBadRequest() throws Exception {
        mockMvc.perform(get("/widgets/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value 'not-a-uuid' for parameter 'id'"));
    }

    @Test
    void listingBindsThePaginationQueryParameters() throws Exception {
        given(service.getAll(any())).willReturn(new Paginated<>(
                List.of(new WidgetResponse(id, "widget")), new PaginationMetaData(1L, 25, 2, 1)));

        mockMvc.perform(get("/widgets")
                        .param("page", "2")
                        .param("pageSize", "25")
                        .param("filter", "name==widget")
                        .param("sort", "-createdAt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("widget"))
                .andExpect(jsonPath("$.metaData.currentPage").value(2));

        ArgumentCaptor<PaginationFilter> filter = ArgumentCaptor.forClass(PaginationFilter.class);
        verify(service).getAll(filter.capture());
        assertThat(filter.getValue())
                .isEqualTo(new PaginationFilter(2, 25, "name==widget", "-createdAt"));
    }

    @Test
    void listingWithoutQueryParametersUsesTheDefaults() throws Exception {
        given(service.getAll(any())).willReturn(new Paginated<>(List.of(), new PaginationMetaData(0L, 10, 1, 0)));

        mockMvc.perform(get("/widgets")).andExpect(status().isOk());

        ArgumentCaptor<PaginationFilter> filter = ArgumentCaptor.forClass(PaginationFilter.class);
        verify(service).getAll(filter.capture());
        assertThat(filter.getValue().page()).isEqualTo(PaginationFilter.DEFAULT_PAGE);
        assertThat(filter.getValue().pageSize()).isEqualTo(PaginationFilter.DEFAULT_PAGE_SIZE);
        assertThat(filter.getValue().filter()).isNull();
        assertThat(filter.getValue().sort()).isNull();
    }

    @Test
    void updatingAResourceAnswersOk() throws Exception {
        UpdateWidget request = new UpdateWidget(id, "renamed");
        given(service.update(request)).willReturn(new WidgetResponse(id, "renamed"));

        mockMvc.perform(put("/widgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed"));
    }

    @Test
    void disablingAResourceAnswersOk() throws Exception {
        given(service.disable(id)).willReturn(new WidgetResponse(id, "widget"));

        mockMvc.perform(delete("/widgets/{id}/disable", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        verify(service).disable(id);
    }

    @Test
    void deletingAResourceAnswersOk() throws Exception {
        given(service.delete(id)).willReturn(new WidgetResponse(id, "widget"));

        mockMvc.perform(delete("/widgets/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        verify(service).delete(id);
    }

    @Test
    void anUnexpectedFailureAnswersInternalServerErrorWithoutDetail() throws Exception {
        willThrow(new RuntimeException("connection refused to 10.0.0.5")).given(service).getById(id);

        mockMvc.perform(get("/widgets/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("An unexpected error occurred. If this persists, please contact support."));
    }
}
