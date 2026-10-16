package com.mitpass.event.mapper;

import com.mitpass.event.domain.Event;
import com.mitpass.event.domain.EventSession;
import com.mitpass.event.web.dto.EventCreateRequest;
import com.mitpass.event.web.dto.EventDetailResponse;
import com.mitpass.event.web.dto.EventSummaryResponse;
import com.mitpass.event.web.dto.EventUpdateRequest;
import com.mitpass.event.web.dto.SessionResponse;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * Entity ↔ DTO mapping, generated at compile time. {@code unmappedTargetPolicy = ERROR} (root POM) makes a forgotten
 * field a compile error. Category, venue and sessions are resolved by the service, never copied from ids here.
 */
@Mapper(
        uses = {CategoryMapper.class, VenueMapper.class},
        injectionStrategy = InjectionStrategy.CONSTRUCTOR)
public interface EventMapper {

    @Mapping(target = "categoryCode", source = "category.code")
    @Mapping(target = "categoryName", source = "category.name")
    @Mapping(target = "venueId", source = "venue.id")
    @Mapping(target = "venueName", source = "venue.name")
    EventSummaryResponse toSummary(Event event);

    @Mapping(target = "allowedTransitions", expression = "java(java.util.List.copyOf(event.getStatus().manualNext()))")
    EventDetailResponse toDetail(Event event);

    SessionResponse toResponse(EventSession session);

    // eligibleDepartments is set by the service: MapStruct would clear() the entity's read-only getter list.
    // staticQrAllowed too: null means "default" on create and "unchanged" on update.
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "venue", ignore = true)
    @Mapping(target = "sessions", ignore = true)
    @Mapping(target = "organizerId", ignore = true)
    @Mapping(target = "organizerName", ignore = true)
    @Mapping(target = "eligibleDepartments", ignore = true)
    @Mapping(target = "staticQrAllowed", ignore = true)
    void apply(EventCreateRequest request, @MappingTarget Event event);

    @Mapping(target = "category", ignore = true)
    @Mapping(target = "venue", ignore = true)
    @Mapping(target = "sessions", ignore = true)
    @Mapping(target = "organizerId", ignore = true)
    @Mapping(target = "organizerName", ignore = true)
    @Mapping(target = "eligibleDepartments", ignore = true)
    @Mapping(target = "staticQrAllowed", ignore = true)
    void apply(EventUpdateRequest request, @MappingTarget Event event);
}
