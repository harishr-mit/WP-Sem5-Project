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

    EventDetailResponse toDetail(Event event);

    SessionResponse toResponse(EventSession session);

    @Mapping(target = "category", ignore = true)
    @Mapping(target = "venue", ignore = true)
    @Mapping(target = "sessions", ignore = true)
    void apply(EventCreateRequest request, @MappingTarget Event event);

    @Mapping(target = "category", ignore = true)
    @Mapping(target = "venue", ignore = true)
    @Mapping(target = "sessions", ignore = true)
    void apply(EventUpdateRequest request, @MappingTarget Event event);
}
