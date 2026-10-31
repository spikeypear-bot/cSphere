# @stories DEV16
"""DEV16 coordinate mapping validation; Python standard library only."""
import copy
import hashlib
import json
import unittest

import generate_venue_coordinates as mapping


class VenueCoordinateGeneratorTest(unittest.TestCase):
    def setUp(self):
        self.raw = mapping.SOURCE.read_bytes()
        self.rows = json.loads(self.raw)
        self.venues = json.loads((mapping.HERE / 'venues.json').read_text(encoding='utf-8-sig'))

    def test_all_rooms_are_mapped_without_source_urls_or_other_payload_changes(self):
        """DEV16 (happy): every existing room has one coordinate pair and unchanged identity."""
        mapping.validate(self.rows, self.venues)
        self.assertEqual(20, len(self.rows))
        self.assertEqual(20, len(set(r['venueId'] for r in self.rows)))
        self.assertEqual(13, len(set((r['latitude'], r['longitude']) for r in self.rows)))
        self.assertNotIn('http', self.raw.decode('utf-8'))
        self.assertEqual(mapping.OUTPUT.read_text(encoding='utf-8'),
                         mapping.render(self.rows, self.venues, hashlib.sha256(self.raw).hexdigest()))

    def test_precision_notes_and_original_mailing_address_are_retained(self):
        """DEV16 (boundary): building/complex pins do not claim measured room precision."""
        expo = next(r for r in self.rows if r['venueName'].startswith('Singapore EXPO'))
        self.assertEqual('complex-only', expo['locationGranularity'])
        self.assertIn('not a measured Hall 1 coordinate', expo['verificationNote'])
        victoria = next(r for r in self.rows if r['venueName'].startswith('Victoria Theatre'))
        self.assertTrue(victoria['venueAddress'].endswith('9 Empress Place, Singapore 179556'))
        self.assertIn('11 Empress Place', victoria['verificationNote'])

    def test_invalid_coordinates_identity_and_unwanted_fields_are_rejected(self):
        """DEV16 (negative): malformed references cannot generate a coordinate update."""
        cases = [('latitude', None), ('latitude', True), ('latitude', float('nan')),
                 ('latitude', 90.01), ('longitude', -180.01), ('longitude', float('inf')),
                 ('venueName', 'Unmatched room'), ('venueAddress', ''), ('venueId', 'not-a-uuid'),
                 ('venueId', '00000000-0000-0000-0000-000000000000'),
                 ('locationGranularity', None), ('verificationNote', 123), ('coordinateSource', 'ignored')]
        for field, value in cases:
            with self.subTest(field=field, value=value):
                rows = copy.deepcopy(self.rows)
                rows[0][field] = value
                with self.assertRaises(ValueError):
                    mapping.render(rows, self.venues, 'test')

    def test_missing_and_duplicate_room_mappings_are_rejected(self):
        """DEV16 (conflict): shared coordinates never collapse separate room mappings."""
        for rows in [[], self.rows[:-1], self.rows + [copy.deepcopy(self.rows[0])]]:
            with self.assertRaises(ValueError):
                mapping.validate(rows, self.venues)
        self.assertEqual(self.rows[0]['latitude'], self.rows[1]['latitude'])
        self.assertEqual(self.rows[0]['longitude'], self.rows[1]['longitude'])
        self.assertNotEqual(self.rows[0]['venueId'], self.rows[1]['venueId'])


if __name__ == '__main__':
    unittest.main()
