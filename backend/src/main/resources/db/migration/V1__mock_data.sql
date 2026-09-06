CREATE TABLE IF NOT EXISTS mock (
    id UUID PRIMARY KEY,
    mock_string VARCHAR(50) NOT NULL,
    mock_character CHAR NOT NULL,
    mock_integer INTEGER NOT NULL,
    mock_boolean BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS mock_references (
    mock_id UUID NOT NULL,
    mock_string2 VARCHAR(50) NOT NULL,
    
    FOREIGN KEY (mock_id) REFERENCES mock(id),
    PRIMARY KEY (mock_id,mock_string2)    
);




