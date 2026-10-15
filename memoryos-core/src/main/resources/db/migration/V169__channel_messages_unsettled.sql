-- MEM-263: the messages a process claimed and has not settled, which the receiving process sweeps every pass for those
-- an ended process left behind. Few rows at any time, so the sweep reads only them.
CREATE INDEX ix_channel_messages_unsettled ON channel_messages (received_at) WHERE outcome = 'RECEIVED';
