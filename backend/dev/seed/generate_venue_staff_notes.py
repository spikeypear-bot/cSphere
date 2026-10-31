"""DEV16: generate a reviewable, preservation-safe correction of venue staff notes.

The original venue payload and V60/V61/V62 remain immutable. After V64 is applied,
use --check only; further corrections require a new migration.
"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
NOTES = {
    'SMU Yong Pung How School of Law – Seminar Room 3-09, Level 3':
        'Level 3, Seminar Room 3-09. Teaching and seminar space. Confirm any campus security access requirements before the event.',
    'SMU Yong Pung How School of Law – SMU Hall 3, Basement 1':
        'Basement 1, SMU Hall 3. This hall is booked separately from the Level 3 seminar rooms.',
    'NUS University Cultural Centre – Ho Bee Auditorium':
        'Fixed auditorium seating. The published standard configuration seats 1,607 people, including wheelchair spaces. Larger configurations require separate arrangements with the venue operator.',
    'NUS University Cultural Centre – Theatre':
        'The theatre seats 425 people, including six wheelchair spaces. A flat-floor configuration requires approval from the venue operator.',
    'NUS University Cultural Centre – Function Room 1, Level 1':
        'Level 1 function room, supplied unfurnished. The published theatre-style maximum is 50 people. Arrange the required furniture and room setup before the event.',
    'NTU – Nanyang Auditorium Theatre':
        'Main theatre in Nanyang Auditorium. Published seating capacity is 1,526 people, including two designated wheelchair spaces.',
    'Lifelong Learning Institute – Training Room 1-1, Level 1':
        'Level 1, Training Room 1-1. Published capacity guidance is 30–50 people. Confirm the capacity for the requested seating layout before approval.',
    'Lifelong Learning Institute – Event Hall 1-1, Level 1':
        'Level 1, next to Hall 1-2. Published guidance is approximately 90–100 people in classroom seating or 150–180 in theatre seating. The recorded planning limit is 90 people. Confirm the layout and any combined-hall arrangements with the venue operator before approval.',
    'Lifelong Learning Institute – Lecture Theatre':
        'Dedicated lecture theatre with a published seating capacity of 267 people.',
    'Singapore Chinese Cultural Centre – Auditorium, Levels 9–10':
        'Performing arts auditorium on Levels 9–10. Published seating capacity is 533 people.',
    'Singapore Chinese Cultural Centre – Multi-Purpose Hall, Level 7':
        'Level 7 multi-purpose hall. Published layout guidance is 300 people in classroom seating, or 550 in theatre or banquet seating. The recorded planning limit is 300 people. Confirm the capacity for the requested layout before approval.',
    'Singapore Chinese Cultural Centre – Creative Box, Level 6':
        'Flexible event space on Level 6. The published maximum is 120 people; the recorded planning limit is 100. Confirm the final setup and capacity with the venue operator.',
    'Esplanade – Concert Hall':
        'Published main-house seating is 1,628 people, excluding 197 additional gallery seats. Coordinate production access and any gallery seating requirements with the venue operator.',
    'Esplanade – Recital Studio, Level 2':
        'Flexible studio on Level 2, with 245 seats on retractable bleachers. Confirm the setup and capacity with the venue operator if a different seating arrangement is required.',
    'The Star Performing Arts Centre – The Star Theatre':
        'The theatre occupies Levels 5–10, with a published full-house capacity of 5,000 people. This listing covers the full theatre. Confirm any plans to use selected seating tiers with the venue operator.',
    'Singapore EXPO – Exhibition Hall 1':
        'Exhibition Hall 1. The venue operator quotes up to 9,000 people per hall. The recorded 6,000-person planning limit is illustrative and is not an operator-approved capacity for a specific layout. Confirm the layout and capacity with the venue operator before approving a booking.',
    'Sands Expo & Convention Centre – Sands Grand Ballroom':
        'Some ballroom configurations accommodate up to 8,000 guests. The recorded 5,000-person planning limit is illustrative. Confirm the approved capacity for the requested layout with the venue operator.',
    'Suntec Singapore Convention & Exhibition Centre – Hall 401, Level 4':
        'Event hall on Level 4. The recorded 1,200-person planning limit has not been confirmed by the venue operator. Confirm the capacity for the requested layout. Combined halls require separate confirmation of availability and booking arrangements.',
    'Victoria Theatre & Concert Hall – Victoria Concert Hall':
        'Concert hall within the Victoria Theatre & Concert Hall complex, with published seating for 673 people.',
    'The Arts House at the Old Parliament – Chamber':
        'Former parliamentary Chamber, with published seating for 187 people. Accessibility provisions have not been recorded; confirm them with the venue operator when reviewing accessibility requirements.',
}


def generate():
    venues = json.loads((ROOT / 'venues.json').read_text(encoding='utf-8'))
    coordinates = json.loads((ROOT / 'venue_coordinates.json').read_text(encoding='utf-8'))
    identities = {(v['venueName'], v['venueAddress']): v['venueId'] for v in coordinates}
    if len(venues) != 20 or set(NOTES) != {v['venueName'] for v in venues}:
        raise ValueError('Staff notes must cover exactly the 20 original venues')
    rows = [dict(venueId=identities[v['venueName'], v['venueAddress']], venueName=v['venueName'],
                 venueAddress=v['venueAddress'], previousAdditionalInformation=v['additionalInformation'],
                 additionalInformation=NOTES[v['venueName']]) for v in venues]
    payload = json.dumps(rows, ensure_ascii=False, indent=2) + '\n'
    quote = lambda value: "'" + value.replace("'", "''") + "'"
    values = ',\n'.join('    (' + ', '.join(quote(r[k]) for k in (
        'venueId', 'venueName', 'venueAddress', 'previousAdditionalInformation', 'additionalInformation')) + ')' for r in rows)
    sql = """-- DEV16: venue-facing planning notes. V60/V61/V62 and the original payload are unchanged.
