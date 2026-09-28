"""Off-host backup: what goes into an archive, and what a target keeps.

Until this existed, production had no backup at all: a dump script nobody scheduled, an empty
backups directory, and the pre-migration dumps the deployment writes onto the same disk.
"""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
BACKUP = (HERE / "backup.sh").read_text(encoding="utf-8")
# What the script runs, without the comments that explain it.
COMMANDS = "\n".join(line for line in BACKUP.splitlines() if not line.lstrip().startswith("#"))
LINUX = sys.platform != "win32" and shutil.which("bash") is not None


class ArchiveContentsTest(unittest.TestCase):
    def test_the_database_dump_is_the_one_that_proves_itself(self):
        # backup-databases.sh runs pg_restore --list on every dump and checks a manifest; a second
        # dump routine here would be one that does not.
        self.assertIn("backup-databases.sh", BACKUP)
        self.assertNotIn("pg_dump ", COMMANDS)

    def test_the_encryption_keys_travel_with_the_database(self):
        # A database restored without the credential encryption keys holds rows nobody can decrypt.
        self.assertIn('"${root#/}/secrets"', BACKUP)
        self.assertIn('"${environment_file#/}"', BACKUP)

    def test_what_can_be_rebuilt_is_left_out(self):
        self.assertNotIn("opensearch-data", COMMANDS)
        self.assertNotIn("redis-data", COMMANDS)

    def test_nothing_leaves_the_host_unencrypted(self):
        seal = BACKUP.index("age --encrypt")
        send = BACKUP.index("rsync ")
        self.assertLess(seal, send)
        self.assertIn('"$archive" "$archive.sha256" "$remote:"', BACKUP)

    def test_the_host_verifies_the_target_it_sends_to(self):
        self.assertIn("StrictHostKeyChecking=yes", BACKUP)
        self.assertIn("BatchMode=yes", BACKUP)

    def test_success_is_recorded_only_at_the_end(self):
        self.assertGreater(BACKUP.index("last-success"), BACKUP.index("sent to $remote"))


@unittest.skipUnless(LINUX, "executes the retention script")
class RetentionTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())

    def tearDown(self):
        shutil.rmtree(self.directory, ignore_errors=True)

    def archives(self, stamps):
        for stamp in stamps:
            (self.directory / ("memoryos-production-%s.tar.zst.age" % stamp)).write_text("x")
            (self.directory / ("memoryos-production-%s.tar.zst.age.sha256" % stamp)).write_text("x")

    def prune(self, *arguments):
        return subprocess.run(["bash", str(HERE / "prune-backups.sh"), str(self.directory), *arguments],
                              capture_output=True, text=True, check=True).stdout

    def left(self):
        return sorted(path.name.split("-", 2)[2][:16] for path in self.directory.glob("*.age"))

    def test_keeps_the_newest_fourteen_and_one_per_month_for_six_months(self):
        # One archive a night for about nine months.
        stamps = []
        for month in range(1, 10):
            for day in range(1, 29):
                stamps.append("2026%02d%02dT021000Z" % (month, day))
        self.archives(stamps)
        self.prune()
        left = self.left()
        newest = sorted(stamps)[-14:]
        for stamp in newest:
            self.assertIn(stamp, left)
        # The first archive of the six most recent months survives; older months are gone.
        for month in range(4, 10):
            self.assertIn("2026%02d01T021000Z" % month, left)
        for month in range(1, 4):
            self.assertNotIn("2026%02d01T021000Z" % month, left)
        self.assertEqual(len(left), len(set(newest) | {"2026%02d01T021000Z" % m for m in range(4, 10)}))

    def test_a_checksum_goes_with_its_archive(self):
        self.archives(["202601%02dT021000Z" % day for day in range(1, 29)])
        self.prune()
        self.assertEqual(len(list(self.directory.glob("*.age"))),
                         len(list(self.directory.glob("*.sha256"))))

    def test_a_dry_run_removes_nothing(self):
        self.archives(["202601%02dT021000Z" % day for day in range(1, 29)])
        output = self.prune("--dry-run")
        self.assertIn("would remove", output)
        self.assertEqual(len(list(self.directory.glob("*.age"))), 28)

    def test_an_empty_target_is_not_an_error(self):
        self.assertIn("No archives", self.prune())


if __name__ == "__main__":
    unittest.main()
