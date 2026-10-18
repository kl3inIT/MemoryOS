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
        self.assertEqual(site, read(WORKTREE, "memoryos-sandbox/computer/sitecustomize.py"))
        self.assertIn("capture_open_figures", site)

    def test_reads_the_first_path_a_ref_has(self):
        self.assertEqual(sitecustomize(WORKTREE, "sandbox"), read(WORKTREE, RUNTIMES["sandbox"].sitecustomize[0]))
        # The working tree has no interpreter/ since MEM-271.
        with self.assertRaises(FileNotFoundError):
            sitecustomize(WORKTREE, "interpreter")

    def test_tells_the_model_where_each_runtime_keeps_the_files(self):
        self.assertTrue(harness(RUNTIMES["interpreter"]).endswith("in the current directory."))
        self.assertTrue(harness(RUNTIMES["sandbox"]).endswith("in `attachments/`."))


if __name__ == "__main__":
    unittest.main()