-- Match one original room by identity, including retained legacy UUIDs. Never overwrite
-- staff-edited notes, renamed/readdressed rooms, or ambiguous duplicate identities.
WITH replacements(seed_id, venue_name, venue_address, previous_note, staff_note) AS (
  VALUES
""" + values + r"""
), candidates AS (
  SELECT v.venue_id, r.previous_note, r.staff_note,
         count(*) OVER (PARTITION BY r.seed_id) AS identity_matches
  FROM venues v JOIN replacements r
    ON lower(regexp_replace(btrim(v.venue_address), '\s+', ' ', 'g')) =
       lower(regexp_replace(btrim(r.venue_address), '\s+', ' ', 'g'))
   AND (lower(regexp_replace(btrim(v.venue_name), '\s+', ' ', 'g')) =
        lower(regexp_replace(btrim(r.venue_name), '\s+', ' ', 'g'))
        OR lower(regexp_replace(btrim(v.venue_name), '\s+', ' ', 'g')) =
           lower(regexp_replace(btrim(r.venue_address), '\s+', ' ', 'g')))
)
UPDATE venues v SET additional_information = c.staff_note
FROM candidates c
WHERE v.venue_id = c.venue_id AND c.identity_matches = 1
  AND v.additional_information IS NOT DISTINCT FROM c.previous_note;
"""
    return {ROOT / 'venue_staff_notes.json': payload,
            ROOT.parent.parent / 'src/main/resources/db/migration/V64__venue_staff_planning_notes.sql': sql}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for path, content in generate().items():
        if args.check:
            if not path.exists() or path.read_text(encoding='utf-8') != content:
                raise SystemExit(f'Generated staff notes differ: {path.name}')
        else:
            path.write_text(content, encoding='utf-8', newline='\n')
    print('20 staff-facing notes: JSON and V64 SQL agree' if args.check else 'Generated 20 staff-facing notes and V64 SQL')


if __name__ == '__main__':
    main()
