#!/usr/bin/env bash
# Downloads real CAN databases from comma.ai's opendbc (MIT) into samples/.
# https://github.com/commaai/opendbc/tree/master/opendbc/dbc
set -eu
cd "$(dirname "$0")"
mkdir -p samples
BASE="https://raw.githubusercontent.com/commaai/opendbc/master/opendbc/dbc"
for f in comma_body toyota_2017_ref_pt tesla_can hyundai_2015_ccan gm_global_a_chassis acura_ilx_2016_nidec vw_mqb; do
    curl -sSf -o "samples/$f.dbc" "$BASE/$f.dbc" && echo "fetched $f.dbc"
done
