UPDATE train_schedule
SET path = (
    SELECT jsonb_agg(
        jsonb_set(step, '{location}', (step -> 'location') - 'type')
        ORDER BY ordinality
    )
    FROM jsonb_array_elements(path) WITH ORDINALITY AS t(step, ordinality)
)
WHERE path IS NOT NULL;
