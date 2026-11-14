-- The owner can say who spoke one line. `speaker` is the voice the line is shown as; `diarized_speaker` keeps the label
-- the provider gave, written the first time the owner moves the line and never changed after, so a move can be traced
-- back to what was heard. Manual voices are labelled M1, M2… within their track; providers label voices with numbers.
ALTER TABLE meeting_utterance ADD COLUMN diarized_speaker VARCHAR(16);
