CREATE TYPE user_role AS ENUM(
    'ec',
    'eo',
    'vs',
    'attendee',
    'technician'
);
CREATE TYPE event_status AS ENUM(
    'confirmed',
    'cancelled',
    'completed'
);
CREATE TYPE event_request_status AS ENUM(
    'pending',
    'approved',
    'rejected',
    'cancelled'
);
CREATE TYPE equipment_request_status AS ENUM(
    'processing',
    'approved',
    'rejected'
);
CREATE TYPE equipment_status AS ENUM(
    'available',
    'in_use',
    'damaged',
    'maintenance',
    'retired'
);
CREATE TYPE venue_booking_status AS ENUM(
    'pending',
    'confirmed',
    'changed',
    'rejected',
    'cancelled'
);

-- Users tables roles includes the EC,EO,VS,Attendee etc, organisation can create a separate table if really needed.
-- for EC or EO orgs can be null or connectSphere etc. 
CREATE TABLE IF NOT EXISTS users(
    user_id UUID PRIMARY KEY,
    hashed_password VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    username VARCHAR(255) NOT NULL UNIQUE, 
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    role user_role NOT NULL,
    organisation VARCHAR(100)
);
-- Types of accesibilities features can add more by inserting or smth, will be stored as an array in the tables that references this
CREATE TYPE accessibilities AS ENUM(
    'accessible_parking',
    'drop_off_zone',
    'public_transport',
    'step_free_access',
    'wide_doorways',
    'elevators',
    'wheelchair_support'
);
-- Likewise for facilities
CREATE TYPE facilities AS ENUM(
    'audio_visual_equipment',
    'air_conditioning',
    'breakout_spaces',
    'projection',
    'stage',
    'dining_area',
    'barbeque_pit'
);

-- Venues with the accessibilities and facilities, index has been created to allow better filtering when calling the db
CREATE TABLE IF NOT EXISTS venues(
    venue_id UUID PRIMARY KEY,
    venue_address TEXT NOT NULL,
    venue_layout TEXT,
    venue_capacity INTEGER,
    venue_accessibilities accessibilities[] NOT NULL DEFAULT '{}',
    operating_information TEXT NOT NULL,
    venue_facilities facilities[] NOT NULL DEFAULT '{}',
    additional_information TEXT
);
CREATE INDEX idx_venue_accessibility
ON venues USING GIN (venue_accessibilities);
CREATE INDEX idx_venue_facility
ON venues USING GIN (venue_facilities);



-- Events, venues can be null at first when a venue is not tagged to it, status whether it is confirmed cancelled and more, i put venue requirements as text, can change if u see a different vision.
CREATE TABLE IF NOT EXISTS events(
    event_id UUID PRIMARY KEY,
    event_name VARCHAR(255) NOT NULL,
    purpose TEXT NOT NULL,
    description TEXT,
    start_datetime TIMESTAMPTZ NOT NULL,
    end_datetime TIMESTAMPTZ NOT NULL,
    expected_attendance INTEGER NOT NULL,
    venue_id UUID,
    accessibility_needs accessibilities[] NOT NULL DEFAULT '{}',
    registration_needs BOOLEAN,
    organisation VARCHAR(100),
    actual_attendance INTEGER,
    venue_requirements TEXT NOT NULL,
    status event_status NOT NULL,

    FOREIGN KEY (venue_id) REFERENCES venues(venue_id)
);


-- Event requests got two types, so change and creation, change will have event_id, non change will not have, then the rest of the fields are wtv is needed.
CREATE TABLE IF NOT EXISTS event_requests(
    request_id UUID PRIMARY KEY,
    request_type CHAR(1),
    event_id UUID,
    event_name VARCHAR(255) NOT NULL,
    purpose TEXT NOT NULL,
    description TEXT,
    start_datetime TIMESTAMPTZ,
    end_datetime TIMESTAMPTZ,
    expected_attendance INTEGER NOT NULL,
    venue_requirements TEXT NOT NULL,
    accessibility_needs accessibilities[] NOT NULL DEFAULT '{}',
    registration_needs BOOLEAN,
    status event_request_status NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    organisation VARCHAR(100),
    created_by UUID,

    FOREIGN KEY (event_id) REFERENCES events(event_id),
    FOREIGN KEY (created_by) REFERENCES users(user_id)




);
-- Equipments quantity to keep track of total quantities in the inventory, serialised if its like very important equipment such as mixer etc., can take out if you feel like it is not needed for this scope. , in the future can add like damage quantity etc.
CREATE TABLE IF NOT EXISTS equipments(
    equipment_id UUID PRIMARY KEY,
    equipment_name VARCHAR(255) NOT NULL,
    equipment_qty INTEGER NOT NULL,
    serialised BOOLEAN,
    equipment_type CHAR(1) NOT NULL


);

-- Serialised equipments status to show damage etc., primary key being serial number and equipment id in case different equipment have diff serial number
CREATE TABLE IF NOT EXISTS serialised_equipments(
    equipment_id UUID,
    serial_number VARCHAR(255) NOT NULL,
    status equipment_status NOT NULL,

    FOREIGN KEY (equipment_id) REFERENCES equipments(equipment_id),
    PRIMARY KEY (equipment_id,serial_number)


); 

--equipment requests with status approved, reject, processing
CREATE TABLE IF NOT EXISTS equipment_requests(
    request_id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    status equipment_request_status NOT NULL,
    technical_requirement TEXT NOT NULL,
    reject_reason TEXT,
    FOREIGN KEY (event_id) REFERENCES events(event_id)


);
-- equipment id and quantity needed for each requests 
CREATE TABLE IF NOT EXISTS equipment_request_equipments(
    request_id UUID NOT NULL,
    equipment_id UUID NOT NULL,
    equipment_qty INTEGER NOT NULL,
    FOREIGN KEY (equipment_id) REFERENCES equipments(equipment_id),
    FOREIGN KEY (request_id) REFERENCES equipment_requests(request_id),
    PRIMARY KEY (request_id,equipment_id)


);
--equipment_logs log_id, will be used to keep trackof available equipment during a given period
CREATE TABLE IF NOT EXISTS equipment_logs(
    log_id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    equipment_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    technical_requirements TEXT,
    serial_number VARCHAR(255),
    loaned_from TIMESTAMPTZ NOT NULL,
    loaned_until TIMESTAMPTZ NOT NULL,

    FOREIGN KEY (event_id) REFERENCES events(event_id),
    FOREIGN KEY (equipment_id) REFERENCES equipments(equipment_id),
    FOREIGN KEY (equipment_id,serial_number) REFERENCES serialised_equipments(equipment_id,serial_number)


);


--Once appproved and event is confirmed, propagate the event with the confirmed venue_id, for changes, change the existing venue_bookings to cancel or change or reject then open a new booking
CREATE TABLE IF NOT EXISTS venue_bookings(
    booking_id UUID PRIMARY KEY,
    venue_id UUID NOT NULL,
    event_id UUID NOT NULL,
    status venue_booking_status NOT NULL,
    booking_notes TEXT,
    reject_reason TEXT,
    FOREIGN KEY (event_id) REFERENCES events(event_id),
    FOREIGN KEY (venue_id) REFERENCES venues(venue_id)



);



