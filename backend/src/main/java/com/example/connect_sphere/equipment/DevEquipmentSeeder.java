package com.example.connect_sphere.equipment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dev-only equipment catalogue. Runs on every boot and is idempotent: a kind is
 * inserted only when absent, and a serialised kind is topped up with random
 * serial numbers (one {@link SerialNumberPattern} per kind) until it has as many
 * units as its quantity, so restarting adds nothing. Existing rows, including
 * earlier {@code DEMO-…} serials, are never changed.
 */
@Component
@Profile("dev")
public class DevEquipmentSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevEquipmentSeeder.class);
    private static final char GENERAL_EQUIPMENT_TYPE = 'E';

    /** Gives up on a kind after this many collisions per missing unit. */
    static final int ATTEMPTS_PER_UNIT = 20;

    // Keys must not change: each kind's UUID is derived from its key.
    static final List<SeedEquipment> SEED_EQUIPMENT = List.of(
            // Audiovisual
            serialised("projector", "Projector", 6, "EPS-EBU04-", "XXXXXX"),
            serialised("microphone", "Wireless Microphone", 16, "SHR-SLXD2-", "#######"),
            serialised("speaker", "Portable Speaker", 8, "JBL-EON710-", "#######"),
            serialised("mixer", "Audio Mixer", 3, "YMH-MG12XU-", "AAA####"),
            serialised("walkie-talkie", "Walkie-Talkie", 20, "MOT-T5320-", "HHHHHH"),
            serialised("confidence-monitor", "Confidence Monitor", 4, "VSN-VA2432-", "XXXXXXXX"),
            bulk("projection-screen", "Projection Screen", 6),
            bulk("wireless-presenter", "Wireless Presenter", 12),
            bulk("lectern-microphone", "Lectern Microphone", 6),
            // Registration
            serialised("ipad", "iPad", 12, "", "XXXXXXXXXXXX"),
            serialised("badge-printer", "Badge Printer", 4, "ZBR-ZC300-", "##########"),
            bulk("ipad-desk-stand", "iPad Desk Stand", 20),
            bulk("ipad-floor-stand", "iPad Floor Stand", 10),
            bulk("badge-lanyards", "Badge Lanyards", 30),
            bulk("badge-printer-cards", "Badge Printer Cards", 20),
            // Connectivity
            serialised("mobile-wifi", "Mobile Wi-Fi", 10, "86", "#############"),
            serialised("portable-router", "Portable Router", 4, "GLI-MT3000-", "HHHHHHHHHHHH"),
            // Computing and streaming
            serialised("laptop", "Laptop", 8, "LNV-T14-PF", "XXXXXX"),
            bulk("webcam", "Webcam", 8),
            bulk("tripod", "Camera Tripod", 6),
            bulk("ring-light", "Ring Light", 4),
            // Accessories
            bulk("hdmi-cable", "HDMI Cable", 30),
            bulk("extension-cord", "Extension Cord", 20),
            bulk("power-bank", "Power Bank", 30),
            bulk("nfc-reader", "NFC Reader", 12),
            bulk("barcode-scanner", "Barcode Scanner", 12),
            bulk("usb-c-charging-hub", "USB-C Charging Hub", 10),
            bulk("hdmi-usb-c-adapter", "HDMI/USB-C Adapter", 20));

    private final JdbcTemplate jdbc;
    private final RandomGenerator random = RandomGenerator.getDefault();

    public DevEquipmentSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(String... args) {
        int created = 0;
        for (SeedEquipment seed : SEED_EQUIPMENT) {
            UUID equipmentId = equipmentId(seed.key());

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

            if (Boolean.TRUE.equals(state.serialised()) && seed.serialPattern() != null) {
                created += seedSerialNumbers(equipmentId, seed, state.quantity());
            }
        }

        if (created > 0) {
            log.info("Dev equipment seed: added catalogue entries or serialised units.");
        } else {
            log.info("Dev equipment seed: all demo entries already present.");
        }
    }

    static UUID equipmentId(String key) {
        return UUID.nameUUIDFromBytes(("csphere-dev-equipment:" + key).getBytes(StandardCharsets.UTF_8));
    }

    private int seedSerialNumbers(UUID equipmentId, SeedEquipment seed, int quantity) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM serialised_equipments WHERE equipment_id = ?", Integer.class, equipmentId);
        return topUp(existing == null ? 0 : existing, quantity,
                () -> seed.serialPattern().generate(random),
                serial -> jdbc.update("""
                        INSERT INTO serialised_equipments (equipment_id, serial_number, status)
                        VALUES (?, ?, ?::equipment_status)
                        ON CONFLICT (equipment_id, serial_number) DO NOTHING
                        """, equipmentId, serial, EquipmentStatus.AVAILABLE.name()) == 1,
                seed.key());
    }

    /**
     * Inserts {@code target - existing} new serial numbers (none if the kind
     * already has enough units). {@code insert} returns false when the serial is
     * already taken, and another one is generated.
     *
     * @return how many units were inserted
     */
    static int topUp(int existing, int target, Supplier<String> nextSerial, Predicate<String> insert, String key) {
        int missing = target - existing;
        if (missing <= 0) {
            return 0;
        }
        int inserted = 0;
        int attemptsLeft = missing * ATTEMPTS_PER_UNIT;
        while (inserted < missing) {
            if (attemptsLeft-- == 0) {
                throw new IllegalStateException("Could not generate " + missing
                        + " unique serial numbers for " + key + "; its serial format may be too small");
            }
            if (insert.test(nextSerial.get())) {
                inserted++;
            }
        }
        return inserted;
    }

    private static SeedEquipment serialised(String key, String name, int quantity, String prefix, String randomPart) {
        return new SeedEquipment(key, name, quantity, new SerialNumberPattern(prefix, randomPart));
    }

    private static SeedEquipment bulk(String key, String name, int quantity) {
        return new SeedEquipment(key, name, quantity, null);
    }

    /** {@code serialPattern} is null for kinds tracked by quantity only. */
    record SeedEquipment(String key, String name, int quantity, SerialNumberPattern serialPattern) {
        boolean serialised() {
            return serialPattern != null;
        }
    }

    private record SeedState(int quantity, Boolean serialised) {}

    /**
     * A serial-number format for one kind of serialised equipment (TS09): a fixed
     * prefix followed by a random part, e.g. {@code "EPS-EBU04-"} + {@code "XXXXXX"}
     * gives {@code EPS-EBU04-7K2Q9M} for a projector. Each character of the random
     * part is a placeholder:
     * <ul>
     *   <li>{@code #} a digit</li>
     *   <li>{@code A} an upper-case letter</li>
     *   <li>{@code X} a digit or upper-case letter</li>
     *   <li>{@code H} a hexadecimal digit (upper case)</li>
     * </ul>
     * Letters that are easily misread on a label ({@code I}, {@code O}) are never
     * generated. Different kinds have different formats, so serials are only
     * assumed unique within a kind, matching the
     * {@code (equipment_id, serial_number)} primary key.
     */
    record SerialNumberPattern(String prefix, String randomPart) {

        private static final String DIGITS = "0123456789";
        private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        private static final String ALPHANUMERIC = DIGITS + LETTERS;
        private static final String HEX = "0123456789ABCDEF";

        SerialNumberPattern {
            if (prefix == null) {
                prefix = "";
            }
            if (randomPart == null || randomPart.isEmpty()) {
                throw new IllegalArgumentException("A serial number needs a random part");
            }
            for (char c : randomPart.toCharArray()) {
                if (alphabetFor(c) == null) {
                    throw new IllegalArgumentException(
                            "Unknown placeholder '" + c + "' in random part " + randomPart);
                }
            }
            if (prefix.length() + randomPart.length() > SerialisedEquipmentId.MAX_SERIAL_NUMBER_LENGTH) {
                throw new IllegalArgumentException("Serial numbers would be longer than "
                        + SerialisedEquipmentId.MAX_SERIAL_NUMBER_LENGTH + " characters: " + prefix + randomPart);
            }
        }

        String generate(RandomGenerator random) {
            StringBuilder serial = new StringBuilder(prefix);
            for (char c : randomPart.toCharArray()) {
                String alphabet = alphabetFor(c);
                serial.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            return serial.toString();
        }

        /** Length of every serial number this pattern generates. */
        int length() {
            return prefix.length() + randomPart.length();
        }

        private static String alphabetFor(char placeholder) {
            return switch (placeholder) {
                case '#' -> DIGITS;
                case 'A' -> LETTERS;
                case 'X' -> ALPHANUMERIC;
                case 'H' -> HEX;
                default -> null;
            };
        }
    }
}
