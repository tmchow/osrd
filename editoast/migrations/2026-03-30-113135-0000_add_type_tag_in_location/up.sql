UPDATE train_schedule
SET path = (
    SELECT jsonb_agg(
        CASE
            WHEN (step -> 'location') ? 'track'
                THEN jsonb_set(step, '{location, type}', '"track_offset"')
            WHEN (step -> 'location') ? 'operational_point'
                THEN jsonb_set(step, '{location, type}', '"operational_point_part_reference"')
            ELSE step
        END
        ORDER BY ordinality
    )
    FROM jsonb_array_elements(path) WITH ORDINALITY AS t(step, ordinality)
)
WHERE path IS NOT NULL;
