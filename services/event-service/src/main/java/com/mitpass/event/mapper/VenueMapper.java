package com.mitpass.event.mapper;

import com.mitpass.event.domain.Venue;
import com.mitpass.event.web.dto.VenueRequest;
import com.mitpass.event.web.dto.VenueResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

@Mapper
public interface VenueMapper {

    VenueResponse toResponse(Venue venue);

    /** PUT semantics: every editable field is replaced (a missing optional field becomes null). */
    void update(VenueRequest request, @MappingTarget Venue venue);
}
