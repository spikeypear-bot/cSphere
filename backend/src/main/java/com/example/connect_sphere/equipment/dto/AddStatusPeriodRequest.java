package com.example.connect_sphere.equipment.dto;

import com.example.connect_sphere.equipment.entity.EquipmentStatus;
import java.time.Instant;

// end = null means "no end date".
public record AddStatusPeriodRequest(EquipmentStatus status, Instant start, Instant end) {}