import unittest

from interpreter_eval.scoring import answer_of, code_of, matches, old_idioms


class MatchesTest(unittest.TestCase):
    def test_numbers_compare_with_tolerance_and_keys_as_strings(self):
        self.assertTrue(matches({"Bắc": 30227.0, "1": [1, 2]}, {"Bắc": 30227, 1: [1.0, 2.0]}))
        self.assertTrue(matches(24980.45, 24980.4500001))
        self.assertFalse(matches(24980.45, 25033.2))

    def test_shape_must_agree(self):
        self.assertFalse(matches([1, 2, 3], [1, 2]))
        self.assertFalse(matches({"a": 1}, {"a": 1, "b": 2}))
        self.assertFalse(matches(1, True))
        self.assertFalse(matches(1, "one"))


class OutputTest(unittest.TestCase):
    def test_reads_the_last_answer_line(self):
        self.assertEqual(answer_of('loading\n{"answer": 1}\n{"answer": [2]}\n'), [2])
        self.assertIsNone(answer_of("no json here\n[1, 2]\n"))

    def test_takes_the_first_fenced_block(self):
        self.assertEqual(code_of("Here:\n```python\nprint(1)\n```\n```python\nprint(2)\n```"), "print(1)\n")
        self.assertEqual(code_of("print(3)"), "print(3)")


class IdiomTest(unittest.TestCase):
    def test_flags_the_habits_that_changed(self):
        code = (
            "df['a'].fillna(0, inplace=True)\ns = df.resample('M').sum()\nnp.trapz(y)\n"
            "pd.to_datetime(d, dayfirst=True)\n"
        )
        self.assertEqual(old_idioms(code), ["dayfirst", "freq_M_Q_H", "inplace_on_column", "np_removed_alias"])
        self.assertEqual(old_idioms("df.loc[m, 'a'] = 0\ndf.resample('ME').sum()\nnp.trapezoid(y)\n"), [])


if __name__ == "__main__":
    unittest.main()
