#!/usr/bin/env bash
# Downloads real CAN databases from comma.ai's opendbc (MIT) into samples/.
# https://github.com/commaai/opendbc/tree/master/opendbc/dbc
set -eu
cd "$(dirname "$0")"
mkdir -p samples
BASE="https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/dbc"
FILES="comma_body toyota_2017_ref_pt tesla_can hyundai_2015_ccan gm_global_a_chassis acura_ilx_2016_nidec vw_mqb"
# The page of a file in its repository, for a reader: raw.githubusercontent.com gives the bare text.
page() {
    case "$1" in
        https://raw.githubusercontent.com/*)
            local p="${1#https://raw.githubusercontent.com/}"
            local owner="${p%%/*}"; p="${p#*/}"
            local repo="${p%%/*}"; p="${p#*/}"
            echo "https://github.com/$owner/$repo/blob/$p" ;;
        *) echo "$1" ;;
    esac
}
{
    echo "# Where every sample comes from: <path in samples/> <page of the original>. Written by fetch-samples.sh;"
    echo "# the converter links these pages in the headers of the descriptions."
    for f in $FILES; do printf '%-24s %s\n' "$f.dbc" "$(page "$BASE/$f.dbc")"; done
} > samples/sources.txt
for f in $FILES; do
    curl -sSf -o "samples/$f.dbc" "$BASE/$f.dbc" && echo "fetched $f.dbc"
done
