"""A host that has never run this deployment is still a host it must deploy to.

The first promotion onto the production node stopped before it changed anything: the script
inspects the running api, worker and web to record what a rollback would restore, found none of
them, and reported "Unhealthy or mixed runtime". The next statement would have failed too,
selecting from a Flyway history table that a database Flyway has never touched does not have.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / "infrastructure/deployment/deploy.sh").read_text(encoding="utf-8")


def block(marker):
    """The lines a guard owns, by indentation, starting at the line that opens it."""
    lines = SCRIPT.split("\n")
    start = next(i for i, line in enumerate(lines) if marker in line)
    opening = len(lines[start]) - len(lines[start].lstrip())
    for index in range(start + 1, len(lines)):
        line = lines[index]
        if line.strip() and len(line) - len(line.lstrip()) <= opening:
            return "\n".join(lines[start:index])
    return "\n".join(lines[start:])


class FirstDeploymentTest(unittest.TestCase):
    def test_the_absent_runtime_is_what_marks_a_first_deployment(self):
        # Not the absence of current.env: a host whose runtime was built over SSH before this
        # script existed also has no current.env, and it does have something to roll back to.
        self.assertIn('if ! docker inspect memoryos-api > /dev/null 2>&1; then', SCRIPT)
        self.assertIn('touch "$tx/first-deployment"', SCRIPT)

    def test_the_mark_is_a_file_because_the_modes_are_separate_invocations(self):
        # deploy, rollback and finish each start the script again; a shell variable would not
        # survive between them.
        for mode in ('rollback',):
            self.assertIn('"$tx/first-deployment"', SCRIPT, mode)
        self.assertNotIn("first_deployment=", SCRIPT, "a variable cannot outlive the invocation")

    def test_nothing_reads_a_previous_runtime_that_does_not_exist(self):
        guarded = block('if [[ ! -f "$tx/first-deployment" ]]; then')
        for reference in ('docker inspect "${previous_components[@]/#/memoryos-}"',
                          '"$tx/previous.env"', '"$tx/previous.compose"',
                          'target=previous; compose config'):
            self.assertIn(reference, guarded, "%s is read outside the guard" % reference)

    def test_the_writers_it_stops_before_the_backup_may_not_exist(self):
        # This one runs after the reservation is written, so failing here leaves the host needing
        # an operator rather than simply refusing.
        self.assertIn('if [[ ! -f "$tx/first-deployment" ]]; then target=previous; '
                      'compose stop --timeout 45 worker api; fi', SCRIPT)

    def test_rollback_refuses_rather_than_restoring_nothing(self):
        rollback = SCRIPT.split("elif [[ \"$mode\" == rollback ]]; then", 1)[1]
        rollback = rollback.split("elif [[ \"$mode\" == finish ]]", 1)[0]
        self.assertIn('if [[ -f "$tx/first-deployment" ]]; then', rollback)
        self.assertIn("no previous runtime exists to restore", rollback)
        # The reservation stays: the host's own state has to keep saying somebody must look.
        refusal = rollback.split('if [[ -f "$tx/first-deployment" ]]; then', 1)[1].split("fi", 1)[0]
        self.assertNotIn('rm -- "$state/pending"', refusal)


    def test_the_reservation_is_asked_about_once(self):
        # Whether a reservation exists is answered before the modes divide, so the copy that used
        # to repeat it inside the rollback branch could never run and only read as a second rule.
        self.assertEqual(SCRIPT.count("No runtime mutation was reserved"), 1)


class SchemaHistoryTest(unittest.TestCase):
    def test_the_history_is_asked_for_before_it_is_read(self):
        # Flyway creates the table the first time it runs. Selecting from a table that is not
        # there is an error, not an empty result.
        self.assertIn("to_regclass('public.flyway_schema_history')", SCRIPT)
        self.assertIn("has_schema_history()", SCRIPT)

    def test_every_read_of_the_history_is_guarded(self):
        for target in ("schema.before", "schema.after-failure", "schema.accepted"):
            pattern = r'if has_schema_history; then schema > "\$tx/%s"; else : > "\$tx/%s"; fi' % (
                re.escape(target), re.escape(target))
            self.assertRegex(SCRIPT, pattern, target)

    def test_an_absent_history_reads_as_no_migrations_rather_than_as_a_failure(self):
        # The comparison that follows walks the file line by line, so an empty file means the
        # candidate predates nothing, which is the truth about a database Flyway has not touched.
        self.assertIn(': > "$tx/schema.before"', SCRIPT)


if __name__ == "__main__":
    unittest.main()
