import unittest

from interpreter_eval.sources import PROMPTS, SITECUSTOMIZE, WORKTREE, arm, guidance, read


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
        self.assertIn("MPLCONFIGDIR", site)
        self.assertIn('private static final String RUN_PYTHON_GUIDANCE = """', read(WORKTREE, PROMPTS))
        self.assertEqual(site, read(WORKTREE, SITECUSTOMIZE))


if __name__ == "__main__":
    unittest.main()
