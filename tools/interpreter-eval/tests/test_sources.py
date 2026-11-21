import unittest

from interpreter_eval.runner import harness
from interpreter_eval.sources import PROMPTS, RUNTIMES, WORKTREE, arm, guidance, read, sitecustomize


class GuidanceTest(unittest.TestCase):
    def test_extracts_the_text_block_as_sent(self):
        java = (
            'class A {\n    private static final String RUN_PYTHON_GUIDANCE = """\n        ## run_python\n'
            '        Use it.\n        """;\n}\n'
        )
        self.assertEqual(guidance(java), "## run_python\nUse it.")

    def test_rejects_a_file_without_the_block(self):
        with self.assertRaises(ValueError):
            guidance("class A {}")

    def test_reads_the_working_tree(self):
        prompt, site = arm(WORKTREE)
        self.assertTrue(prompt.startswith("## run_python"))
        self.assertIn('private static final String RUN_PYTHON_GUIDANCE = """', read(WORKTREE, PROMPTS))
        self.assertEqual(site, read(WORKTREE, "memoryos-interpreter/executor/sitecustomize.py"))
        self.assertIn("MPLCONFIGDIR", site)

    def test_reads_the_interpreter_under_its_older_path_where_a_ref_has_it(self):
        _, site = arm(WORKTREE, "interpreter")
        # A ref from before MEM-271 has it under interpreter/; the newest path is tried first.
        self.assertEqual(site, sitecustomize(WORKTREE, "interpreter"))

    def test_tells_the_model_where_each_runtime_keeps_the_files(self):
        self.assertTrue(harness(RUNTIMES["interpreter"]).endswith("in the current directory."))


if __name__ == "__main__":
    unittest.main()
