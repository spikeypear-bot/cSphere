package com.example.connect_sphere.venueavailability;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/venues/{venueId}/timing-conflicts")
public class TimingConflictController {
    private final TimingConflictService service;
    public TimingConflictController(TimingConflictService service) { this.service = service; }
    @GetMapping public org.springframework.http.ResponseEntity<List<TimingConflictService.Overlap>> list(@PathVariable UUID venueId) {
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.list(venueId));
    }
    @PostMapping("/scan") public List<TimingConflictService.Overlap> scan(@PathVariable UUID venueId) { return service.scan(venueId); }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionException.class})
    @ResponseStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
    public com.example.connect_sphere.common.web.ApiError unavailable() {
        return com.example.connect_sphere.common.web.ApiError.of("Could not retrieve booking conflicts. Please try again.");
    }
}
