"""Deck authoring for the MemoryOS interpreter executor (MEM-122).

`render-deck` turns a validated deck plan into a corporate-template `.pptx`; `check-pptx` reports the faults
a model cannot see in a presentation it wrote. The model owns the content, this package owns the layout.

Import the submodules directly — :mod:`memoryos_deck.plan`, :mod:`memoryos_deck.render`,
:mod:`memoryos_deck.inspect`, :mod:`memoryos_deck.theme`, :mod:`memoryos_deck.measure` — so the `render`
module and the `render` function never shadow each other.
"""
