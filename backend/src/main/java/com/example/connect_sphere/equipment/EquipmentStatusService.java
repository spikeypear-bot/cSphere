package com.example.connect_sphere.equipment;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EquipmentStatusService {

    private final SerialisedEquipmentRepository repository;

    // Spring sees this constructor and passes in the repository for us.
    public EquipmentStatusService(SerialisedEquipmentRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<EquipmentUnitResponse> listUnits() {
        return repository.findAll().stream()
                .map(EquipmentUnitResponse::from)
                .sorted(Comparator.comparing(EquipmentUnitResponse::equipmentName)
                        .thenComparing(EquipmentUnitResponse::serialNumber))
                .toList();
    }

    @Transactional
    public EquipmentUnitResponse updateStatus(
            UUID equipmentId, String serialNumber, EquipmentStatus newStatus) {

        if (newStatus == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }

        SerialisedEquipment unit = repository
                .findById(new SerialisedEquipmentId(equipmentId, serialNumber))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Equipment unit not found"));

        unit.setStatus(newStatus);
        return EquipmentUnitResponse.from(repository.save(unit));
    }
}