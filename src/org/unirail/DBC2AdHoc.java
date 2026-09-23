package org.unirail;

import org.unirail.adhoc.AdHocWriter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.unirail.adhoc.AdHocWriter.I1;
import static org.unirail.adhoc.AdHocWriter.I2;
import static org.unirail.adhoc.AdHocWriter.I3;
import static org.unirail.adhoc.AdHocWriter.brush;
import static org.unirail.adhoc.AdHocWriter.doc;
import static org.unirail.adhoc.AdHocWriter.ident;
import static org.unirail.adhoc.AdHocWriter.num;
import static org.unirail.adhoc.AdHocWriter.str;
import static org.unirail.adhoc.AdHocWriter.unique;

/**
 * Vector CAN database (.dbc) → AdHoc protocol description (.cs) converter.
 *
 * <p>Usage: <code>java -cp out org.unirail.DBC2AdHoc &lt;file.dbc or folder&gt; [output folder]</code>
 * (output defaults to <code>&lt;cwd&gt;/AdHoc</code>). One <code>.cs</code> per <code>.dbc</code>.
 *
 * <p>Mapping in short: every <code>BO_</code> message becomes a pack whose fields are the <code>SG_</code> signals,
 * typed by the smallest integer that holds the signal's bit length and range-limited with <code>[MinMax]</code> so
 * AdHoc bit-packs them like the CAN frame does; physical scaling, units, byte order, multiplexing and cycle times
 * travel as custom attributes; <code>VAL_</code> tables become enums; every <code>BU_</code> node becomes a host and
 * every pair of nodes that exchanges messages gets a connection listing exactly what each side sends.
 */
