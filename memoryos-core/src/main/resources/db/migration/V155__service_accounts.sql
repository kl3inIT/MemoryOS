-- MEM-246: an account of the Tenant's own setup, such as a channel's bot, beside the accounts of people.
ALTER TABLE actors
    DROP CONSTRAINT ck_actors_account_type;

ALTER TABLE actors
    ADD CONSTRAINT ck_actors_account_type
        CHECK (account_type IN ('STANDARD', 'SERVICE'));

-- The person a channel's sender is, found by the e-mail their identity provider verified.
CREATE INDEX ix_actor_profiles_verified_email
    ON actor_profiles (LOWER(BTRIM(email)))
    WHERE email_verified AND email IS NOT NULL;
