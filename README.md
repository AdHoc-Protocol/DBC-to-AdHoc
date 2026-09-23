# DBC-to-AdHoc — CAN database → AdHoc protocol description

> One of the [**converters to AdHoc protocol**](https://github.com/AdHoc-Protocol#converters-to-adhoc-protocol).
> Take a protocol you already have, get an [AdHoc](https://github.com/AdHoc-Protocol/AdHoc-protocol) description,
> open it in the Observer. The result is a starting point you refine by hand, not a finished protocol.

Turns Vector CAN databases (`.dbc`, the de-facto standard description of CAN bus traffic in the automotive world)
into [AdHoc](https://github.com/AdHoc-Protocol) protocol-description `.cs` files. A CAN frame is at most 8 bytes of
bit-packed signals, which is exactly the shape AdHoc's `[MinMax]` bit-packing and Value Packs are built for; the
DBC node list and the sender / receiver lists of every message give a real network topology for free.

The project is self-contained: converter, a local copy of the emitter helpers, sample fetcher, build and
validation scripts. Java 17+ only, no dependencies.

## Links

| What | Where |
|:--|:--|
| DBC format, readable introduction | https://www.csselectronics.com/pages/can-dbc-file-database-intro |
| Reference parser (Python `cantools`), useful as a format oracle | https://github.com/cantools/cantools |
| Sample databases (comma.ai opendbc, MIT) | https://github.com/commaai/opendbc/tree/master/opendbc/dbc |
| AdHoc protocol description format | https://github.com/AdHoc-Protocol (README of AdHocAgent) |

## Samples shipped

`fetch-samples.sh` downloads these files from opendbc into `samples/` (all seven parse and validate):

| File | Messages | Signals | Nodes | Notable features |
|:--|--:|--:|--:|:--|
| `comma_body.dbc` | 14 | 60 | 0 | no nodes at all, Motorola byte order |
| `gm_global_a_chassis.dbc` | 4 | 9 | 5 | `CM_`, `BA_DEF_`/`BA_` attributes |
| `acura_ilx_2016_nidec.dbc` | 36 | 69 | 3 | `VAL_`, comments |
| `tesla_can.dbc` | 44 | 572 | 13 | multiplexed signals, 145 `VAL_` tables, `VAL_TABLE_`, nodes implied by `BO_`/`SG_` |
| `hyundai_2015_ccan.dbc` | 113 | 1154 | 45 | Intel byte order, node names starting with `_` |
| `toyota_2017_ref_pt.dbc` | 143 | 1315 | 10 | ids above 16 bits, Motorola byte order |
| `vw_mqb.dbc` | 113 | 1348 | 21 | 29-bit extended ids, multiplexing, multi-line comments |

## Build, run, validate

```bash
./fetch-samples.sh            # samples/*.dbc
./build.sh                    # javac → out/, then samples/ → AdHoc/<name>.cs
./validate.sh AdHoc           # AdHocAgent parse-only run over every generated file (nothing is uploaded)

# by hand:
javac -encoding UTF-8 --release 17 -d out src/org/unirail/adhoc/*.java src/org/unirail/*.java
java -Dfile.encoding=UTF-8 -cp out org.unirail.DBC2AdHoc <file.dbc or folder> [output folder]
```

`validate.sh` expects the AdHocAgent Debug build at `AdHocAgent.exe` (found on `PATH`, or set `AGENT=/path/to/AdHocAgent.exe`)
(override with `AGENT=…`). It runs the agent with `ADHOC_PARSE_ONLY=1 ADHOC_DUMP_BRANCHES=1` on a copy of each
file and leaves `AdHoc/<name>.branches.txt` with the packs every branch collected and their ids.

**Validation result for the shipped samples: all 7 files `OK`** (exit 0, no errors, no warnings).

## Mapping

| DBC | AdHoc | Notes |
|:--|:--|:--|
| file `x.dbc` | `namespace org.dbc { public interface x { … } }` | one descriptor per database |
| `VERSION`, global `CM_`, global `BA_` | header comment lines | |
| `BO_ id NAME: dlc SENDER` | `class NAME { public const uint can_id; public const bool extended; public const int dlc; public const string sender; … }` | the CAN id is **never** pinned into the Dashboard — see [Source identity](#source-identity-stays-metadata) |
| `SG_ NAME : start\|len@endian sign` | field typed by the smallest integer for `len`/sign, `[MinMax(lo, hi)]` for the exact bit width | 1-bit unsigned → `bool`; 8/16/32/64-bit → plain type; `[StartBit(n)]`; `@0` → `[BigEndian]` |
| `SIG_VALTYPE_ … : 1 / 2` | `float` / `double` | |
| `(factor, offset)` | `[Factor(f)] [Offset(o)]` + doc line `physical = raw * f + o unit` | omitted when 1 / 0 |
| `[min\|max]` | `[PhysRange(min, max)]` | a UI hint, deliberately **not** `[MinMax]` (that is a wire limit on the raw value) |
| `"unit"` | `[Units("unit")]` | |
| `M` / `mN` | `[Multiplexor]` / `[Multiplexed(N)]`, multiplexed fields are nullable (`byte?`) | present only for one multiplexor value → optional, one bit when absent |
| `VAL_ id SIG v "name" …` (≥ 2 entries) | `enum NAME_SIG { name = v, … }` | field is typed with the enum when the table names **every** raw value of the signal; otherwise the raw integer type stays and `[ValueTable("NAME_SIG")]` points to the enum |
| `VAL_` with 1 entry | dropped — AdHoc rejects enums with fewer than two members | a trailing `// dropped DBC VAL_ …` comment is left on the field, so whoever refines the file sees what was lost |
| `CM_ BO_` / `CM_ SG_` / `CM_ BU_` | doc comment of the pack / field / host | multi-line comments supported |
| `BA_ "GenMsgCycleTime" BO_` | `[CycleTime(ms)]` on the pack | |
| `BA_ "GenSigStartValue" SG_` | `[StartValue(v)]` on the field | |
| any other `BA_` on a message / signal | `[Attr("Name", "value")]` (repeatable) | |
| `BU_:` nodes | `struct NODE : Host { }` | nodes that appear only as `BO_` sender / `SG_` receiver are added too (flagged "implied") |
| sender → receivers of each message | one `interface A_to_B : Connects<A, B>` per pair of nodes that exchange messages, a single non-transitional state with `[l____________<(…)>]` (A sends) and `[____________r<(…)>]` (B sends) | explicit pack lists, so only real messages are transmittable |
| unknown sender (`XXX`, `Vector__XXX`) | synthetic host `UnknownECU` | |
| no known receiver | synthetic host `Bus` (broadcast sink) | |

Names go through the same keyword check AdHocAgent applies (`type` → `Type`, `_4WD` → `N4WD`); duplicates within a
scope get a numeric suffix.

### Source identity stays metadata

A pack id is AdHoc's own internal matter: the agent assigns and maintains it. The CAN identifier describes the
*source's* wire format, which AdHoc replaces with its own, so it is never pinned into the Packs Inventory. Every
message carries it instead as constants a migration can audit:

```csharp
class MOTORS_DATA {
    public const uint can_id  = 0x201;
    public const bool extended = false;   // true for 29-bit ids
    public const int  dlc      = 8;
    …
}
```

The generated Dashboard therefore lists every pack with no `id =` at all, and the agent numbers them on the first
run. This holds for all messages, 11-bit and 29-bit alike.

### Why no varint attributes

A CAN signal is a fixed-width bit field: its raw value is uniformly distributed across exactly `len` bits, and the
DBC says nothing about where inside that span the values sit. That is the one case where `[A]`/`[V]`/`[X]` makes
the wire *bigger*, so the converter emits none of them. `[MinMax(lo, hi)]` is the right tool and the converter
applies it to every non-byte-aligned signal, which lets AdHoc bit-pack the field to the same width the CAN frame
uses. The physical `[min|max]` from the DBC is a separate, purely informational `[PhysRange]`.

### Why no `_DefaultMaxLengthOf`

The enum raises the global 255-item cap for arrays, maps, sets and strings. A DBC describes nothing but scalar bit
fields — the generated descriptors contain no array, string, `Map` or `Set` field at all (the only strings are
`const` metadata, which never travels), so the enum would cap nothing and is deliberately left out.

## Limitations

- `SG_MUL_VAL_` (extended multiplexing with several multiplexors) is not interpreted; such signals are emitted with
  the plain `[Multiplexed(N)]` of their first multiplexor value.
- `EV_` environment variables, `SIG_GROUP_`, `BA_DEF_REL_` / `BA_REL_` and signal-type definitions are skipped.
- A message that is both sent by node A and received by A (self-reception) is not represented.
- Byte order and start bit are carried as metadata only: AdHoc lays the fields out its own way on the wire, so the
  generated code is a typed model of the DBC, not a bit-exact CAN frame codec.
