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

## Before and after

`samples/tesla_can.dbc`, 901 lines — [source](samples/tesla_can.dbc) → [result](AdHoc/tesla_can.cs). One message
of it, the steering command the Autopilot computer sends to the power steering:

```dbc
VERSION ""

BU_:

BO_ 1160 DAS_steeringControl: 4 NEO
 SG_ DAS_steeringControlType : 23|2@0+ (1,0) [0|0] "" EPAS
 SG_ DAS_steeringControlChecksum : 31|8@0+ (1,0) [0|0] "" EPAS
 SG_ DAS_steeringControlCounter : 19|4@0+ (1,0) [0|0] "" EPAS
 SG_ DAS_steeringAngleRequest : 6|15@0+ (0.1,-1638.35) [-1638.35|1638.35] "deg" EPAS
 SG_ DAS_steeringHapticRequest : 7|1@0+ (1,0) [0|0] "" EPAS

// … 43 more BO_ blocks …

VAL_ 1160 DAS_steeringAngleRequest 16384 "ZERO_ANGLE" ;
VAL_ 1160 DAS_steeringControlType 1 "ANGLE_CONTROL" 3 "DISABLED" 0 "NONE" 2 "RESERVED" ;
VAL_ 1160 DAS_steeringHapticRequest 1 "ACTIVE" 0 "IDLE" ;
```

```csharp
// … 43 more messages …
        class DAS_steeringControl {
            public const uint can_id = 0x488;
            public const bool extended = false;
            public const int dlc = 4;
            public const string sender = "NEO";
            [StartBit(23), BigEndian] DAS_steeringControl_DAS_steeringControlType DAS_steeringControlType;
            [StartBit(31), BigEndian] byte DAS_steeringControlChecksum;
            [MinMax(0, 15), StartBit(19), BigEndian] byte DAS_steeringControlCounter;
            /**
            physical = raw * 0.1 - 1638.35 deg
            */
            [MinMax(0, 32767), StartBit(6), BigEndian, Factor(0.1), Offset(-1638.35), PhysRange(-1638.35, 1638.35), Units("deg")] ushort DAS_steeringAngleRequest; // physics: physical zero is raw 16383 (raw span 0…32767); if values cluster around it, consider [X(amplitude, 16383)] // dropped DBC VAL_ 16384 "ZERO_ANGLE": a single named value, too few for an AdHoc enum
            [StartBit(7), BigEndian] DAS_steeringControl_DAS_steeringHapticRequest DAS_steeringHapticRequest;
        }
// … value tables …
        /**
        Values of DAS_steeringControl.DAS_steeringControlType
        */
        enum DAS_steeringControl_DAS_steeringControlType {
            ANGLE_CONTROL = 1,
            DISABLED = 3,
            NONE = 0,
            RESERVED = 2,
        }
```

A 2-bit signal whose `VAL_` table names all four values becomes a real enum; a 4-bit counter becomes a
`[MinMax(0, 15)] byte` that AdHoc packs into 4 bits; the 15-bit angle keeps its scaling as metadata and carries a
note about the distribution its author never stated; the one-entry `VAL_` table that AdHoc cannot express as an
enum leaves a trail instead of vanishing.

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

`validate.sh` looks for `AdHocAgent.exe` on `PATH`, then in the usual local build folders; point it elsewhere with
`AGENT=/path/to/AdHocAgent.exe ./validate.sh AdHoc`. It runs the agent with `ADHOC_PARSE_ONLY=1
ADHOC_DUMP_BRANCHES=1` on a copy of each file — nothing is uploaded — and leaves `AdHoc/<name>.branches.txt` with
the packs every branch collected and the ids the agent assigned.

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

### Varint: why the converter emits none, and where it says so

How CAN stores a signal decides nothing — AdHoc lays out its own frame, and the source's width is never a reason
to accept or decline `[A]`/`[V]`/`[X]`. What decides it is where the *values* sit.

For a raw CAN signal, the honest default answer is: **uniformly across its whole span**. A `len`-bit signal is
declared to take every value in `0 … 2^len-1`, and DBC states nothing that narrows it. Uniform is exactly the
distribution varint cannot help: with no cluster to sit on, every value is encoded at full magnitude and the
continuation bits are pure overhead. `[MinMax(lo, hi)]` is the right attribute for that claim, and the converter
puts it on every signal whose span is not a whole number of bytes, so AdHoc bit-packs the field to the same width
the value actually needs.

The arithmetic, once: varint pays while the typical distance from the declared base stays under roughly two
million, breaks even to 268 435 455, and beyond that always loses. So a scaled latitude, a Unix timestamp or a
free-running counter are varint losses regardless of how they were stored.

But DBC *hints* at physics it never states. A name or a unit can mark a signal as a counter that idles near its
floor, a distance that hugs zero, or a value centred on ambient — all of which are genuine `[A]`/`[V]`/`[X]`
candidates. That call needs knowledge of the traffic, which the converter does not have, so it invents no
attribute and instead leaves a comment **on the field** naming the candidate and the reason, wherever the raw span
exceeds one byte (below that varint is rejected anyway and `[MinMax]` already wins) and stays inside the range
where varint can still pay off:

```csharp
[MinMax(0, 16777215), …, Units("km")] uint CF_Clu_Odometer; // physics: the name marks a counter (raw span 0…16777215); if it spends its life near the floor rather than sweeping the whole span, consider [A]
[StartBit(7), BigEndian, PhysRange(-1000, 1000)] short TORQUE_L;  // physics: a two-sided quantity, raw straddles zero (raw span -32768…32767); if the typical excursion is small, consider [X(amplitude)]
[MinMax(0, 511), …, Offset(-15), Units("m/s^2")] ushort DAS_accelMin; // physics: physical zero is raw 375 (raw span 0…511); if values cluster around it, consider [X(amplitude, 375)]
```

The last shape is the one DBC gives away for free: an unsigned signal with a negative `offset` puts physical zero
at a computable raw value *inside* the span, so a quantity that hovers around zero physically hovers around that
raw value — a centre `[X]` can be given explicitly. The shipped samples collect 111 such notes.

The physical `[min|max]` from the DBC stays a separate, purely informational `[PhysRange]`; it describes the scaled
value, never the raw one AdHoc transmits.

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
