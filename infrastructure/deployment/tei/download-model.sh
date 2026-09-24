#!/bin/sh
# Places one pinned revision of an embedding model in the models volume for Text Embeddings Inference.
#
# Runs as the tei-model-download service before every start of tei. A revision already in place is
# left alone, so only the first deployment needs to reach Hugging Face. Files are fetched by commit
# revision, never by branch, into a staging directory that replaces the model only when complete:
# an interrupted download leaves no half model for TEI to load.
set -eu

: "${MODEL_REPOSITORY:?}" "${MODEL_REVISION:?}" "${MODEL_DIRECTORY:?}"
case "$MODEL_REVISION" in
    *[!0-9a-f]* | "") echo "MODEL_REVISION must be a commit hash" >&2; exit 64 ;;
esac
[ "${#MODEL_REVISION}" -eq 40 ] || { echo "MODEL_REVISION must be a full commit hash" >&2; exit 64; }

marker="$MODEL_DIRECTORY/.memoryos-revision"
if [ -f "$marker" ] && [ "$(cat "$marker")" = "$MODEL_REVISION" ]; then
    echo "$MODEL_REPOSITORY $MODEL_REVISION is already in place"
    exit 0
fi

staging="$MODEL_DIRECTORY.download"
rm -rf "$staging"
mkdir -p "$staging/1_Pooling"
# What TEI reads for a sentence-transformers model: weights, configuration, tokenizer and pooling.
for file in config.json tokenizer.json tokenizer_config.json model.safetensors modules.json \
        config_sentence_transformers.json 1_Pooling/config.json; do
    curl --fail --silent --show-error --location --retry 3 --output "$staging/$file" \
        "https://huggingface.co/$MODEL_REPOSITORY/resolve/$MODEL_REVISION/$file"
done
printf '%s\n' "$MODEL_REVISION" > "$staging/.memoryos-revision"
rm -rf "$MODEL_DIRECTORY"
mv "$staging" "$MODEL_DIRECTORY"
echo "Downloaded $MODEL_REPOSITORY $MODEL_REVISION"