public class DBC2AdHoc {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("Usage: java -cp out org.unirail.DBC2AdHoc <file.dbc or folder> [output folder]");
			System.out.println("       output folder defaults to <current dir>/AdHoc");
			return;
		}
		Path src = Paths.get(args[0]);
		Path dst = 1 < args.length ? Paths.get(args[1]) : Paths.get(System.getProperty("user.dir"), "AdHoc");
		File[] files = Files.isDirectory(src) ? src.toFile().listFiles((d, n) -> n.toLowerCase().endsWith(".dbc")) : new File[]{src.toFile()};
		if (files == null || files.length == 0) {
			System.err.println("No .dbc files found in `" + src.toAbsolutePath() + "`.");
			System.exit(1);
			return;
		}
		Arrays.sort(files);
		Files.createDirectories(dst);
		int failed = 0;
		for (File f : files)
			try {
				Database db = Parser.parse(f.toPath());
				Path out = dst.resolve(db.name + ".cs");
				Files.write(out, new Emitter(db).emit().getBytes(StandardCharsets.UTF_8));
				System.out.printf("%-32s -> %s  (%d messages, %d signals, %d nodes, %d value tables)%n", f.getName(), out, db.messages.size(), db.signalCount(), db.nodes.size(), db.enumCount());
			} catch (Exception e) {
				failed++;
				System.err.println("FAILED " + f + ": " + e);
				e.printStackTrace();
			}
		if (0 < failed) System.exit(2);
	}

	// ═══════════════════════════════════════════ model ═══════════════════════════════════════════

	static final class Signal {
		String name, mux;          // mux: null, "M" (multiplexor) or "mN" (multiplexed by value N)
		int start, len;
		boolean littleEndian, signed;
		double factor, offset, min, max;
		String unit = "", comment;
		final List<String> receivers = new ArrayList<>();
		int valType;               // SIG_VALTYPE_: 0 integer, 1 float, 2 double
		String startValue;         // BA_ GenSigStartValue
		final LinkedHashMap<Long, String> values = new LinkedHashMap<>(); // VAL_
		final LinkedHashMap<String, String> attrs = new LinkedHashMap<>(); // other BA_
	}

	static final class Message {
		long rawId;
		long id;                   // 11- or 29-bit identifier
		boolean extended;          // bit 31 of the DBC id
		String name, sender, comment, cycleTime;
		int dlc;
		final List<Signal> signals = new ArrayList<>();
		final LinkedHashMap<String, String> attrs = new LinkedHashMap<>();

		Signal signal(String n) {
			for (Signal s : signals) if (s.name.equals(n)) return s;
			return null;
		}
	}

	static final class Database {
		String name, version;
		final LinkedHashMap<String, String> nodes = new LinkedHashMap<>(); // raw node → comment
		final Set<String> impliedNodes = new HashSet<>();                    // referenced by BO_/SG_ but missing from BU_
		final LinkedHashMap<Long, Message> messages = new LinkedHashMap<>();
		final List<String> comments = new ArrayList<>();                    // global CM_
		final LinkedHashMap<String, String> globalAttrs = new LinkedHashMap<>();
		final LinkedHashMap<String, LinkedHashMap<Long, String>> valueTables = new LinkedHashMap<>(); // VAL_TABLE_

		int signalCount() {
			int n = 0;
			for (Message m : messages.values()) n += m.signals.size();
			return n;
		}

		int enumCount() {
			int n = 0;
			for (Message m : messages.values()) for (Signal s : m.signals) if (2 <= s.values.size()) n++;
			return n;
		}
	}

	static boolean unknownNode(String n) { return n == null || n.isEmpty() || n.equals("XXX") || n.equals("Vector__XXX"); }

	// ═══════════════════════════════════════════ parser ═══════════════════════════════════════════

	static final class Parser {
		static final Pattern BO = Pattern.compile("^BO_\\s+(\\d+)\\s+(\\S+?)\\s*:\\s*(\\d+)\\s+(\\S+)");
		static final Pattern SG = Pattern.compile("^SG_\\s+(\\S+)\\s+(M|m\\d+M?)?\\s*:\\s*(\\d+)\\|(\\d+)@([01])([+-])\\s*\\(\\s*([^,]+?)\\s*,\\s*([^)]+?)\\s*\\)\\s*\\[\\s*([^|]*?)\\s*\\|\\s*([^\\]]*?)\\s*\\]\\s*\"([^\"]*)\"\\s*(.*)$");
		static final Pattern CM = Pattern.compile("^CM_\\s+(?:(BU_)\\s+(\\S+)|(BO_)\\s+(\\d+)|(SG_)\\s+(\\d+)\\s+(\\S+)|(EV_)\\s+(\\S+))?\\s*\"(.*)\"\\s*;\\s*$", Pattern.DOTALL);
		static final Pattern VAL = Pattern.compile("^VAL_\\s+(\\d+)\\s+(\\S+)\\s+(.*?)\\s*;\\s*$", Pattern.DOTALL);
		static final Pattern VAL_TABLE = Pattern.compile("^VAL_TABLE_\\s+(\\S+)\\s+(.*?)\\s*;\\s*$", Pattern.DOTALL);
		static final Pattern VAL_ENTRY = Pattern.compile("(-?\\d+)\\s+\"([^\"]*)\"");
		static final Pattern BA = Pattern.compile("^BA_\\s+\"([^\"]+)\"\\s+(?:(BU_)\\s+(\\S+)|(BO_)\\s+(\\d+)|(SG_)\\s+(\\d+)\\s+(\\S+)|(EV_)\\s+(\\S+))?\\s*(.*?)\\s*;\\s*$", Pattern.DOTALL);
		static final Pattern SIG_VALTYPE = Pattern.compile("^SIG_VALTYPE_\\s+(\\d+)\\s+(\\S+)\\s*:\\s*(\\d)\\s*;");
		static final Pattern VERSION = Pattern.compile("^VERSION\\s+\"(.*)\"");
		static final String[] STATEMENT_KEYWORDS = {"CM_", "VAL_", "VAL_TABLE_", "BA_", "BA_DEF_", "BA_DEF_DEF_", "BA_DEF_REL_", "BA_REL_", "BA_DEF_DEF_REL_", "SIG_VALTYPE_", "SG_MUL_VAL_", "EV_", "SIG_GROUP_", "SGTYPE_", "SIG_TYPE_REF_", "ENVVAR_DATA_", "CAT_", "FILTER"};

		static Database parse(Path path) throws Exception {
			Database db = new Database();
			String file = path.getFileName().toString();
			db.name = ident(file.substring(0, file.lastIndexOf('.')));
			List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
			// DBC files are often Latin-1; re-read when UTF-8 decoding produced replacement characters.
			for (String l : lines)
				if (l.indexOf('�') >= 0) {
					lines = Files.readAllLines(path, StandardCharsets.ISO_8859_1);
					break;
				}

			Message current = null;
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).trim();
				if (line.isEmpty()) continue;

				if (line.startsWith("VERSION")) {
					Matcher m = VERSION.matcher(line);
					if (m.find()) db.version = m.group(1);
					continue;
				}
				if (line.startsWith("BU_:")) {
					for (String n : line.substring(4).trim().split("\\s+")) if (!n.isEmpty() && !unknownNode(n)) db.nodes.put(n, null);
					continue;
				}
				if (line.startsWith("BO_ ")) {
					Matcher m = BO.matcher(line);
					if (!m.find()) throw new IllegalStateException(file + ":" + (i + 1) + " cannot parse message: " + line);
					current = new Message();
					current.rawId = Long.parseLong(m.group(1));
					current.extended = (current.rawId & 0x8000_0000L) != 0;
					current.id = current.rawId & 0x1FFF_FFFFL;
					current.name = m.group(2);
					current.dlc = Integer.parseInt(m.group(3));
					current.sender = m.group(4);
					db.messages.put(current.rawId, current);
					continue;
				}
				if (line.startsWith("SG_ ")) {
					Matcher m = SG.matcher(line);
					if (!m.find()) throw new IllegalStateException(file + ":" + (i + 1) + " cannot parse signal: " + line);
					if (current == null) throw new IllegalStateException(file + ":" + (i + 1) + " signal outside of a message");
					Signal s = new Signal();
					s.name = m.group(1);
					s.mux = m.group(2);
					s.start = Integer.parseInt(m.group(3));
					s.len = Integer.parseInt(m.group(4));
					s.littleEndian = m.group(5).equals("1");
					s.signed = m.group(6).equals("-");
					s.factor = Double.parseDouble(m.group(7));
					s.offset = Double.parseDouble(m.group(8));
					s.min = m.group(9).isEmpty() ? 0 : Double.parseDouble(m.group(9));
					s.max = m.group(10).isEmpty() ? 0 : Double.parseDouble(m.group(10));
					s.unit = m.group(11);
					for (String r : m.group(12).trim().split("\\s*,\\s*")) if (!r.isEmpty()) s.receivers.add(r);
					current.signals.add(s);
					continue;
				}

				// `;`-terminated statements may span several lines (comments with line breaks). A bare keyword on a
				// line of its own is an entry of the NS_ header block, not a statement.
				String kw = null;
				for (String k : STATEMENT_KEYWORDS) if (line.startsWith(k + " ") || line.startsWith(k + "\t")) { kw = k; break; }
				if (kw == null) continue; // NS_ block, BS_:, unknown keywords
				StringBuilder stmt = new StringBuilder(line);
				while (!endsStatement(stmt) && i + 1 < lines.size()) stmt.append('\n').append(lines.get(++i).trim());
				statement(db, stmt.toString(), kw);
			}
			// Some databases leave BU_ incomplete (or empty) yet name senders and receivers on BO_/SG_ lines: those
			// nodes take part in the traffic, so they become hosts as well.
			for (Message m : db.messages.values()) {
				if (!unknownNode(m.sender) && !db.nodes.containsKey(m.sender)) { db.nodes.put(m.sender, null); db.impliedNodes.add(m.sender); }
				for (Signal s : m.signals)
					for (String r : s.receivers)
						if (!unknownNode(r) && !db.nodes.containsKey(r)) { db.nodes.put(r, null); db.impliedNodes.add(r); }
			}
			return db;
		}

		/** True when the statement text ends with `;` outside a quoted string. */
		static boolean endsStatement(CharSequence s) {
			boolean quoted = false;
			int lastSemi = -1;
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				if (c == '"') quoted = !quoted;
				else if (c == ';' && !quoted) lastSemi = i;
			}
			if (quoted || lastSemi < 0) return false;
			for (int i = lastSemi + 1; i < s.length(); i++) if (!Character.isWhitespace(s.charAt(i))) return false;
			return true;
		}

		static void statement(Database db, String stmt, String kw) {
			Matcher m;
			switch (kw) {
				case "CM_":
					m = CM.matcher(stmt);
					if (!m.find()) return;
					String text = m.group(10);
					if (m.group(1) != null) { if (db.nodes.containsKey(m.group(2))) db.nodes.put(m.group(2), text); }
					else if (m.group(3) != null) { Message msg = db.messages.get(Long.parseLong(m.group(4))); if (msg != null) msg.comment = text; }
					else if (m.group(5) != null) {
						Message msg = db.messages.get(Long.parseLong(m.group(6)));
						Signal s = msg == null ? null : msg.signal(m.group(7));
						if (s != null) s.comment = text;
					} else if (m.group(8) == null) db.comments.add(text);
					return;
				case "VAL_":
					m = VAL.matcher(stmt);
					if (!m.find()) return;
					Message msg = db.messages.get(Long.parseLong(m.group(1)));
					Signal s = msg == null ? null : msg.signal(m.group(2));
					if (s == null) return;
					Matcher e = VAL_ENTRY.matcher(m.group(3));
					while (e.find()) s.values.putIfAbsent(Long.parseLong(e.group(1)), e.group(2));
					return;
				case "VAL_TABLE_":
					m = VAL_TABLE.matcher(stmt);
					if (!m.find()) return;
					LinkedHashMap<Long, String> t = new LinkedHashMap<>();
					Matcher te = VAL_ENTRY.matcher(m.group(2));
					while (te.find()) t.putIfAbsent(Long.parseLong(te.group(1)), te.group(2));
					db.valueTables.put(m.group(1), t);
					return;
				case "BA_":
					m = BA.matcher(stmt);
					if (!m.find()) return;
					String name = m.group(1), value = unquote(m.group(11));
					if (m.group(4) != null) {
						Message bm = db.messages.get(Long.parseLong(m.group(5)));
						if (bm == null) return;
						if (name.equals("GenMsgCycleTime")) bm.cycleTime = value;
						else bm.attrs.put(name, value);
					} else if (m.group(6) != null) {
						Message bm = db.messages.get(Long.parseLong(m.group(7)));
						Signal bs = bm == null ? null : bm.signal(m.group(8));
						if (bs == null) return;
						if (name.equals("GenSigStartValue")) bs.startValue = value;
						else bs.attrs.put(name, value);
					} else if (m.group(2) == null && m.group(9) == null) db.globalAttrs.put(name, value);
					return;
				case "SIG_VALTYPE_":
					m = SIG_VALTYPE.matcher(stmt);
					if (!m.find()) return;
					Message vm = db.messages.get(Long.parseLong(m.group(1)));
					Signal vs = vm == null ? null : vm.signal(m.group(2));
					if (vs != null) vs.valType = Integer.parseInt(m.group(3));
					return;
				default: // BA_DEF_, BA_DEF_DEF_, EV_, SG_MUL_VAL_, ... carry nothing the descriptor needs
			}
		}

		static String unquote(String v) {
			v = v.trim();
			return 2 <= v.length() && v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : v;
		}
	}

	// ═══════════════════════════════════════════ emitter ═══════════════════════════════════════════

	static final class Emitter {
		static final String BUS = "Bus", UNKNOWN = "UnknownECU";

		final Database db;
		final StringBuilder sb = new StringBuilder(1 << 18);
		final Set<String> topLevel = new HashSet<>();                 // names of packs, enums, hosts, connections, attributes
		final Map<Message, String> packName = new LinkedHashMap<>();
		final Map<Signal, String> enumName = new HashMap<>();         // signals whose VAL_ table became an enum
		final Map<String, String> hostOf = new LinkedHashMap<>();     // raw node → host name

		Emitter(Database db) { this.db = db; }

		String emit() {
			// Reserve names in the order that matters: project, hosts, packs, enums, attributes.
			topLevel.add(db.name);
			for (String a : ATTRIBUTES) topLevel.add(a + "Attribute");
			for (String n : db.nodes.keySet()) hostOf.put(n, unique(n, topLevel));
			hostOf.put(BUS, unique(BUS, topLevel));
			hostOf.put(UNKNOWN, unique(UNKNOWN, topLevel));
			for (Message m : db.messages.values()) packName.put(m, unique(m.name, topLevel));
			for (Message m : db.messages.values())
				for (Signal s : m.signals)
					if (2 <= s.values.size()) enumName.put(s, unique(m.name + "_" + s.name, topLevel));

			List<String> header = new ArrayList<>();
			if (db.version != null && !db.version.isEmpty()) header.add("DBC VERSION \"" + db.version + "\"");
			for (Map.Entry<String, String> a : db.globalAttrs.entrySet()) header.add("DBC attribute " + a.getKey() + " = " + a.getValue());
			for (String c : db.comments) header.add("DBC comment: " + c.replace("\r", "").replace("\n", " "));
			AdHocWriter.fileHeader(sb, "DBC2AdHoc", db.name + ".dbc", header.toArray(new String[0]));
			sb.append("namespace org.dbc {\n");
			dashboard();
			sb.append(I1).append("public interface ").append(db.name).append(" {\n");
			messages();
			enums();
			topology();
			attributes();
			sb.append(I1).append("}\n");
			sb.append("}\n");
			return sb.toString();
		}

		/**
		 * The Packs Inventory carries no id. A pack id is AdHoc's own internal matter — the agent assigns and
		 * maintains it. The CAN identifier describes the <i>source's</i> wire format, which AdHoc replaces with its
		 * own, so it stays inside the pack as {@code can_id} / {@code extended}, where a migration can audit it.
		 */
		void dashboard() {
			Map<String, Integer> ids = new TreeMap<>();
			for (Message m : db.messages.values()) ids.put(packName.get(m), null);
			AdHocWriter.dashboard(sb, I1, ids);
		}

		// ───────────────────────────── messages → packs ─────────────────────────────

		void messages() {
			sb.append('\n').append(I2).append("// ═════════════════════════ messages ═════════════════════════\n");
			for (Message m : db.messages.values()) {
				sb.append('\n');
				StringBuilder d = new StringBuilder();
				if (m.comment != null) d.append(m.comment).append('\n');
				d.append("CAN id 0x").append(Long.toHexString(m.id).toUpperCase()).append(m.extended ? " (29-bit)" : "").append(", DLC ").append(m.dlc)
						.append(", sender ").append(unknownNode(m.sender) ? "unknown" : m.sender);
				doc(sb, I2, d.toString());
				List<String> attrs = new ArrayList<>();
				if (m.cycleTime != null) attrs.add("CycleTime(" + num(m.cycleTime) + ")");
				for (Map.Entry<String, String> a : m.attrs.entrySet()) attrs.add("Attr(" + str(a.getKey()) + ", " + str(a.getValue()) + ")");
				if (!attrs.isEmpty()) sb.append(I2).append('[').append(String.join(", ", attrs)).append("]\n");
				String pack = packName.get(m);
				sb.append(I2).append("class ").append(pack).append(" {\n");
				// Source identity as metadata, never as protocol structure: AdHoc numbers the pack itself.
				sb.append(I3).append("public const uint can_id = 0x").append(Long.toHexString(m.id).toUpperCase()).append(";\n");
				sb.append(I3).append("public const bool extended = ").append(m.extended).append(";\n");
				sb.append(I3).append("public const int dlc = ").append(m.dlc).append(";\n");
				if (!unknownNode(m.sender)) sb.append(I3).append("public const string sender = ").append(str(m.sender)).append(";\n");

				Set<String> members = new HashSet<>(Arrays.asList(pack, "can_id", "extended", "dlc", "sender"));
				for (Signal s : m.signals) {
					doc(sb, I3, signalDoc(s));
					String field = unique(s.name, members);
					sb.append(I3).append(signalField(s)).append(' ').append(field).append(";").append(droppedNote(s)).append("\n");
				}
				sb.append(I2).append("}\n");
			}
		}

		/**
		 * A readable trail for what the converter could not express, left at the place it was dropped. AdHoc rejects
		 * an enum with fewer than two constants, so a one-entry {@code VAL_} table survives only as this note.
		 */
		static String droppedNote(Signal s) {
			if (s.values.size() != 1) return "";
			Map.Entry<Long, String> only = s.values.entrySet().iterator().next();
			return " // dropped DBC VAL_ " + only.getKey() + " \"" + only.getValue().replace("\r", " ").replace("\n", " ")
					+ "\": a single named value, too few for an AdHoc enum";
		}

		static String signalDoc(Signal s) {
			StringBuilder d = new StringBuilder();
			if (s.comment != null) d.append(s.comment).append('\n');
			if (s.factor != 1 || s.offset != 0) {
				d.append("physical = raw * ").append(trim(s.factor));
				if (s.offset != 0) d.append(s.offset < 0 ? " - " : " + ").append(trim(Math.abs(s.offset)));
				if (!s.unit.isEmpty()) d.append(' ').append(s.unit);
				d.append('\n');
			}
			return d.toString();
		}

		/** Attributes plus type of a signal field. */
		String signalField(Signal s) {
			List<String> attrs = new ArrayList<>();
			String type;
			boolean fullWidth = s.len == 8 || s.len == 16 || s.len == 32 || s.len == 64;
			if (s.valType == 1) type = "float";
			else if (s.valType == 2) type = "double";
			else if (enumName.containsKey(s) && coversRange(s)) type = enumName.get(s);
			else if (!s.signed && s.len == 1) type = "bool";
			else {
				type = s.signed ? (s.len <= 8 ? "sbyte" : s.len <= 16 ? "short" : s.len <= 32 ? "int" : "long")
						: (s.len <= 8 ? "byte" : s.len <= 16 ? "ushort" : s.len <= 32 ? "uint" : "ulong");
				if (!fullWidth && s.len < 64)
					attrs.add(s.signed ? "MinMax(" + (-(1L << (s.len - 1))) + ", " + ((1L << (s.len - 1)) - 1) + ")"
							: "MinMax(0, " + ((1L << s.len) - 1) + ")");
			}
			attrs.add("StartBit(" + s.start + ")");
			if (!s.littleEndian) attrs.add("BigEndian");
			if (s.factor != 1) attrs.add("Factor(" + trim(s.factor) + ")");
			if (s.offset != 0) attrs.add("Offset(" + trim(s.offset) + ")");
			if (s.min != 0 || s.max != 0) attrs.add("PhysRange(" + trim(s.min) + ", " + trim(s.max) + ")");
			if (!s.unit.isEmpty()) attrs.add("Units(" + str(s.unit) + ")");
			if (s.mux != null) {
				if (s.mux.equals("M")) attrs.add("Multiplexor");
				else {
					attrs.add("Multiplexed(" + s.mux.substring(1).replace("M", "") + ")");
					if (s.mux.endsWith("M")) attrs.add("Multiplexor");
				}
			}
			if (s.startValue != null) attrs.add("StartValue(" + num(s.startValue) + ")");
			if (enumName.containsKey(s) && !type.equals(enumName.get(s))) attrs.add("ValueTable(" + str(enumName.get(s)) + ")");
			for (Map.Entry<String, String> a : s.attrs.entrySet()) attrs.add("Attr(" + str(a.getKey()) + ", " + str(a.getValue()) + ")");
			// A multiplexed signal is present only for one multiplexor value: optional, one bit when absent.
			if (s.mux != null && !s.mux.equals("M")) type += "?";
			return "[" + String.join(", ", attrs) + "] " + type;
		}

		/** True when the VAL_ table names every raw value the signal can carry, so the enum can be the field type. */
		static boolean coversRange(Signal s) {
			if (s.len >= 63 || s.valType != 0) return false;
			long lo = s.signed ? -(1L << (s.len - 1)) : 0;
			long hi = s.signed ? (1L << (s.len - 1)) - 1 : (1L << s.len) - 1;
			if (hi - lo + 1 != s.values.size()) return false;
			for (long v = lo; v <= hi; v++) if (!s.values.containsKey(v)) return false;
			return true;
		}

		static String trim(double v) {
			if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
			return Double.toString(v);
		}

		// ───────────────────────────── VAL_ tables → enums ─────────────────────────────

		void enums() {
			if (enumName.isEmpty()) return;
			sb.append('\n').append(I2).append("// ═════════════════════════ value tables (VAL_) ═════════════════════════\n");
			for (Message m : db.messages.values())
				for (Signal s : m.signals) {
					String en = enumName.get(s);
					if (en == null) continue;
					sb.append('\n');
					doc(sb, I2, "Values of " + packName.get(m) + "." + s.name);
					long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
					for (long v : s.values.keySet()) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
					String under = lo < Integer.MIN_VALUE || Integer.MAX_VALUE < hi ? " : long" : "";
					sb.append(I2).append("enum ").append(en).append(under).append(" {\n");
					Set<String> members = new HashSet<>();
					members.add(en);
					for (Map.Entry<Long, String> e : s.values.entrySet()) {
						String desc = e.getValue().trim();
						String member = unique(desc.isEmpty() ? "VALUE_" + e.getKey() : desc, members);
						if (!desc.equals(member)) doc(sb, I3, desc);
						sb.append(I3).append(member).append(" = ").append(e.getKey()).append(",\n");
					}
					sb.append(I2).append("}\n");
				}
		}

		// ───────────────────────────── nodes → hosts, message flows → connections ─────────────────────────────

		void topology() {
			// flows: sender host → receiver host → messages
			Map<String, Map<String, List<String>>> flows = new LinkedHashMap<>();
			Set<String> usedHosts = new LinkedHashSet<>();
			for (Message m : db.messages.values()) {
				String from = unknownNode(m.sender) || !hostOf.containsKey(m.sender) ? hostOf.get(UNKNOWN) : hostOf.get(m.sender);
				Set<String> to = new LinkedHashSet<>();
				for (Signal s : m.signals)
					for (String r : s.receivers)
						if (!unknownNode(r) && hostOf.containsKey(r) && !hostOf.get(r).equals(from)) to.add(hostOf.get(r));
				if (to.isEmpty()) to.add(from.equals(hostOf.get(BUS)) ? hostOf.get(UNKNOWN) : hostOf.get(BUS));
				for (String t : to) {
					flows.computeIfAbsent(from, k -> new LinkedHashMap<>()).computeIfAbsent(t, k -> new ArrayList<>()).add(packName.get(m));
					usedHosts.add(from);
					usedHosts.add(t);
				}
			}

			sb.append('\n').append(I2).append("// ═════════════════════════ nodes (BU_) → hosts ═════════════════════════\n\n");
			for (Map.Entry<String, String> n : db.nodes.entrySet())
				AdHocWriter.host(sb, I2, hostOf.get(n.getKey()),
						"DBC node " + n.getKey() + (db.impliedNodes.contains(n.getKey()) ? " (not declared in BU_, implied by BO_/SG_)" : "")
								+ (n.getValue() == null ? "" : ": " + n.getValue().replace("\r", "").replace("\n", " ")));
			if (usedHosts.contains(hostOf.get(BUS)))
				AdHocWriter.host(sb, I2, hostOf.get(BUS), "Synthetic: receives every message whose DBC receivers are unknown (Vector__XXX)");
			if (usedHosts.contains(hostOf.get(UNKNOWN)))
				AdHocWriter.host(sb, I2, hostOf.get(UNKNOWN), "Synthetic: sends every message whose DBC sender is unknown (Vector__XXX)");

			sb.append(I2).append("// ═════════════════════════ connections: one per pair of nodes that exchange messages ═════════════════════════\n\n");
			Set<String> done = new HashSet<>();
			for (Map.Entry<String, Map<String, List<String>>> f : flows.entrySet())
				for (String to : f.getValue().keySet()) {
					String a = f.getKey(), b = to;
					String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
					if (!done.add(key)) continue;
					String left = a.compareTo(b) < 0 ? a : b, right = left.equals(a) ? b : a;
					List<String> l2r = flows.getOrDefault(left, new LinkedHashMap<>()).getOrDefault(right, new ArrayList<>());
					List<String> r2l = flows.getOrDefault(right, new LinkedHashMap<>()).getOrDefault(left, new ArrayList<>());
					String conn = unique(left + "_to_" + right, topLevel);
					sb.append(I2).append("interface ").append(conn).append(" : Connects<").append(left).append(", ").append(right).append("> {\n");
					if (!l2r.isEmpty()) sb.append(I3).append("[l____________<").append(AdHocWriter.tuple(l2r)).append(">]\n");
					if (!r2l.isEmpty()) sb.append(I3).append("[____________r<").append(AdHocWriter.tuple(r2l)).append(">]\n");
					sb.append(I3).append("struct Traffic { }\n");
					sb.append(I2).append("}\n\n");
				}
		}

		// ───────────────────────────── custom attribute declarations ─────────────────────────────

		static final String[] ATTRIBUTES = {"StartBit", "BigEndian", "Factor", "Offset", "PhysRange", "Units", "Multiplexor", "Multiplexed", "StartValue", "CycleTime", "ValueTable", "Attr"};

		void attributes() {
			sb.append(I2).append("// ═════════════════════════ DBC metadata attributes ═════════════════════════\n\n");
			sb.append(I2).append("// AdHoc custom attributes: carried into the generated code as constants attached to the field / pack.\n");
			sb.append(I2).append("// They live inside the project interface (the agent expects every class in a project); the connections use\n");
			sb.append(I2).append("// explicit pack lists, so they are never collected as packs.\n\n");
			AdHocWriter.attribute(sb, I2, "StartBit", "Start bit of the signal in the CAN frame, as written in the DBC (LSB for Intel, MSB for Motorola).", "int bit");
			AdHocWriter.attribute(sb, I2, "BigEndian", "Motorola byte order (DBC @0); absent means Intel / little-endian (@1).");
			AdHocWriter.attribute(sb, I2, "Factor", "Physical value = raw * factor + offset.", "double factor");
			AdHocWriter.attribute(sb, I2, "Offset", "Physical value = raw * factor + offset.", "double offset");
			AdHocWriter.attribute(sb, I2, "PhysRange", "Physical [min|max] declared in the DBC (a UI hint, not a wire constraint).", "double min, double max");
			AdHocWriter.attribute(sb, I2, "Units", "Unit of the physical value.", "string units");
			AdHocWriter.attribute(sb, I2, "Multiplexor", "This signal selects which multiplexed signals are present.");
			AdHocWriter.attribute(sb, I2, "Multiplexed", "Present only when the multiplexor carries this value.", "long value");
			AdHocWriter.attribute(sb, I2, "StartValue", "GenSigStartValue: raw value at ECU start.", "double value", "string value");
			AdHocWriter.attribute(sb, I2, "CycleTime", "GenMsgCycleTime: transmission period in milliseconds.", "double ms", "string ms");
			AdHocWriter.attribute(sb, I2, "ValueTable", "Name of the enum listing the named raw values (used when the table does not cover the whole range).", "string enumName");
			sb.append(I2).append("/** Any other DBC attribute (BA_) of the message or signal. */\n");
			sb.append(I2).append("[AttributeUsage(AttributeTargets.All, AllowMultiple = true)]\n");
			sb.append(I2).append("public class AttrAttribute : Attribute { public AttrAttribute(string name, string value) { } }\n");
		}
	}
}
