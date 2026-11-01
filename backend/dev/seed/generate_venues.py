"""DEV16: validate the source and generate V61. No database connection is used.

Once V61 has been applied anywhere, never regenerate it to change data. Use a
new reviewed migration instead. --check is safe to run after application.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import uuid

HERE = Path(__file__).resolve().parent
OUTPUT = HERE.parents[1] / 'src/main/resources/db/migration/V61__seed_shared_venues.sql'
NAMESPACE = uuid.UUID('16c40f49-ddc5-5c95-a3c9-d5240d4e9d28')
LAYOUTS = {'classroom', 'theatre', 'boardroom', 'banquet', 'exhibition'}
ACCESS = {'accessible_parking', 'drop_off_zone', 'public_transport', 'step_free_access',
          'wide_doorways', 'elevators', 'wheelchair_support', 'none'}
FACILITIES = {'audio_visual_equipment', 'air_conditioning', 'breakout_spaces',
              'projection', 'stage', 'dining_area', 'barbeque_pit'}
DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
REQUIRED = {'venueName', 'venueAddress', 'venueCapacity', 'supportedLayouts',
            'venueAccessibilities', 'venueFacilities', 'operatingHours',
            'setupMinutes', 'turnaroundMinutes', 'additionalInformation'}


def identity(value):
    # Mirrors lower(regexp_replace(btrim(...), '[[:space:]]+', ' ', 'g')) in SQL.
    return re.sub(r'\s+', ' ', value.strip()).lower()


def venue_id(row):
    key = json.dumps([identity(row['venueName']), identity(row['venueAddress'])], ensure_ascii=False, separators=(',', ':'))
    return uuid.uuid5(NAMESPACE, 'venue:' + key)


def hour_id(row, day):
    return uuid.uuid5(venue_id(row), 'day:' + str(day))


def validate(rows):
    errors = []
    if not isinstance(rows, list) or not rows:
        raise ValueError('Expected a nonempty array of venues')
    identities = set()
    for index, row in enumerate(rows, 1):
        def error(message):
            errors.append(f'venue {index}: {message}')
        if not isinstance(row, dict):
            error('expected an object')
            continue
        if set(row) - REQUIRED - {'latitude', 'longitude'}:
            error('unknown fields: ' + ', '.join(sorted(set(row) - REQUIRED - {'latitude', 'longitude'})))
        for field in REQUIRED - {'additionalInformation'}:
            if field not in row or row[field] is None:
                error(f'missing {field}')
        for field, maximum in [('venueName', 200), ('venueAddress', 500)]:
            value = row.get(field)
            if not isinstance(value, str) or not value.strip() or len(value.strip()) > maximum:
                error(f'{field} must contain 1..{maximum} characters')
        if all(isinstance(row.get(f), str) for f in ('venueName', 'venueAddress')):
            key = (identity(row['venueName']), identity(row['venueAddress']))
            if key in identities:
                error('duplicate room name/address identity')
            identities.add(key)
        for field, low, high in [('venueCapacity', 1, 50000), ('setupMinutes', 0, 10080),
                                 ('turnaroundMinutes', 0, 10080)]:
            value = row.get(field)
            if type(value) is not int or not low <= value <= high:
                error(f'{field} must be a whole number in {low}..{high}')
        for field, allowed in [('supportedLayouts', LAYOUTS), ('venueAccessibilities', ACCESS),
                               ('venueFacilities', FACILITIES)]:
            values = row.get(field)
            if not isinstance(values, list) or any(not isinstance(v, str) or v not in allowed for v in values):
                error(f'invalid {field} selections')
            elif len(values) != len(set(values)) or (field == 'supportedLayouts' and not values):
                error(f'{field} contains duplicates or is empty')
            elif field == 'venueAccessibilities' and 'none' in values and len(values) != 1:
                error('accessibility none must be exclusive')
        if row.get('additionalInformation') is not None and not isinstance(row['additionalInformation'], str):
            error('additionalInformation must be text or null')
        hours = row.get('operatingHours')
        if not isinstance(hours, list) or not hours:
            error('operatingHours requires at least one day')
        else:
            seen_days = set()
            for hour in hours:
                if not isinstance(hour, dict) or set(hour) != {'dayOfWeek', 'openTime', 'closeTime'}:
                    error('invalid operating-hours fields')
                    continue
                day = hour['dayOfWeek']
                if type(day) is not int or day not in range(1, 8):
                    error('dayOfWeek must be 1..7')
                elif day in seen_days:
                    error('duplicate operating day')
                else:
                    seen_days.add(day)
                times = [hour['openTime'], hour['closeTime']]
                if any(not isinstance(t, str) or not re.fullmatch(r'(?:[01]\d|2[0-3]):[0-5]\d', t) for t in times):
                    error('hours must use HH:mm (00:00..23:59)')
                elif times[0] >= times[1]:
                    error('closeTime must follow openTime; overnight hours are unsupported')
        for field, bound in [('latitude', 90), ('longitude', 180)]:
            value = row.get(field)
            if value is not None and (type(value) not in (int, float) or not math.isfinite(value) or not -bound <= value <= bound):
                error(f'{field} must be null or a finite number in {-bound}..{bound}')
    if errors:
        raise ValueError('\n'.join(errors))


def literal(value):
    if value is None:
        return 'NULL'
    if isinstance(value, str):
        return "'" + value.replace("'", "''") + "'"
    return str(value)


def array(values, pg_type):
    return 'ARRAY[' + ','.join(map(literal, values)) + ']::' + pg_type + '[]'


def render(rows, source_hash):
    validate(rows)
    venues, hours = [], []
    for row in rows:
        schedule = sorted(row['operatingHours'], key=lambda h: h['dayOfWeek'])
        summary = '; '.join(f"{DAYS[h['dayOfWeek']-1]} {h['openTime']}–{h['closeTime']}" for h in schedule)
        values = [literal(str(venue_id(row))), literal(row['venueName']), literal(row['venueAddress']),
                  literal(row['venueCapacity']), array(row['supportedLayouts'], 'text'),
                  array(row['venueAccessibilities'], 'accessibilities'), array(row['venueFacilities'], 'facilities'),
                  literal(row['setupMinutes']), literal(row['turnaroundMinutes']), literal(summary),
                  literal(row.get('additionalInformation')), literal(row.get('latitude')), literal(row.get('longitude'))]
        venues.append('    (' + ', '.join(values) + ')')
        for h in schedule:
            hours.append('    (' + ', '.join(map(literal, [str(hour_id(row, h['dayOfWeek'])),
                         str(venue_id(row)), h['dayOfWeek'], h['openTime'], h['closeTime']])) + ')')
    template = (HERE / 'venue_seed.sql.template').read_text(encoding='utf-8')
    return template.replace('@SOURCE_HASH@', source_hash).replace('@VENUES@', ',\n'.join(venues)).replace('@HOURS@', ',\n'.join(hours))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='validate and compare without writing')
    args = parser.parse_args()
    raw = (HERE / 'venues.json').read_bytes()
    rows = json.loads(raw.decode('utf-8-sig'))
    try:
        sql = render(rows, hashlib.sha256(raw).hexdigest())
    except ValueError as error:
        parser.exit(1, str(error) + '\nNo SQL was written.\n')
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding='utf-8') != sql:
            parser.exit(1, 'Generated SQL differs from source/template; review before applying.\n')
    else:
        OUTPUT.write_text(sql, encoding='utf-8', newline='\n')
    print(f"DEV16: validated {len(rows)} venues, {sum(len(r['operatingHours']) for r in rows)} hours; "
          f"{sum(r.get('latitude') is None or r.get('longitude') is None for r in rows)} missing coordinate pairs; "
          f"SQL {'matches' if args.check else 'generated'}. No database was changed.")


if __name__ == '__main__':
    main()
