package com.example.connect_sphere.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.equipment.DevEquipmentSeeder.SeedEquipment;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

@Tag("integration")
@Tag("TS09")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DevEquipmentSeederFlowTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    private int units(UUID equipmentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM serialised_equipments WHERE equipment_id = ?", Integer.class, equipmentId);
    }

    private int totalUnits() {
        return jdbc.queryForObject("SELECT count(*) FROM serialised_equipments", Integer.class);
    }

    @Test
    @DisplayName("TS09 (happy): every serialised kind gets one unit per item, each with a serial in its own format")
    void seedsUnitsInEachKindsFormat() {
        new DevEquipmentSeeder(jdbc).run();

        for (SeedEquipment seed : DevEquipmentSeeder.SEED_EQUIPMENT) {
            UUID id = DevEquipmentSeeder.equipmentId(seed.key());
            if (!seed.serialised()) {
                assertThat(units(id)).as(seed.name()).isZero();
                continue;
            }
            List<String> serials = jdbc.queryForList(
                    "SELECT serial_number FROM serialised_equipments WHERE equipment_id = ?", String.class, id);
            assertThat(serials).as(seed.name()).hasSize(seed.quantity()).doesNotHaveDuplicates();
            // A database seeded before TS09 keeps its DEMO-… units; every newer one follows the kind's format.
            assertThat(serials).filteredOn(serial -> !serial.startsWith("DEMO-"))
                    .allSatisfy(serial -> assertThat(serial)
                            .startsWith(seed.serialPattern().prefix())
                            .hasSize(seed.serialPattern().length()));
        }
    }

    @Test
    @DisplayName("TS09 (happy): running the seeder again adds no kinds and no units")
    void restartAddsNothing() {
        DevEquipmentSeeder seeder = new DevEquipmentSeeder(jdbc);
        seeder.run();
        int kinds = jdbc.queryForObject("SELECT count(*) FROM equipments", Integer.class);
        int unitCount = totalUnits();

        seeder.run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipments", Integer.class)).isEqualTo(kinds);
        assertThat(totalUnits()).isEqualTo(unitCount);
    }

    @Test
    @DisplayName("TS09 (happy): a kind whose quantity grows is topped up with new units and keeps its existing ones")
    void topsUpAKindToItsQuantity() {
        DevEquipmentSeeder seeder = new DevEquipmentSeeder(jdbc);
        seeder.run();
        UUID projector = DevEquipmentSeeder.equipmentId("projector");
        List<String> before = jdbc.queryForList(
                "SELECT serial_number FROM serialised_equipments WHERE equipment_id = ?", String.class, projector);
        jdbc.update("UPDATE equipments SET equipment_qty = ? WHERE equipment_id = ?", before.size() + 2, projector);

        seeder.run();

        List<String> after = jdbc.queryForList(
                "SELECT serial_number FROM serialised_equipments WHERE equipment_id = ?", String.class, projector);
        assertThat(after).hasSize(before.size() + 2).containsAll(before);
        assertThat(after).filteredOn(serial -> !before.contains(serial))
                .allSatisfy(serial -> assertThat(serial).matches("EPS-EBU04-[0-9A-HJ-NP-Z]{6}"));
    }

    @Test
    @DisplayName("TS09 (boundary): the database stores a 64-character serial number and refuses a 65-character one")
    void serialNumberColumnIs64Characters() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type)
                VALUES (?, 'TS09 length probe', 2, true, 'E')
                """, id);
        String insert = "INSERT INTO serialised_equipments (equipment_id, serial_number, status) "
                + "VALUES (?, ?, 'AVAILABLE'::equipment_status)";

        jdbc.update(insert, id, "S".repeat(64));

        for (String table : List.of("serialised_equipments", "equipment_logs", "equipment_status_periods")) {
            assertThat(jdbc.queryForObject("""
                    SELECT character_maximum_length FROM information_schema.columns
                    WHERE table_name = ? AND column_name = 'serial_number'
                    """, Integer.class, table)).as(table).isEqualTo(64);
        }
        // Last: a failed statement aborts the surrounding (rolled-back) transaction.
        assertThatThrownBy(() -> jdbc.update(insert, id, "T".repeat(65)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("TS09 (negative): a reservation naming a serial number over 64 characters is refused and nothing is saved")
    void reservationRefusesOverlongSerial() throws Exception {
        int before = jdbc.queryForObject("SELECT count(*) FROM equipment_logs", Integer.class);
        String body = """
                {"eventId":"%s","equipmentId":"%s","quantity":1,"serialNumber":"%s",
                 "start":"2027-03-10T01:00:00Z","end":"2027-03-10T04:00:00Z"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), "S".repeat(65));

        mvc.perform(post("/api/equipment/reservations").with(flow.as("ts1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM equipment_logs", Integer.class)).isEqualTo(before);
    }
}
