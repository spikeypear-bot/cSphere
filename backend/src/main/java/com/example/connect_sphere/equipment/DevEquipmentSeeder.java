package com.example.connect_sphere.equipment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
public class DevEquipmentSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevEquipmentSeeder.class);
    private static final char GENERAL_EQUIPMENT_TYPE = 'E';

    private static final List<SeedEquipment> SEED_EQUIPMENT = List.of(
            new SeedEquipment("projector", "Projector", 6, true),
            new SeedEquipment("microphone", "Wireless Microphone", 16, true),
            new SeedEquipment("speaker", "Portable Speaker", 8, true),
            new SeedEquipment("mixer", "Audio Mixer", 3, true),
            new SeedEquipment("laptop", "Laptop", 8, true),
            new SeedEquipment("webcam", "Webcam", 8, false),
            new SeedEquipment("hdmi-cable", "HDMI Cable", 30, false),
            new SeedEquipment("extension-cord", "Extension Cord", 20, false),
            new SeedEquipment("tripod", "Camera Tripod", 6, false));

    private final JdbcTemplate jdbc;

    public DevEquipmentSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(String... args) {
        int created = 0;
        for (SeedEquipment seed : SEED_EQUIPMENT) {
            UUID equipmentId = UUID.nameUUIDFromBytes(
                    ("csphere-dev-equipment:" + seed.key()).getBytes(StandardCharsets.UTF_8));

            created += jdbc.update("""
                    INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (equipment_id) DO NOTHING
                    """, equipmentId, seed.name(), seed.quantity(), seed.serialised(), String.valueOf(GENERAL_EQUIPMENT_TYPE));

            SeedState state = jdbc.queryForObject("""
                    SELECT equipment_qty, serialised
                    FROM equipments
                    WHERE equipment_id = ?
                    """, (result, row) -> new SeedState(
                            result.getInt("equipment_qty"),
                            result.getObject("serialised", Boolean.class)), equipmentId);

            if (Boolean.TRUE.equals(state.serialised())) {
                created += seedSerialNumbers(equipmentId, seed.key(), state.quantity());
            }
        }

        if (created > 0) {
            log.info("Dev equipment seed: added catalogue entries or serialised units.");
        } else {
            log.info("Dev equipment seed: all demo entries already present.");
        }
    }

    private int seedSerialNumbers(UUID equipmentId, String key, int quantity) {
        int created = 0;
        for (int unit = 1; unit <= quantity; unit++) {
            String serialNumber = "DEMO-" + key.toUpperCase().replace('-', '_')
                    + "-" + String.format("%03d", unit);
            created += jdbc.update("""
                    INSERT INTO serialised_equipments (equipment_id, serial_number, status)
                    VALUES (?, ?, ?::equipment_status)
                    ON CONFLICT (equipment_id, serial_number) DO NOTHING
                    """, equipmentId, serialNumber, EquipmentStatus.AVAILABLE.name());
        }
        return created;
    }

    private record SeedEquipment(String key, String name, int quantity, boolean serialised) {}

    private record SeedState(int quantity, Boolean serialised) {}
}
