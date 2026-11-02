# The answer key

One JSON file per benchmark meeting, `key/NN.json` beside `key/lines/NN.json`, kept on the staging server only. It
says what a careful note-taker who heard the whole meeting would put in its minutes. The minutes MemoryOS writes are
scored against it, so it must not copy any model's minutes: draft it from the transcript alone.

```json
{
  "copy": "<copied meeting id, from the lines file>",
  "decisions": [
    {"text": "Dùng Google Drive của dự án làm nơi lưu tài liệu.", "line": 212, "sure": true}
  ],
  "actions": [
    {"text": "Gửi path spec của bucket MyTasco cho nhóm tích hợp.", "line": 318,
     "owner": {"track": "MIC", "label": "1"}, "why": "MIC/1: 'để em gửi path spec'", "sure": true},
    {"text": "Liên hệ Hà để hỏi khu vực Google Drive được dùng.", "line": 330,
     "owner": null, "why": "nobody takes it on", "sure": false}
  ],
  "notes": "anything the reviewer should know"
}
```

## What goes in

- **Decision**: something the meeting settled or agreed — a choice, an approval, a rule, a date fixed. Not an
  opinion, a proposal nobody accepted, or a fact someone reported.
- **Action**: work someone will do after the meeting — a concrete commitment or an assignment that was accepted
  or not refused. Not work already done, a wish ("nên…") nobody takes on, or the meeting's own discussion.
- One commitment is one item, even when it is said three times; two different pieces of work said in one breath
  are two items. Leave out trivia (closing the call, sharing a screen now).
- `text`: short Vietnamese, as minutes would write it, naming the object of the work.
- `line`: the number of the line where the decision is made or the work is committed to (the clearest one).

## The owner of an action

The owner is the one person who will **do** the work, not the one who asked for it.

- A speaker of the transcript who takes it on ("để em làm", "em sẽ gửi") or is asked by name or by role and
  accepts: that speaker's voice, `{"track": "MIC", "label": "2"}`, as the lines show it (`MIC/2`). In an ONLINE
  meeting `MIC` is the person who recorded it and `TAB` the people on the call.
- A person the transcript names who is not one of the voices, or whose voice cannot be told: `{"name": "Hà"}`.
- Nobody in particular, a group ("cả team", "bên mình"), several people, or not said: `null`.
- `why`: the words that show it, in a few words with the speaker. `sure: false` when the transcript leaves room
  for doubt (a garbled line, two voices that may be one person, an assignment that was never answered), so the
  reviewer reads those first.

## Review

The owner reads every meeting's key beside its transcript on a local page, fixes items, owners and lines, and
saves the file. A reviewed key is frozen with the corpus fingerprint of `copies.json`; changing it afterwards makes
earlier results incomparable.
