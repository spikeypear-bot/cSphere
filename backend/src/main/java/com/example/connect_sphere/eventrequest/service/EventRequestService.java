package com.example.connect_sphere.eventrequest.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.entity.EventRequestStatus;
import com.example.connect_sphere.eventrequest.mapper.EventRequestMapper;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;

/**
 * Implements EO01 (save/reopen/edit a draft) and EO02 (submit) end to end.
 * Business rules live here, not in the controller or the entity, per
 * AI_Context.md's layering convention.
 */
@Service
public class EventRequestService {

    private static final char REQUEST_TYPE_CREATION = 'C';

    private final EventRequestRepository repository;
    private final EventRequestMapper mapper;

    public EventRequestService(EventRequestRepository repository, EventRequestMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public EventRequestDto saveNewDraft(String organisation, SaveEventRequestRequest request) {
        requireOrganisation(organisation);
        EventRequest entity = new EventRequest();
        entity.setRequestId(UUID.randomUUID());
        entity.setRequestType(REQUEST_TYPE_CREATION);
        entity.setOrganisation(organisation);
        entity.setStatus(EventRequestStatus.draft);
        OffsetDateTime now = OffsetDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        applyFields(entity, request);
        return mapper.toDto(repository.save(entity));
    }

    @Transactional
    public EventRequestDto updateDraft(String organisation, UUID requestId, SaveEventRequestRequest request) {
        requireOrganisation(organisation);
        EventRequest entity = findOwnedDraft(organisation, requestId);
        applyFields(entity, request);
        entity.setUpdatedAt(OffsetDateTime.now());
        // If this save fails (e.g. the transaction rolls back for any reason),
        // Spring/JPA leaves the previously committed row untouched — EO01's
        // "does not overwrite the last successfully saved version if a later
        // save attempt fails" falls out of @Transactional for free, as long as
        // nothing here does a non-transactional side effect.
        return mapper.toDto(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public EventRequestDto get(String organisation, UUID requestId) {
        requireOrganisation(organisation);
        return mapper.toDto(findOwned(organisation, requestId));
    }

    @Transactional(readOnly = true)
    public List<EventRequestDto> list(String organisation) {
        requireOrganisation(organisation);
        return repository.findByOrganisationOrderByCreatedAtDesc(organisation).stream()
                .map(mapper::toDto)
                .toList();
    }

    @Transactional
    public EventRequestDto submit(String organisation, UUID requestId) {
        requireOrganisation(organisation);
        EventRequest entity = findOwnedDraft(organisation, requestId);
        List<String> missing = missingRequiredFields(entity);
        if (!missing.isEmpty()) {
            throw new IncompleteEventRequestException(missing);
        }
        entity.setStatus(EventRequestStatus.pending);
        entity.setUpdatedAt(OffsetDateTime.now());
        return mapper.toDto(repository.save(entity));
    }

    private void applyFields(EventRequest entity, SaveEventRequestRequest request) {
        entity.setEventName(request.eventName());
        entity.setPurpose(request.purpose());
        entity.setDescription(request.description());
        entity.setStartDatetime(request.startDatetime());
        entity.setEndDatetime(request.endDatetime());
        entity.setExpectedAttendance(request.expectedAttendance());
        entity.setVenueRequirements(request.venueRequirements());
        entity.setEquipmentRequirements(request.equipmentRequirements());
        entity.setAccessibilityNeeds(
                request.accessibilityNeeds() == null ? List.of() : request.accessibilityNeeds());
        entity.setRegistrationNeeds(request.registrationNeeds());
    }

    private EventRequest findOwned(String organisation, UUID requestId) {
        EventRequest entity = repository.findById(requestId)
                .orElseThrow(() -> new EventRequestNotFoundException(requestId));
        if (!organisation.equals(entity.getOrganisation())) {
            throw new EventRequestNotFoundException(requestId);
        }
        return entity;
    }

    private EventRequest findOwnedDraft(String organisation, UUID requestId) {
        EventRequest entity = findOwned(organisation, requestId);
        if (entity.getStatus() != EventRequestStatus.draft) {
            throw new EventRequestNotEditableException(requestId, entity.getStatus());
        }
        return entity;
    }

    private static List<String> missingRequiredFields(EventRequest entity) {
        List<String> missing = new ArrayList<>();
        if (isBlank(entity.getEventName())) {
            missing.add("eventName");
        }
        if (isBlank(entity.getPurpose())) {
            missing.add("purpose");
        }
        if (entity.getStartDatetime() == null) {
            missing.add("startDatetime");
        }
        if (entity.getEndDatetime() == null) {
            missing.add("endDatetime");
        }
        if (entity.getExpectedAttendance() == null) {
            missing.add("expectedAttendance");
        }
        if (isBlank(entity.getVenueRequirements())) {
            missing.add("venueRequirements");
        }
        if (entity.getAccessibilityNeeds() == null || entity.getAccessibilityNeeds().isEmpty()) {
            missing.add("accessibilityNeeds");
        }
        return missing;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireOrganisation(String organisation) {
        if (isBlank(organisation)) {
            throw new MissingOrganisationException();
        }
    }
}
