package com.example.connect_sphere.venueavailability;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/venues/{venueId}/timing-conflicts")
public class TimingConflictController {
    private final TimingConflictService service;
    public TimingConflictController(TimingConflictService service) { this.service = service; }
    @GetMapping public List<TimingConflictService.Overlap> list(@PathVariable UUID venueId) { return service.list(venueId); }
    @PostMapping("/scan") public List<TimingConflictService.Overlap> scan(@PathVariable UUID venueId) { return service.scan(venueId); }
}
