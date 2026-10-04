package com.example.connect_sphere.equipment;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentCatalogueController {

    private final EquipmentRepository equipmentRepository;

    public EquipmentCatalogueController(EquipmentRepository equipmentRepository) {
        this.equipmentRepository = equipmentRepository;
    }

    @GetMapping("/catalogue")
    public List<EquipmentCatalogueResponse> catalogue() {
        return equipmentRepository.findAll(Sort.by("name")).stream()
                .map(equipment -> new EquipmentCatalogueResponse(
                        equipment.getId(), equipment.getName(), equipment.getQuantity(),
                        Boolean.TRUE.equals(equipment.getSerialised())))
                .toList();
    }
}
