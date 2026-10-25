package com.example.connect_sphere.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.connect_sphere.equipment.DevEquipmentSeeder.SeedEquipment;
import com.example.connect_sphere.equipment.DevEquipmentSeeder.SerialNumberPattern;

@Tag("unit")
@Tag("TS09")
class DevEquipmentSeederTest {

    private static final RandomGenerator RANDOM = new java.util.Random(42);

    @Test
    @DisplayName("TS09 (happy): a serial number is the kind's prefix followed by random characters of the right type")
    void generatesPrefixAndPlaceholders() {
        SerialNumberPattern pattern = new SerialNumberPattern("EPS-EBU04-", "#AXH");

        for (int i = 0; i < 200; i++) {
            String serial = pattern.generate(RANDOM);
            assertThat(serial).hasSize(14).startsWith("EPS-EBU04-");
            assertThat(serial.substring(10)).matches("[0-9][A-HJ-NP-Z][0-9A-HJ-NP-Z][0-9A-F]");
        }
    }

    @Test
    @DisplayName("TS09 (boundary): a format of exactly 64 characters is accepted and one of 65 is refused")
    void patternLengthLimit() {
        assertThat(new SerialNumberPattern("P".repeat(63), "#").length()).isEqualTo(64);

        assertThatThrownBy(() -> new SerialNumberPattern("P".repeat(64), "#"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
    }

    @Test
    @DisplayName("TS09 (negative): a format with no random part or an unknown placeholder is refused")
    void invalidPatterns() {
        assertThatThrownBy(() -> new SerialNumberPattern("EPS-", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SerialNumberPattern("EPS-", "##Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'Z'");
    }

    @Test
    @DisplayName("TS09 (happy): every seeded kind has a unique key and name, and serialised kinds fit VARCHAR(64)")
    void catalogueIsConsistent() {
        List<SeedEquipment> seeds = DevEquipmentSeeder.SEED_EQUIPMENT;

        assertThat(seeds).extracting(SeedEquipment::key).doesNotHaveDuplicates();
        assertThat(seeds).extracting(SeedEquipment::name).doesNotHaveDuplicates();
        assertThat(seeds).allSatisfy(seed -> assertThat(seed.quantity()).isPositive());
        assertThat(seeds).filteredOn(SeedEquipment::serialised).allSatisfy(seed -> assertThat(
                seed.serialPattern().length()).isLessThanOrEqualTo(SerialisedEquipmentId.MAX_SERIAL_NUMBER_LENGTH));
    }

    @Test
    @DisplayName("TS09 (cross-cutting): the original nine kinds keep their keys and names, so their IDs do not change")
    void originalKindsAreUnchanged() {
        Map<String, String> namesByKey = DevEquipmentSeeder.SEED_EQUIPMENT.stream()
                .collect(Collectors.toMap(SeedEquipment::key, SeedEquipment::name));

        assertThat(namesByKey).containsAllEntriesOf(Map.of(
                "projector", "Projector",
                "microphone", "Wireless Microphone",
                "speaker", "Portable Speaker",
                "mixer", "Audio Mixer",
                "laptop", "Laptop",
                "webcam", "Webcam",
                "hdmi-cable", "HDMI Cable",
                "extension-cord", "Extension Cord",
                "tripod", "Camera Tripod"));
        assertThat(DevEquipmentSeeder.equipmentId("projector"))
                .isEqualTo(java.util.UUID.nameUUIDFromBytes("csphere-dev-equipment:projector".getBytes()));
    }

    @Test
    @DisplayName("TS09 (happy): readers and scanners are tracked by quantity; units such as iPads are serialised")
    void serialisedFlags() {
        Map<String, Boolean> serialisedByName = DevEquipmentSeeder.SEED_EQUIPMENT.stream()
                .collect(Collectors.toMap(SeedEquipment::name, SeedEquipment::serialised));

        assertThat(serialisedByName).containsAllEntriesOf(Map.of(
                "NFC Reader", false,
                "Barcode Scanner", false,
                "Power Bank", false,
                "iPad", true,
                "Badge Printer", true,
                "Mobile Wi-Fi", true,
                "Walkie-Talkie", true));
    }

    @Test
    @DisplayName("TS09 (happy): a kind is topped up only with the units it is missing")
    void topUpInsertsOnlyTheMissingUnits() {
        List<String> inserted = new ArrayList<>();

        int count = DevEquipmentSeeder.topUp(4, 6, counter("S"), inserted::add, "projector");

        assertThat(count).isEqualTo(2);
        assertThat(inserted).containsExactly("S1", "S2");
    }

    @Test
    @DisplayName("TS09 (boundary): a kind that already has all its units, or more, gets nothing new")
    void topUpDoesNothingWhenFull() {
        List<String> inserted = new ArrayList<>();

        assertThat(DevEquipmentSeeder.topUp(6, 6, counter("S"), inserted::add, "projector")).isZero();
        assertThat(DevEquipmentSeeder.topUp(9, 6, counter("S"), inserted::add, "projector")).isZero();
        assertThat(inserted).isEmpty();
    }

    @Test
    @DisplayName("TS09 (conflict): a serial number that is already taken is skipped and another is generated")
    void topUpRetriesOnCollision() {
        Set<String> taken = new HashSet<>(Set.of("S1", "S2"));
        Iterator<String> serials = List.of("S1", "S3", "S2", "S4").iterator();

        int count = DevEquipmentSeeder.topUp(2, 4, serials::next, taken::add, "projector");

        assertThat(count).isEqualTo(2);
        assertThat(taken).containsExactlyInAnyOrder("S1", "S2", "S3", "S4");
    }

    @Test
    @DisplayName("TS09 (failure): seeding stops with a clear error when unique serial numbers cannot be found")
    void topUpGivesUp() {
        int[] attempts = {0};

        assertThatThrownBy(() -> DevEquipmentSeeder.topUp(0, 2, () -> "SAME",
                serial -> attempts[0]++ == 0, "projector"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projector");
        assertThat(attempts[0]).isEqualTo(2 * DevEquipmentSeeder.ATTEMPTS_PER_UNIT);
    }

    private static java.util.function.Supplier<String> counter(String prefix) {
        int[] next = {0};
        return () -> prefix + ++next[0];
    }
}
