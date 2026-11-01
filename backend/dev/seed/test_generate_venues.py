# @stories DEV16
"""DEV16 generator checks. Run with python -m unittest discover -s backend/dev/seed."""
import copy
import hashlib
import json
import unittest

import generate_venues as seed


class VenueSeedGeneratorTest(unittest.TestCase):
    def setUp(self):
        self.raw = (seed.HERE / 'venues.json').read_bytes()
        self.rows = json.loads(self.raw.decode('utf-8-sig'))

    def test_source_and_generated_migration_agree(self):
        """DEV16 (happy): all 20 supplied venues produce the checked-in SQL."""
        self.assertEqual(20, len(self.rows))
        self.assertEqual(129, sum(len(r['operatingHours']) for r in self.rows))
        self.assertEqual(seed.OUTPUT.read_text(encoding='utf-8'),
                         seed.render(self.rows, hashlib.sha256(self.raw).hexdigest()))

    def test_identities_survive_reordering_and_characteristic_changes(self):
        """DEV16 (boundary): identities do not depend on ordering, capacities or hours."""
        before = seed.venue_id(self.rows[0])
        hour = seed.hour_id(self.rows[0], 1)
        row = copy.deepcopy(self.rows[0])
        row['venueCapacity'] += 1
        row['operatingHours'][0]['openTime'] = '10:00'
        row['venueName'] = '  ' + row['venueName'].upper() + ' '
        self.assertEqual(before, seed.venue_id(row))
        self.assertEqual(hour, seed.hour_id(row, 1))
        self.assertNotEqual(hour, seed.hour_id(row, 2))
        self.assertEqual({seed.venue_id(r) for r in self.rows},
                         {seed.venue_id(r) for r in reversed(self.rows)})

    def test_rooms_at_same_address_and_coordinates_remain_distinct(self):
        """DEV16 (happy): a shared building and coordinates never collapse rooms."""
        rows = copy.deepcopy(self.rows[:2])
        for row in rows:
            row.update(venueAddress='55 Armenian Street', latitude=1.3, longitude=103.8)
        seed.validate(rows)
        self.assertNotEqual(seed.venue_id(rows[0]), seed.venue_id(rows[1]))

    def test_invalid_values_are_rejected_before_sql_is_generated(self):
        """DEV16 (negative): reject malformed, out-of-range and incompatible data."""
        bad = [('venueName', ''), ('venueAddress', 'x' * 501), ('venueCapacity', 0),
               ('venueCapacity', 50001), ('venueCapacity', 2.5), ('venueCapacity', True),
               ('supportedLayouts', []), ('supportedLayouts', ['unknown']),
               ('supportedLayouts', ['theatre', 'theatre']), ('venueFacilities', [None]),
               ('venueAccessibilities', ['none', 'elevators']), ('setupMinutes', -1),
               ('turnaroundMinutes', 10081), ('latitude', float('nan')),
               ('latitude', 90.001), ('longitude', -180.001), ('longitude', True),
               ('operatingHours', []), ('additionalInformation', 123)]
        for field, value in bad:
            with self.subTest(field=field, value=value):
                rows = copy.deepcopy(self.rows)
                rows[0][field] = value
                with self.assertRaises(ValueError):
                    seed.render(rows, 'test')
        for hours in [[{'dayOfWeek': 0, 'openTime': '09:00', 'closeTime': '18:00'}],
                      [{'dayOfWeek': 1, 'openTime': '24:00', 'closeTime': '25:00'}],
                      [{'dayOfWeek': 1, 'openTime': '18:00', 'closeTime': '09:00'}],
                      [self.rows[0]['operatingHours'][0]] * 2, [None]]:
            with self.subTest(hours=hours):
                rows = copy.deepcopy(self.rows)
                rows[0]['operatingHours'] = hours
                with self.assertRaises(ValueError):
                    seed.render(rows, 'test')

    def test_duplicate_room_identity_and_unknown_fields_are_rejected(self):
        """DEV16 (conflict): duplicate identities and unmapped fields are actionable errors."""
        with self.assertRaisesRegex(ValueError, 'duplicate room'):
            seed.validate(self.rows + [copy.deepcopy(self.rows[0])])
        self.rows[0]['unmappedField'] = 'not silently discarded'
        with self.assertRaisesRegex(ValueError, 'unknown fields'):
            seed.validate(self.rows)

    def test_missing_coordinates_and_unknown_accessibility_stay_unrecorded(self):
        """DEV16 (boundary): missing information is preserved, never invented."""
        self.assertTrue(all(r.get('latitude') is None and r.get('longitude') is None for r in self.rows))
        self.assertEqual([], self.rows[-1]['venueAccessibilities'])
        seed.validate(self.rows)
        for latitude, longitude in [(-90, -180), (90, 180), (None, None)]:
            self.rows[0].update(latitude=latitude, longitude=longitude)
            seed.validate(self.rows)


if __name__ == '__main__':
    unittest.main()
