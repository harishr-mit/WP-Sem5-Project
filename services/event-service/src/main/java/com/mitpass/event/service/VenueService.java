package com.mitpass.event.service;

import com.mitpass.common.web.error.ConflictException;
import com.mitpass.common.web.error.NotFoundException;
import com.mitpass.common.web.paging.PageResponse;
import com.mitpass.event.domain.Venue;
import com.mitpass.event.mapper.VenueMapper;
import com.mitpass.event.repository.VenueRepository;
import com.mitpass.event.web.dto.VenueRequest;
import com.mitpass.event.web.dto.VenueResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class VenueService {

    private final VenueRepository venues;
    private final VenueMapper mapper;

    public VenueService(VenueRepository venues, VenueMapper mapper) {
        this.venues = venues;
        this.mapper = mapper;
    }

    public PageResponse<VenueResponse> list(Pageable pageable) {
        return PageResponse.from(venues.findAll(pageable), mapper::toResponse);
    }

    public VenueResponse get(long id) {
        return mapper.toResponse(load(id));
    }

    @Transactional
    public VenueResponse create(VenueRequest request) {
        if (venues.existsByNameIgnoreCase(request.name())) {
            throw duplicateName(request.name());
        }
        Venue venue = new Venue(request.name(), request.building(), request.capacity(), request.mapUrl());
        return mapper.toResponse(venues.saveAndFlush(venue));
    }

    @Transactional
    public VenueResponse update(long id, VenueRequest request) {
        Venue venue = load(id);
        if (venues.existsByNameIgnoreCaseAndIdNot(request.name(), id)) {
            throw duplicateName(request.name());
        }
        mapper.update(request, venue);
        return mapper.toResponse(venues.saveAndFlush(venue));
    }

    private Venue load(long id) {
        return venues.findById(id).orElseThrow(() -> NotFoundException.of("Venue", id));
    }

    private static ConflictException duplicateName(String name) {
        return new ConflictException("A venue named '%s' already exists".formatted(name));
    }
}
