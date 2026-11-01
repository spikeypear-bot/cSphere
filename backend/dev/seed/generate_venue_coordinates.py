"""DEV16: validate coordinate mappings and construct the self-contained V62 SQL.

V60/V61 and venues.json stay immutable. After V62 is applied, use --check only;
any later coordinate correction needs another reviewed migration.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import uuid

from generate_venues import identity, literal, venue_id

HERE = Path(__file__).resolve().parent
SOURCE = HERE / 'venue_coordinates.json'
OUTPUT = HERE.parents[1] / 'src/main/resources/db/migration/V62__map_existing_venue_coordinates.sql'
FIELDS = {'venueId', 'venueName', 'venueAddress', 'latitude', 'longitude',
          'locationGranularity', 'verificationNote'}


def validate(rows, venues):
    if not isinstance(rows, list) or not rows:
        raise ValueError('Expected a nonempty coordinate mapping array')
    expected = {(identity(v['venueName']), identity(v['venueAddress'])): v for v in venues}
    seen = set()
    errors = []
    for index, row in enumerate(rows, 1):
        if not isinstance(row, dict) or set(row) != FIELDS:
            errors.append(f'mapping {index}: expected only {sorted(FIELDS)}; URLs are not stored')
            continue
        if any(not isinstance(row[f], str) or not row[f].strip() for f in ('venueName', 'venueAddress')):
            errors.append(f'mapping {index}: room name and address must be nonblank text')
            continue
        key = (identity(row['venueName']), identity(row['venueAddress']))
        if key in seen:
            errors.append(f'mapping {index}: duplicate room identity')
        seen.add(key)
        if key not in expected:
            errors.append(f'mapping {index}: name/address does not match original venues.json')
        else:
            try:
                if uuid.UUID(row['venueId']) != venue_id(expected[key]):
                    errors.append(f'mapping {index}: deterministic venue UUID mismatch')
            except (ValueError, TypeError, AttributeError):
                errors.append(f'mapping {index}: invalid venue UUID')
        for field, bound in [('latitude', 90), ('longitude', 180)]:
            value = row[field]
            if type(value) not in (int, float) or not math.isfinite(value) or not -bound <= value <= bound:
                errors.append(f'mapping {index}: {field} must be finite and in {-bound}..{bound}')
        if not isinstance(row['locationGranularity'], str) or not row['locationGranularity'].strip():
            errors.append(f'mapping {index}: locationGranularity must be recorded')
        if not isinstance(row['verificationNote'], str):
            errors.append(f'mapping {index}: verificationNote must be text')
    if seen != set(expected):
        errors.append(f'Coordinate identities must cover all {len(expected)} original venues exactly once')
    if errors:
        raise ValueError('\n'.join(errors))


def render(rows, venues, source_hash):
    validate(rows, venues)
    values = ['    (' + ', '.join(map(literal, [r[f] for f in
              ('venueId', 'venueName', 'venueAddress', 'latitude', 'longitude')])) + ')' for r in rows]
    return (HERE / 'venue_coordinates.sql.template').read_text(encoding='utf-8').replace(
        '@SOURCE_HASH@', source_hash).replace('@COORDINATES@', ',\n'.join(values))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='validate without writing')
    args = parser.parse_args()
    raw = SOURCE.read_bytes()
    try:
        rows = json.loads(raw.decode('utf-8-sig'))
        venues = json.loads((HERE / 'venues.json').read_text(encoding='utf-8-sig'))
        sql = render(rows, venues, hashlib.sha256(raw).hexdigest())
    except ValueError as error:
        parser.exit(1, str(error) + '\nNo SQL was written.\n')
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding='utf-8') != sql:
            parser.exit(1, 'V62 differs from its mapping/template; review before applying.\n')
    else:
        OUTPUT.write_text(sql, encoding='utf-8', newline='\n')
    print(f'DEV16: {len(rows)} coordinate mappings validated; '
          f'{len(set((r["latitude"], r["longitude"]) for r in rows))} distinct points; '
          f'SQL {"matches" if args.check else "generated"}. No database changed.')


if __name__ == '__main__':
    main()
