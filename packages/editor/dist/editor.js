import { defineComponent as qe, openBlock as i, createElementBlock as u, normalizeClass as te, createCommentVNode as $, renderSlot as Xn, inject as rs, computed as O, ref as N, onBeforeUnmount as Tn, createVNode as ve, unref as p, createElementVNode as o, withModifiers as le, toDisplayString as m, Fragment as U, renderList as Y, normalizeStyle as Ee, createBlock as xt, reactive as vn, onMounted as Go, watch as Se, createTextVNode as we, withDirectives as z, vModelText as ae, nextTick as je, onUnmounted as Io, Teleport as bn, Transition as is, withCtx as _e, vModelSelect as it, withKeys as Ze, provide as us, mergeProps as Bo, normalizeProps as cs, guardReactiveProps as ds, shallowRef as ps, vShow as Xt } from "vue";
import { useI18n as ft } from "vue-i18n";
import { Handle as Ct, Position as $t, useVueFlow as fs, VueFlow as ms } from "@vue-flow/core";
import { Background as gs } from "@vue-flow/background";
import { Controls as vs } from "@vue-flow/controls";
import { MiniMap as hs } from "@vue-flow/minimap";
import Va from "dompurify";
import { defineStore as Co } from "pinia";
import { HighlightStyle as ys, StreamLanguage as bs, LanguageSupport as ws, syntaxHighlighting as la, defaultHighlightStyle as _s } from "@codemirror/language";
import { tags as Re } from "@lezer/highlight";
import { EditorView as Ko, lineNumbers as ks, highlightActiveLine as Cs, highlightSpecialChars as $s, keymap as Ts } from "@codemirror/view";
import { Compartment as Es, EditorState as Wn } from "@codemirror/state";
import { history as Ss, defaultKeymap as Ls, historyKeymap as Vs } from "@codemirror/commands";
const As = ["disabled"], Ns = {
  key: 0,
  class: "spinner"
}, Rs = /* @__PURE__ */ qe({
  __name: "BaseButton",
  props: {
    variant: {},
    size: {},
    loading: { type: Boolean },
    disabled: { type: Boolean }
  },
  emits: ["click"],
  setup(e) {
    return (a, r) => (i(), u("button", {
      class: te(["btn", [`btn-${e.variant || "primary"}`, `btn-${e.size || "md"}`]]),
      disabled: e.disabled || e.loading,
      onClick: r[0] || (r[0] = (c) => a.$emit("click", c))
    }, [
      e.loading ? (i(), u("span", Ns)) : $("", !0),
      Xn(a.$slots, "default", {}, void 0, !0)
    ], 10, As));
  }
}), ze = (e, a) => {
  const r = e.__vccOpts || e;
  for (const [c, g] of a)
    r[c] = g;
  return r;
}, Oe = /* @__PURE__ */ ze(Rs, [["__scopeId", "data-v-de5bd678"]]), ra = 1;
function Fs(e) {
  const a = e.schema_version;
  if (a == null)
    throw new Error("RecordedFlow missing schema_version");
  if (typeof a != "number" || !Number.isInteger(a))
    throw new Error(`RecordedFlow schema_version must be integer, got ${typeof a}`);
  if (a > ra)
    throw new Error(
      `RecordedFlow schema_version ${a} newer than supported ${ra}. 请升级 Robot Autotest。`
    );
  if (a < 1)
    throw new Error(`RecordedFlow schema_version must be >= 1, got ${a}`);
}
function Ps(e) {
  return e.selector_candidates.length === 0 ? null : e.selector_candidates[e.active_candidate_index] ?? null;
}
function hn(e) {
  return e >= 80 ? "good" : e >= 50 ? "ok" : "poor";
}
const Ds = /^(Given|When|Then|And|But)\s+(.+)$/i;
function Aa(e) {
  const a = Ds.exec((e ?? "").trim());
  return a ? { prefix: a[1].charAt(0).toUpperCase() + a[1].slice(1).toLowerCase(), rest: a[2].trim() } : null;
}
function Ms(e) {
  let a = 0, r = !1, c = !1, g = -1;
  for (let s = 0; s < e.length; s++) {
    const _ = e[s];
    if (r) {
      _ === "'" && e[s - 1] !== "\\" && (r = !1);
      continue;
    }
    if (c) {
      _ === '"' && e[s - 1] !== "\\" && (c = !1);
      continue;
    }
    if (_ === "'") {
      r = !0;
      continue;
    }
    if (_ === '"') {
      c = !0;
      continue;
    }
    if (_ === "(" || _ === "[" || _ === "{") {
      a++;
      continue;
    }
    if (_ === ")" || _ === "]" || _ === "}") {
      a--;
      continue;
    }
    if (a === 0 && _ === "=") {
      if (e[s - 1] === " " && e[s + 1] === " ") return s;
      g === -1 && (g = s);
    }
  }
  return g;
}
function oa(e) {
  const a = e.trim(), r = (T) => ({
    name: "",
    type: null,
    defaultValue: null,
    kind: T
  });
  if (a === "/") return r("positional-only-sep");
  if (a === "*") return r("named-only-sep");
  if (a === "?") return r("optional-sep");
  let c = "positional", g = a;
  g.startsWith("**") ? (c = "kwargs", g = g.slice(2)) : g.startsWith("*") && (c = "varargs", g = g.slice(1));
  let s = null, _ = g;
  const b = Ms(g);
  if (b >= 0) {
    const T = g.slice(b + 1).trim();
    s = T === "" ? null : T, _ = g.slice(0, b).trimEnd();
  }
  s !== null && c === "positional" && (c = "optional");
  let k = _, V = null;
  const L = _.indexOf(":");
  return L >= 0 && (k = _.slice(0, L).trim(), V = _.slice(L + 1).trim(), V === "" && (V = null)), { name: k, type: V, defaultValue: s, kind: c };
}
function xs(e, a) {
  const r = [];
  let c = "", g = 0, s = !1, _ = !1;
  for (let b = 0; b < e.length; b++) {
    const k = e[b];
    if ((s || _) && k === "\\" && b + 1 < e.length) {
      c += k + e[b + 1], b++;
      continue;
    }
    if (s) {
      k === "'" && (s = !1), c += k;
      continue;
    }
    if (_) {
      k === '"' && (_ = !1), c += k;
      continue;
    }
    if (k === "'") {
      s = !0, c += k;
      continue;
    }
    if (k === '"') {
      _ = !0, c += k;
      continue;
    }
    if (k === "[" || k === "(" || k === "{") {
      g++, c += k;
      continue;
    }
    if (k === "]" || k === ")" || k === "}") {
      g--, c += k;
      continue;
    }
    if (g === 0 && k === a) {
      r.push(c), c = "";
      continue;
    }
    c += k;
  }
  return r.push(c), r;
}
function Us(e) {
  const a = /^(?:Literal|OneOf)\s*\[(.+)\]\s*$/is.exec(e);
  if (!a) return null;
  const r = a[1].trim();
  if (!r) return [];
  const c = [];
  let g = "", s = !1, _ = !1, b = 0;
  for (let k = 0; k < r.length; k++) {
    const V = r[k];
    if ((s || _) && V === "\\" && k + 1 < r.length) {
      g += V + r[k + 1], k++;
      continue;
    }
    if (s) {
      V === "'" ? s = !1 : g += V;
      continue;
    }
    if (_) {
      V === '"' ? _ = !1 : g += V;
      continue;
    }
    if (V === "'") {
      s = !0;
      continue;
    }
    if (V === '"') {
      _ = !0;
      continue;
    }
    if (V === "[" || V === "(" || V === "{") {
      b++, g += V;
      continue;
    }
    if (V === "]" || V === ")" || V === "}") {
      b--, g += V;
      continue;
    }
    if (V === "," && b === 0) {
      g.trim() && c.push(g.trim()), g = "";
      continue;
    }
    g += V;
  }
  return g.trim() && c.push(g.trim()), c;
}
function wn(e) {
  const a = (e ?? "").trim(), r = /^Optional\s*\[\s*(.+)\s*\]\s*$/is.exec(a);
  if (r)
    return { ...wn(r[1]), optional: !0, raw: a };
  if (a.includes("|")) {
    const V = xs(a, "|").map((T) => T.trim()).filter(Boolean);
    if (V.some((T) => T === "None") && V.length > 1) {
      const T = V.filter((I) => I !== "None").join(" | ");
      return { ...wn(T), optional: !0, raw: a };
    }
  }
  const c = a.replace(/^\*\*?/, "").trim(), g = Us(c);
  let s = "?", _ = "flowEditor.argTypes.unknown", b = "text", k = null;
  return a === "" || (g ? (s = "▼", _ = "flowEditor.argTypes.choice", b = "select", k = g) : /^str$/i.test(c) ? (s = "Aa", _ = "flowEditor.argTypes.text", b = "text") : /^int$/i.test(c) ? (s = "123", _ = "flowEditor.argTypes.integer", b = "integer") : /^float$|^number$/i.test(c) ? (s = "1.0", _ = "flowEditor.argTypes.number", b = "number") : /^bool(ean)?$/i.test(c) ? (s = "✓", _ = "flowEditor.argTypes.yesNo", b = "checkbox") : /^timedelta$/i.test(c) ? (s = "⏱", _ = "flowEditor.argTypes.duration", b = "duration") : /^(pathlib\.)?path$/i.test(c) ? (s = "📁", _ = "flowEditor.argTypes.path", b = "text") : /^any$/i.test(c) ? (s = "*", _ = "flowEditor.argTypes.any", b = "text") : /^(dict|list|tuple|set|sequence|mapping|iterable)\b/i.test(c) && (s = "[ ]", _ = "flowEditor.argTypes.collection", b = "text")), { icon: s, labelKey: _, control: b, optional: !1, choices: k, raw: a };
}
const Bs = /* @__PURE__ */ new Set(["true", "yes", "on", "1"]);
function ia(e) {
  return e ? Bs.has(e.trim().toLowerCase()) : !1;
}
function ua(e) {
  return e ? "True" : "False";
}
function Ks(e) {
  return e ? /^[$@&]\{.+\}$/.test(e.trim()) : !1;
}
function Na(e, a, r) {
  const c = () => r("flowEditor.argLabels.fallback", { n: a + 1 });
  if (!e || e.length === 0) return c();
  const g = e.findIndex((b) => b.kind === "varargs"), s = e[a];
  if (s)
    return s.kind === "varargs" ? "1" : s.kind === "kwargs" ? r("flowEditor.argLabels.extraNamed") : s.name ? s.name : c();
  const _ = e[e.length - 1];
  return g >= 0 && (_ == null ? void 0 : _.kind) === "varargs" ? String(a - g + 1) : (_ == null ? void 0 : _.kind) === "kwargs" ? r("flowEditor.argLabels.extraNamed") : c();
}
const wo = /* @__PURE__ */ new Map([
  // =====================================================================
  // BuiltIn Library
  // =====================================================================
  ["call method", ["object", "method_name", "*args"]],
  ["catenate", ["*items"]],
  ["comment", ["*messages"]],
  ["continue for loop", []],
  ["continue for loop if", ["condition"]],
  ["convert to binary", ["item", "base=None", "prefix=None", "length=None"]],
  ["convert to boolean", ["item"]],
  ["convert to bytes", ["input", "input_type=text"]],
  ["convert to hex", ["item", "base=None", "prefix=None", "length=None", "lowercase=False"]],
  ["convert to integer", ["item", "base=None"]],
  ["convert to number", ["item", "precision=None"]],
  ["convert to octal", ["item", "base=None", "prefix=None", "length=None"]],
  ["convert to string", ["item"]],
  ["create dictionary", ["*items"]],
  ["create list", ["*items"]],
  ["evaluate", ["expression", "modules=None", "namespace=None"]],
  ["exit for loop", []],
  ["exit for loop if", ["condition"]],
  ["fail", ["msg=None", "*tags"]],
  ["fatal error", ["msg=None"]],
  ["get count", ["container", "item"]],
  ["get length", ["item"]],
  ["get library instance", ["name", "all=False"]],
  ["get time", ["format=timestamp", "time_=NOW"]],
  ["get variable value", ["name", "default=None"]],
  ["get variables", ["no_decoration=False"]],
  ["import library", ["name", "*args"]],
  ["import resource", ["path"]],
  ["import variables", ["path", "*args"]],
  ["keyword should exist", ["name", "msg=None"]],
  ["length should be", ["item", "length", "msg=None"]],
  ["log", ["message", "level=INFO", "html=False", "console=False", "repr=False"]],
  ["log many", ["*messages"]],
  ["log to console", ["message", "stream=STDOUT", "no_newline=False"]],
  ["log variables", ["level=INFO"]],
  ["no operation", []],
  ["pass execution", ["message", "*tags"]],
  ["pass execution if", ["condition", "message", "*tags"]],
  ["regexp escape", ["*patterns"]],
  ["reload library", ["name_or_instance"]],
  ["remove tags", ["*tags"]],
  ["repeat keyword", ["repeat", "name", "*args"]],
  ["replace variables", ["text"]],
  ["return from keyword", ["*return_values"]],
  ["return from keyword if", ["condition", "*return_values"]],
  ["run keyword", ["name", "*args"]],
  ["run keyword and continue on failure", ["name", "*args"]],
  ["run keyword and expect error", ["expected_error", "name", "*args"]],
  ["run keyword and ignore error", ["name", "*args"]],
  ["run keyword and return", ["name", "*args"]],
  ["run keyword and return if", ["condition", "name", "*args"]],
  ["run keyword and return status", ["name", "*args"]],
  ["run keyword and warn on failure", ["name", "*args"]],
  ["run keyword if", ["condition", "name", "*args"]],
  ["run keyword if all tests passed", ["name", "*args"]],
  ["run keyword if any tests failed", ["name", "*args"]],
  ["run keyword if test failed", ["name", "*args"]],
  ["run keyword if test passed", ["name", "*args"]],
  ["run keyword if timeout occurred", ["name", "*args"]],
  ["run keyword unless", ["condition", "name", "*args"]],
  ["run keywords", ["*keywords"]],
  ["set global variable", ["name", "*values"]],
  ["set library search order", ["*search_order"]],
  ["set local variable", ["name", "*values"]],
  ["set log level", ["level"]],
  ["set suite documentation", ["doc", "append=False", "top=False"]],
  ["set suite metadata", ["name", "value", "append=False", "top=False"]],
  ["set suite variable", ["name", "*values"]],
  ["set tags", ["*tags"]],
  ["set task variable", ["name", "*values"]],
  ["set test documentation", ["doc", "append=False"]],
  ["set test message", ["message", "append=False"]],
  ["set test variable", ["name", "*values"]],
  ["set variable", ["*values"]],
  ["set variable if", ["condition", "*values"]],
  ["should be empty", ["item", "msg=None"]],
  ["should be equal", ["first", "second", "msg=None", "values=True", "ignore_case=False"]],
  ["should be equal as integers", ["first", "second", "msg=None", "values=True", "base=None"]],
  ["should be equal as numbers", ["first", "second", "msg=None", "values=True", "precision=6"]],
  ["should be equal as strings", ["first", "second", "msg=None", "values=True", "ignore_case=False"]],
  ["should be true", ["condition", "msg=None"]],
  ["should contain", ["container", "item", "msg=None", "values=True", "ignore_case=False"]],
  ["should contain any", ["container", "*items", "msg=None"]],
  ["should contain x times", ["container", "item", "count", "msg=None", "ignore_case=False"]],
  ["should end with", ["str1", "str2", "msg=None", "values=True", "ignore_case=False"]],
  ["should match", ["string", "pattern", "msg=None", "values=True", "ignore_case=False"]],
  ["should match regexp", ["string", "pattern", "msg=None", "values=True"]],
  ["should not be empty", ["item", "msg=None"]],
  ["should not be equal", ["first", "second", "msg=None", "values=True", "ignore_case=False"]],
  ["should not be equal as integers", ["first", "second", "msg=None", "values=True", "base=None"]],
  ["should not be equal as numbers", ["first", "second", "msg=None", "values=True", "precision=6"]],
  ["should not be equal as strings", ["first", "second", "msg=None", "values=True", "ignore_case=False"]],
  ["should not be true", ["condition", "msg=None"]],
  ["should not contain", ["container", "item", "msg=None", "values=True", "ignore_case=False"]],
  ["should not contain any", ["container", "*items", "msg=None"]],
  ["should not end with", ["str1", "str2", "msg=None", "values=True", "ignore_case=False"]],
  ["should not match", ["string", "pattern", "msg=None", "values=True", "ignore_case=False"]],
  ["should not match regexp", ["string", "pattern", "msg=None", "values=True"]],
  ["should not start with", ["str1", "str2", "msg=None", "values=True", "ignore_case=False"]],
  ["should start with", ["str1", "str2", "msg=None", "values=True", "ignore_case=False"]],
  ["skip", ["msg=None"]],
  ["skip if", ["condition", "msg=None"]],
  ["sleep", ["time_", "reason=None"]],
  ["variable should exist", ["name", "msg=None"]],
  ["variable should not exist", ["name", "msg=None"]],
  ["wait until keyword succeeds", ["retry", "retry_interval", "name", "*args"]],
  // =====================================================================
  // String Library
  // =====================================================================
  ["convert to lower case", ["string"]],
  ["convert to upper case", ["string"]],
  ["decode bytes to string", ["bytes", "encoding=UTF-8"]],
  ["encode string to bytes", ["string", "encoding=UTF-8"]],
  ["fetch from left", ["string", "marker"]],
  ["fetch from right", ["string", "marker"]],
  ["format string", ["template", "*args", "**kwargs"]],
  ["generate random string", ["length=8", "chars=[LETTERS][NUMBERS]"]],
  ["get line", ["string", "line_number"]],
  ["get line count", ["string"]],
  ["get lines containing string", ["string", "pattern", "case_insensitive=False"]],
  ["get lines matching pattern", ["string", "pattern", "case_insensitive=False"]],
  ["get lines matching regexp", ["string", "pattern"]],
  ["get regexp matches", ["string", "pattern", "*groups"]],
  ["get substring", ["string", "start", "end=None"]],
  ["remove string", ["string", "*removables"]],
  ["remove string using regexp", ["string", "*patterns"]],
  ["replace string", ["string", "search_for", "replace_with", "count=-1"]],
  ["replace string using regexp", ["string", "pattern", "replace_with", "count=0"]],
  ["should be byte string", ["item", "msg=None"]],
  ["should be lower case", ["string", "msg=None"]],
  ["should be string", ["item", "msg=None"]],
  ["should be title case", ["string", "msg=None", "exclude=None"]],
  ["should be unicode string", ["item", "msg=None"]],
  ["should be upper case", ["string", "msg=None"]],
  ["should not be string", ["item", "msg=None"]],
  ["split string", ["string", "separator=None", "max_split=-1"]],
  ["split string from right", ["string", "separator=None", "max_split=-1"]],
  ["split string to characters", ["string"]],
  ["split to lines", ["string", "start=0", "end=None"]],
  ["strip string", ["string", "mode=both", "characters=None"]],
  // =====================================================================
  // Collections Library
  // =====================================================================
  ["append to list", ["list_", "*values"]],
  ["combine lists", ["*lists"]],
  ["convert to list", ["item"]],
  ["copy dictionary", ["dictionary", "deepcopy=False"]],
  ["copy list", ["list_", "deepcopy=False"]],
  ["count values in list", ["list_", "value", "start=0", "end=None"]],
  ["dictionaries should be equal", ["dict1", "dict2", "msg=None", "values=True"]],
  ["dictionary should contain item", ["dictionary", "key", "value", "msg=None"]],
  ["dictionary should contain key", ["dictionary", "key", "msg=None"]],
  ["dictionary should contain sub dictionary", ["dict1", "dict2", "msg=None", "values=True"]],
  ["dictionary should contain value", ["dictionary", "value", "msg=None"]],
  ["dictionary should not contain key", ["dictionary", "key", "msg=None"]],
  ["dictionary should not contain value", ["dictionary", "value", "msg=None"]],
  ["get dictionary items", ["dictionary", "sort_keys=True"]],
  ["get dictionary keys", ["dictionary", "sort_keys=True"]],
  ["get dictionary values", ["dictionary", "sort_keys=True"]],
  ["get from dictionary", ["dictionary", "key", "default=None"]],
  ["get from list", ["list_", "index"]],
  ["get match count", ["list", "pattern", "case_insensitive=False", "whitespace_insensitive=False"]],
  ["get matches", ["list", "pattern", "case_insensitive=False", "whitespace_insensitive=False"]],
  ["get slice from list", ["list_", "start=0", "end=None"]],
  ["insert into list", ["list_", "index", "value"]],
  ["keep in dictionary", ["dictionary", "*keys"]],
  ["list should contain sub list", ["list1", "list2", "msg=None", "values=True"]],
  ["list should contain value", ["list_", "value", "msg=None"]],
  ["list should not contain duplicates", ["list_", "msg=None"]],
  ["list should not contain value", ["list_", "value", "msg=None"]],
  ["lists should be equal", ["list1", "list2", "msg=None", "values=True", "names=None"]],
  ["log dictionary", ["dictionary", "level=INFO"]],
  ["log list", ["list_", "level=INFO"]],
  ["pop from dictionary", ["dictionary", "key", "default="]],
  ["remove duplicates", ["list_"]],
  ["remove from dictionary", ["dictionary", "*keys"]],
  ["remove from list", ["list_", "index"]],
  ["remove values from list", ["list_", "*values"]],
  ["reverse list", ["list_"]],
  ["set list value", ["list_", "index", "value"]],
  ["set to dictionary", ["dictionary", "*key_value_pairs"]],
  ["should contain match", ["list", "pattern", "msg=None", "case_insensitive=False", "whitespace_insensitive=False"]],
  ["should not contain match", ["list", "pattern", "msg=None", "case_insensitive=False", "whitespace_insensitive=False"]],
  ["sort list", ["list_"]],
  // =====================================================================
  // DateTime Library
  // =====================================================================
  ["add time to date", ["date", "time", "result_format=timestamp", "exclude_millis=False", "date_format=None"]],
  ["add time to time", ["time1", "time2", "result_format=number", "exclude_millis=False"]],
  ["convert date", ["date", "result_format=timestamp", "exclude_millis=False", "date_format=None"]],
  ["convert time", ["time", "result_format=number", "exclude_millis=False"]],
  ["get current date", ["time_zone=local", "increment=0", "result_format=timestamp", "exclude_millis=False"]],
  ["subtract date from date", ["date1", "date2", "result_format=number", "exclude_millis=False", "date1_format=None", "date2_format=None"]],
  ["subtract time from date", ["date", "time", "result_format=timestamp", "exclude_millis=False", "date_format=None"]],
  ["subtract time from time", ["time1", "time2", "result_format=number", "exclude_millis=False"]],
  // =====================================================================
  // OperatingSystem Library
  // =====================================================================
  ["append to environment variable", ["name", "*values", "separator="]],
  ["append to file", ["path", "content", "encoding=UTF-8"]],
  ["copy directory", ["source", "destination"]],
  ["copy file", ["source", "destination"]],
  ["copy files", ["*sources_and_destination"]],
  ["count directories in directory", ["path", "pattern=None"]],
  ["count files in directory", ["path", "pattern=None"]],
  ["count items in directory", ["path", "pattern=None"]],
  ["create binary file", ["path", "content"]],
  ["create directory", ["path"]],
  ["create file", ["path", "content=", "encoding=UTF-8"]],
  ["directory should be empty", ["path", "msg=None"]],
  ["directory should exist", ["path", "msg=None"]],
  ["directory should not be empty", ["path", "msg=None"]],
  ["directory should not exist", ["path", "msg=None"]],
  ["empty directory", ["path"]],
  ["environment variable should be set", ["name", "msg=None"]],
  ["environment variable should not be set", ["name", "msg=None"]],
  ["file should be empty", ["path", "msg=None"]],
  ["file should exist", ["path", "msg=None"]],
  ["file should not be empty", ["path", "msg=None"]],
  ["file should not exist", ["path", "msg=None"]],
  ["get binary file", ["path"]],
  ["get environment variable", ["name", "default=None"]],
  ["get environment variables", []],
  ["get file", ["path", "encoding=UTF-8"]],
  ["get file size", ["path"]],
  ["get modified time", ["path", "format=timestamp"]],
  ["grep file", ["path", "pattern", "encoding=UTF-8"]],
  ["join path", ["base", "*parts"]],
  ["join paths", ["base", "*paths"]],
  ["list directories in directory", ["path", "pattern=None", "absolute=False"]],
  ["list directory", ["path", "pattern=None", "absolute=False"]],
  ["list files in directory", ["path", "pattern=None", "absolute=False"]],
  ["log environment variables", ["level=INFO"]],
  ["log file", ["path", "encoding=UTF-8"]],
  ["move directory", ["source", "destination"]],
  ["move file", ["source", "destination"]],
  ["move files", ["*sources_and_destination"]],
  ["normalize path", ["path"]],
  ["remove directory", ["path", "recursive=False"]],
  ["remove environment variable", ["*names"]],
  ["remove file", ["path"]],
  ["remove files", ["*paths"]],
  ["run", ["command"]],
  ["run and return rc", ["command"]],
  ["run and return rc and output", ["command"]],
  ["set environment variable", ["name", "value"]],
  ["set modified time", ["path", "mtime=None"]],
  ["should exist", ["path", "msg=None"]],
  ["should not exist", ["path", "msg=None"]],
  ["split extension", ["path"]],
  ["split path", ["path"]],
  ["touch", ["path"]],
  ["wait until created", ["path", "timeout=1 minute"]],
  ["wait until removed", ["path", "timeout=1 minute"]],
  // =====================================================================
  // Process Library
  // =====================================================================
  ["get process id", ["handle=None"]],
  ["get process object", ["handle=None"]],
  ["get process result", ["handle=None", "rc=False", "stdout=False", "stderr=False", "stdout_path=False", "stderr_path=False"]],
  ["is process running", ["handle=None"]],
  ["join command line", ["*args"]],
  ["process should be running", ["handle=None", "error_message=Process is not running."]],
  ["process should be stopped", ["handle=None", "error_message=Process is running."]],
  ["run process", ["command", "*arguments", "**configuration"]],
  ["send signal to process", ["signal", "handle=None", "group=False"]],
  ["split command line", ["args", "escaping=False"]],
  ["start process", ["command", "*arguments", "**configuration"]],
  ["switch process", ["handle"]],
  ["terminate all processes", ["kill=False"]],
  ["terminate process", ["handle=None", "kill=False"]],
  ["wait for process", ["handle=None", "timeout=None", "on_timeout=continue"]],
  // =====================================================================
  // XML Library
  // =====================================================================
  ["add element", ["source", "element", "index=None", "xpath=."]],
  ["clear element", ["source", "xpath=.", "clear_tail=False"]],
  ["copy element", ["source", "xpath=."]],
  ["element attribute should be", ["source", "name", "expected", "xpath=.", "message=None"]],
  ["element attribute should match", ["source", "name", "pattern", "xpath=.", "message=None"]],
  ["element should exist", ["source", "xpath=.", "message=None"]],
  ["element should not exist", ["source", "xpath=.", "message=None"]],
  ["element should not have attribute", ["source", "name", "xpath=.", "message=None"]],
  ["element text should be", ["source", "expected", "xpath=.", "normalize_whitespace=False", "message=None"]],
  ["element text should match", ["source", "pattern", "xpath=.", "normalize_whitespace=False", "message=None"]],
  ["element to string", ["source", "xpath=.", "encoding=None"]],
  ["elements should be equal", ["source", "expected", "exclude_children=False", "normalize_whitespace=False"]],
  ["elements should match", ["source", "expected", "exclude_children=False", "normalize_whitespace=False"]],
  ["evaluate xpath", ["source", "expression", "context=."]],
  ["get child elements", ["source", "xpath=."]],
  ["get element", ["source", "xpath=."]],
  ["get element attribute", ["source", "name", "xpath=.", "default=None"]],
  ["get element attributes", ["source", "xpath=."]],
  ["get element count", ["source", "xpath=."]],
  ["get element text", ["source", "xpath=.", "normalize_whitespace=False"]],
  ["get elements", ["source", "xpath=."]],
  ["get elements texts", ["source", "xpath=.", "normalize_whitespace=False"]],
  ["log element", ["source", "level=INFO", "xpath=."]],
  ["parse xml", ["source", "keep_clark_notation=False", "strip_namespaces=False"]],
  ["remove element", ["source", "xpath=", "remove_tail=False"]],
  ["remove element attribute", ["source", "name", "xpath=."]],
  ["remove element attributes", ["source", "xpath=."]],
  ["remove elements", ["source", "xpath=", "remove_tail=False"]],
  ["remove elements attribute", ["source", "name", "xpath=."]],
  ["remove elements attributes", ["source", "xpath=."]],
  ["save xml", ["source", "path", "encoding=UTF-8"]],
  ["set element attribute", ["source", "name", "value", "xpath=."]],
  ["set element tag", ["source", "tag", "xpath=."]],
  ["set element text", ["source", "text=None", "tail=None", "xpath=."]],
  ["set elements attribute", ["source", "name", "value", "xpath=."]],
  ["set elements tag", ["source", "tag", "xpath=."]],
  ["set elements text", ["source", "text=None", "tail=None", "xpath=."]]
]), Ra = 200, ca = /%\{([^}=]+)(?:=([^}]*))?\}/g;
function Hs(e) {
  const a = [];
  if (!e) return a;
  ca.lastIndex = 0;
  let r;
  for (; (r = ca.exec(e)) !== null; )
    a.push({ name: r[1].trim(), default: r[2] !== void 0 ? r[2] : null });
  return a;
}
function Fa(e) {
  const a = /* @__PURE__ */ new Map();
  for (const r of e)
    for (const c of Hs(r)) {
      const g = a.get(c.name);
      g ? g.default === null && c.default !== null && a.set(c.name, c) : a.set(c.name, c);
    }
  return [...a.values()];
}
function Pa(e, a) {
  return e.map((r) => ({
    ref: r,
    state: a === null ? "unknown" : a.has(r.name) ? "defined" : r.default !== null ? "default" : "missing"
  }));
}
const Os = { class: "flow-node-header" }, Is = ["draggable"], Ws = {
  key: 1,
  class: "flow-node-bdd-badge",
  "data-testid": "bdd-badge"
}, js = { class: "flow-node-label" }, qs = ["title"], zs = {
  key: 0,
  class: "flow-node-args"
}, Gs = ["title"], Ys = ["title"], Xs = {
  key: 1,
  class: "flow-arg-type-icon-prefix",
  "data-testid": "arg-type-icon"
}, Zs = {
  key: 2,
  class: "flow-arg-name-prefix",
  "data-testid": "arg-name-prefix"
}, Qs = { class: "flow-arg-value" }, Js = ["title"], el = {
  key: 1,
  class: "flow-node-return"
}, tl = /* @__PURE__ */ qe({
  __name: "KeywordNode",
  props: {
    data: {},
    reorderEnabled: { type: Boolean },
    selected: { type: Boolean }
  },
  emits: ["reorder-drag-start"],
  setup(e, { emit: a }) {
    const r = e, c = a, { t: g } = ft(), s = rs("envVarKeys", null), _ = O(() => new Set(
      Pa(r.data.envRefs ?? [], (s == null ? void 0 : s.value) ?? null).filter((P) => P.state === "missing").map((P) => P.ref.name)
    )), b = O(() => (r.data.envRefs ?? []).map(
      (P) => (P.default !== null ? `%{${P.name}=${P.default}}` : `%{${P.name}}`) + (_.value.has(P.name) ? ` ${g("flowEditor.envVarNotDefined")}` : "")
    ).join(`
`)), k = N(!1);
    let V = null;
    function L() {
      T(), V = window.setTimeout(() => {
        k.value = !0;
      }, Ra);
    }
    function T() {
      V != null && (clearTimeout(V), V = null), k.value = !1;
    }
    Tn(T);
    function B(P) {
      c("reorder-drag-start", P);
    }
    function I() {
      T();
    }
    function W(P) {
      const K = r.data.argSpecs;
      if (!K || K.length === 0) return null;
      const j = Na(K, P, g), D = g("flowEditor.argLabels.fallback", { n: P + 1 });
      return j === D ? null : j;
    }
    function re(P) {
      var se;
      const K = r.data.step.args[P], j = (se = r.data.argSpecs) == null ? void 0 : se[P], D = [];
      if (K && K.length > 0 && D.push(K), j != null && j.name) {
        let oe = j.name;
        j.type && (oe += `: ${j.type}`), j.defaultValue != null && (oe += ` = ${j.defaultValue}`), D.push(oe);
      }
      return D.length > 0 ? D.join(`
`) : void 0;
    }
    function x(P) {
      var D;
      const K = (D = r.data.argSpecs) == null ? void 0 : D[P];
      if (!(K != null && K.type)) return null;
      const j = wn(K.type);
      return j.labelKey === "flowEditor.argTypes.unknown" ? null : j.icon;
    }
    const Z = O(() => {
      var P;
      return ((P = r.data.recording) == null ? void 0 : P.selector_candidates.length) ?? 0;
    }), Q = O(() => Z.value > 0), G = O(() => {
      const P = r.data.recording ? Ps(r.data.recording) : null;
      return hn((P == null ? void 0 : P.quality_score) ?? 0);
    }), R = O(
      () => g("flowEditor.selector.tooltipHasCandidates", { count: Z.value })
    );
    return (P, K) => (i(), u("div", {
      class: te(["flow-node flow-node-keyword", { "flow-node--selected": e.selected }])
    }, [
      ve(p(Ct), {
        type: "target",
        position: p($t).Top
      }, null, 8, ["position"]),
      o("div", Os, [
        e.reorderEnabled ? (i(), u("div", {
          key: 0,
          class: te(["flow-drag-handle", { "flow-drag-handle--armed": k.value }]),
          draggable: k.value,
          onPointerdown: le(L, ["stop"]),
          onPointerup: T,
          onPointerleave: T,
          onPointercancel: T,
          onMousedown: K[0] || (K[0] = le(() => {
          }, ["stop"])),
          onDragstart: le(B, ["stop"]),
          onDragend: I
        }, "☰", 42, Is)) : $("", !0),
        K[1] || (K[1] = o("span", { class: "flow-node-icon" }, "⚙", -1)),
        e.data.bdd ? (i(), u("span", Ws, m(e.data.bdd.prefix), 1)) : $("", !0),
        o("span", js, m(e.data.bdd ? e.data.bdd.rest : e.data.step.keyword || "Keyword"), 1),
        e.data.envRefs && e.data.envRefs.length ? (i(), u("span", {
          key: 2,
          class: te(["flow-node-env-badge", { "flow-node-env-badge--missing": _.value.size > 0 }]),
          "data-testid": "env-badge",
          title: b.value
        }, "%{}", 10, qs)) : $("", !0)
      ]),
      e.data.step.args.length ? (i(), u("div", zs, [
        (i(!0), u(U, null, Y(e.data.step.args, (j, D) => (i(), u("span", {
          key: D,
          class: te(["flow-arg", { "flow-arg--has-candidates": D === 0 && Q.value }]),
          title: re(D)
        }, [
          D === 0 && Q.value ? (i(), u("span", {
            key: 0,
            class: te(["flow-arg-dot", `flow-arg-dot--${G.value}`]),
            title: R.value,
            "aria-hidden": "true"
          }, null, 10, Ys)) : $("", !0),
          x(D) ? (i(), u("span", Xs, m(x(D)), 1)) : $("", !0),
          W(D) ? (i(), u("span", Zs, m(W(D)) + ": ", 1)) : $("", !0),
          o("span", Qs, m(j), 1),
          D === 0 && Q.value ? (i(), u("span", {
            key: 3,
            class: "flow-arg-count",
            title: R.value,
            "data-testid": "selector-candidate-count"
          }, m(p(g)("flowEditor.selector.candidatesBadge", { count: Z.value })), 9, Js)) : $("", !0)
        ], 10, Gs))), 128))
      ])) : $("", !0),
      e.data.step.returnVars.length ? (i(), u("div", el, m(e.data.step.returnVars.join(", ")) + " = ", 1)) : $("", !0),
      ve(p(Ct), {
        type: "source",
        position: p($t).Bottom
      }, null, 8, ["position"])
    ], 2));
  }
}), da = /* @__PURE__ */ ze(tl, [["__scopeId", "data-v-dcd7105e"]]), ol = { class: "flow-node-header" }, nl = ["draggable"], al = ["innerHTML"], sl = { class: "flow-node-type" }, ll = {
  key: 0,
  class: "flow-node-condition"
}, rl = {
  key: 1,
  class: "flow-node-condition"
}, il = {
  key: 2,
  class: "flow-node-condition"
}, ul = /* @__PURE__ */ qe({
  __name: "ControlNode",
  props: {
    data: {},
    reorderEnabled: { type: Boolean },
    selected: { type: Boolean }
  },
  emits: ["reorder-drag-start"],
  setup(e, { emit: a }) {
    const r = a, c = N(!1);
    let g = null;
    function s() {
      _(), g = window.setTimeout(() => {
        c.value = !0;
      }, Ra);
    }
    function _() {
      g != null && (clearTimeout(g), g = null), c.value = !1;
    }
    Tn(_);
    function b(T) {
      r("reorder-drag-start", T);
    }
    function k() {
      _();
    }
    const V = {
      if: "#E8A838",
      else_if: "#E8A838",
      else: "#E8A838",
      for: "#7B61FF",
      while: "#7B61FF",
      try: "#38B2AC",
      except: "#E53E3E",
      finally: "#38B2AC"
    }, L = {
      if: "&#x2753;",
      // ?
      else_if: "&#x2753;",
      else: "&#x2194;",
      // ↔
      for: "&#x1F503;",
      // 🔃
      while: "&#x1F503;",
      try: "&#x1F6E1;",
      // 🛡
      except: "&#x26A0;",
      // ⚠
      finally: "&#x2705;"
      // ✅
    };
    return (T, B) => (i(), u("div", {
      class: te(["flow-node flow-node-control", { "flow-node--selected": e.selected }]),
      style: Ee({ borderColor: V[e.data.stepType] || "#888" })
    }, [
      ve(p(Ct), {
        type: "target",
        position: p($t).Top
      }, null, 8, ["position"]),
      o("div", ol, [
        e.reorderEnabled ? (i(), u("div", {
          key: 0,
          class: te(["flow-drag-handle", { "flow-drag-handle--armed": c.value }]),
          draggable: c.value,
          onPointerdown: le(s, ["stop"]),
          onPointerup: _,
          onPointerleave: _,
          onPointercancel: _,
          onMousedown: B[0] || (B[0] = le(() => {
          }, ["stop"])),
          onDragstart: le(b, ["stop"]),
          onDragend: k
        }, "☰", 42, nl)) : $("", !0),
        o("span", {
          class: "flow-node-icon",
          innerHTML: L[e.data.stepType] || "◆"
        }, null, 8, al),
        o("span", sl, m(e.data.stepType.toUpperCase().replace("_", " ")), 1)
      ]),
      e.data.step.condition ? (i(), u("div", ll, m(e.data.step.condition), 1)) : $("", !0),
      e.data.stepType === "for" || e.data.stepType === "while" ? (i(), u("div", rl, m(e.data.step.loopVar) + " " + m(e.data.step.loopFlavor) + " " + m(e.data.step.loopValues.join("  ")), 1)) : $("", !0),
      e.data.step.exceptPattern ? (i(), u("div", il, m(e.data.step.exceptPattern), 1)) : $("", !0),
      ve(p(Ct), {
        type: "source",
        position: p($t).Bottom
      }, null, 8, ["position"])
    ], 6));
  }
}), cl = /* @__PURE__ */ ze(ul, [["__scopeId", "data-v-b5f0e3b6"]]), dl = /* @__PURE__ */ qe({
  __name: "ControlGroupNode",
  props: {
    data: {}
  },
  setup(e) {
    return (a, r) => (i(), u("div", {
      class: "control-frame",
      style: Ee({
        borderColor: e.data.borderColor,
        background: e.data.bgColor,
        width: "100%",
        height: "100%"
      })
    }, null, 4));
  }
}), pl = /* @__PURE__ */ ze(dl, [["__scopeId", "data-v-5c4a8d41"]]), fl = { class: "flow-terminal-label" }, ml = /* @__PURE__ */ qe({
  __name: "StartEndNode",
  props: {
    data: {},
    type: {}
  },
  setup(e) {
    const a = e, { t: r } = ft(), c = O(() => {
      var g;
      return (g = a.data) != null && g.label && a.data.label.trim() ? a.data.label : a.type === "start" ? r("flowEditor.startNodeLabel") : r("flowEditor.endNodeLabel");
    });
    return (g, s) => (i(), u("div", {
      class: te(["flow-node-terminal", e.type === "start" ? "flow-node-start" : "flow-node-end"])
    }, [
      e.type === "end" ? (i(), xt(p(Ct), {
        key: 0,
        type: "target",
        position: p($t).Top
      }, null, 8, ["position"])) : $("", !0),
      e.type === "start" ? (i(), xt(p(Ct), {
        key: 1,
        type: "target",
        position: p($t).Left,
        id: "left",
        style: { opacity: "0", "pointer-events": "none" }
      }, null, 8, ["position"])) : $("", !0),
      o("span", fl, m(c.value), 1),
      e.type === "start" ? (i(), xt(p(Ct), {
        key: 2,
        type: "source",
        position: p($t).Bottom
      }, null, 8, ["position"])) : $("", !0)
    ], 2));
  }
}), pa = /* @__PURE__ */ ze(ml, [["__scopeId", "data-v-088ff712"]]), _n = vn({ scopeId: null, filePath: "" });
function L_(e) {
  Object.assign(_n, e);
}
function V_() {
  _n.scopeId = null, _n.filePath = "";
}
function Da() {
  return _n;
}
const mn = (e, a = "") => ({
  path: e,
  name: e.split("/").pop() ?? e,
  content: a,
  extension: e.includes(".") ? e.slice(e.lastIndexOf(".") + 1) : null,
  line_count: a ? a.split(/\r\n|\r|\n/).length : 0
}), We = (e) => () => {
  throw new Error(`编辑器适配器未实现 ${e}（请宿主通过 configureEditorAdapter 注入）`);
}, Ma = {
  searchKeywords: async () => ({ results: [] }),
  invalidateKeywordCache: async () => {
  },
  getEnvironmentKeywords: async () => ({ keywords: [], status: "unavailable" }),
  getTree: async () => ({ name: "", path: "", type: "directory", extension: null, children: [], test_count: 0 }),
  getFile: async (e, a) => mn(a),
  saveFile: async (e, a, r) => mn(a, r),
  createFile: async (e, a, r = "") => mn(a, r),
  deleteFile: async () => {
  },
  renameFile: async (e, a, r) => mn(r),
  search: async () => [],
  getTestCases: async () => [],
  getEnvironments: async () => [],
  getPackages: async () => [],
  getInstalledPackages: async () => [],
  installPackage: We("installPackage"),
  uninstallPackage: async () => {
  },
  getVariables: async () => [],
  createVariable: We("createVariable"),
  updateVariable: We("updateVariable"),
  deleteVariable: async () => {
  },
  createEnvironment: We("createEnvironment"),
  deleteEnvironment: async () => {
  },
  setupDefaultEnvironment: We("setupDefaultEnvironment"),
  cloneEnvironment: We("cloneEnvironment"),
  getRepos: async () => [],
  createRepo: We("createRepo"),
  updateRepo: We("updateRepo"),
  deleteRepo: async () => {
  },
  syncRepo: async () => ({ status: "unsupported", message: "", task_id: null }),
  getBranches: async () => [],
  checkoutBranch: We("checkoutBranch"),
  getProjectMembers: async () => [],
  addProjectMember: We("addProjectMember"),
  updateProjectMember: We("updateProjectMember"),
  removeProjectMember: async () => {
  },
  login: We("login"),
  getMe: We("getMe"),
  patchFirstLoginComplete: We("patchFirstLoginComplete"),
  getFeatures: async () => ({ flags: {}, locked: {} }),
  startDebugSession: We("startDebugSession"),
  getDebugState: async (e) => ({
    session_id: e,
    paused: !1,
    terminated: !0,
    paused_at: { file: null, line: null, keyword: null },
    scopes: [],
    call_stack: [],
    output_lines: []
  }),
  postDebugControl: async () => {
  },
  installDebugPrerequisites: async () => ({ already_installed: !1, log_tail: null })
};
let kn = Ma;
function A_(e) {
  kn = { ...kn, ...e };
}
function N_() {
  kn = Ma;
}
function ye() {
  return kn;
}
const gl = (e, a = "") => ye().getTree(e, a), xa = (e, a, r) => ye().getFile(e, a, r), Ua = (e, a, r) => ye().saveFile(e, a, r), vl = (e, a, r = "") => ye().createFile(e, a, r), hl = (e, a) => ye().deleteFile(e, a), yl = (e, a, r) => ye().renameFile(e, a, r), bl = (e, a, r) => ye().search(e, a, r), wl = (e) => ye().getTestCases(e);
async function _l(e, a) {
}
async function kl(e, a) {
}
async function Cl(e) {
  return [];
}
async function Wo(e, a) {
  return ye().searchKeywords(e, a);
}
async function $l(e) {
  return ye().invalidateKeywordCache(e);
}
const Tl = () => ye().getEnvironments(), El = (e) => ye().getPackages(e), Sl = (e) => ye().getInstalledPackages(e), Ba = (e, a) => ye().installPackage(e, a), Ll = (e, a) => ye().uninstallPackage(e, a), Vl = (e) => ye().getVariables(e), Al = (e, a) => ye().createVariable(e, a), Nl = (e, a, r) => ye().updateVariable(e, a, r), Rl = (e, a) => ye().deleteVariable(e, a), Fl = (e) => ye().createEnvironment(e), Pl = (e) => ye().deleteEnvironment(e), Dl = () => ye().setupDefaultEnvironment(), Ml = (e, a) => ye().cloneEnvironment(e, a);
async function xl(e) {
  return ye().getEnvironmentKeywords(e);
}
const Ul = () => ye().getRepos(), Bl = (e) => ye().createRepo(e), Kl = (e, a) => ye().updateRepo(e, a), Hl = (e) => ye().deleteRepo(e), Ol = (e) => ye().syncRepo(e), Il = (e) => ye().getBranches(e), Wl = (e, a) => ye().checkoutBranch(e, a), jl = (e) => ye().getProjectMembers(e), ql = (e, a, r) => ye().addProjectMember(e, a, r), zl = (e, a, r) => ye().updateProjectMember(e, a, r), Gl = (e, a) => ye().removeProjectMember(e, a), Ka = Co("repos", () => {
  const e = N([]), a = N(!1), r = N({}), c = N({});
  async function g() {
    a.value = !0;
    try {
      e.value = await Ul();
    } finally {
      a.value = !1;
    }
  }
  async function s(x) {
    const Z = await Bl(x);
    return e.value.push(Z), Z;
  }
  async function _(x) {
    return await Ol(x);
  }
  async function b(x, Z) {
    const Q = await Kl(x, Z), G = e.value.findIndex((R) => R.id === x);
    return G >= 0 && (e.value[G] = Q), Q;
  }
  async function k(x) {
    await Hl(x), e.value = e.value.filter((Z) => Z.id !== x);
  }
  async function V(x) {
    r.value[x] = await Il(x);
  }
  async function L(x, Z) {
    await Wl(x, Z);
    const Q = e.value.find((G) => G.id === x);
    Q && (Q.default_branch = Z), r.value[x] && (r.value[x] = r.value[x].map((G) => ({
      ...G,
      is_active: G.name === Z
    })));
  }
  function T(x) {
    return e.value.find((Z) => Z.id === x);
  }
  async function B(x) {
    c.value[x] = await jl(x);
  }
  async function I(x, Z, Q) {
    const G = await ql(x, Z, Q);
    c.value[x] || (c.value[x] = []);
    const R = c.value[x].findIndex((P) => P.user_id === Z);
    return R >= 0 ? c.value[x][R] = G : c.value[x].push(G), G;
  }
  async function W(x, Z, Q) {
    var P;
    const G = await zl(x, Z, Q), R = (P = c.value[x]) == null ? void 0 : P.findIndex((K) => K.id === Z);
    return R !== void 0 && R >= 0 && (c.value[x][R] = G), G;
  }
  async function re(x, Z) {
    await Gl(x, Z), c.value[x] && (c.value[x] = c.value[x].filter((Q) => Q.id !== Z));
  }
  return { repos: e, loading: a, branches: r, members: c, fetchRepos: g, addRepo: s, updateRepo: b, syncRepo: _, removeRepo: k, fetchBranches: V, checkoutBranch: L, getRepo: T, fetchMembers: B, addMember: I, updateMember: W, removeMember: re };
}), na = Co("environments", () => {
  const e = N([]), a = N({}), r = N({}), c = N({}), g = N(!1);
  async function s() {
    g.value = !0;
    try {
      e.value = await Tl();
    } finally {
      g.value = !1;
    }
  }
  async function _(R) {
    const P = await Fl(R);
    return e.value.push(P), P;
  }
  async function b(R) {
    await Pl(R), e.value = e.value.filter((P) => P.id !== R);
  }
  async function k() {
    const R = await Dl();
    return e.value.push(R), R;
  }
  async function V(R, P) {
    const K = await Ml(R, P);
    return e.value.push(K), K;
  }
  async function L(R) {
    const P = await El(R), K = /* @__PURE__ */ new Set();
    a.value[R] = P.filter((j) => K.has(j.package_name) ? !1 : (K.add(j.package_name), !0));
  }
  async function T(R, P) {
    const K = await Ba(R, P);
    a.value[R] || (a.value[R] = []), a.value[R].some((j) => j.package_name === K.package_name) || a.value[R].push(K);
  }
  async function B(R, P) {
    await Ll(R, P), a.value[R] && (a.value[R] = a.value[R].filter((K) => K.package_name !== P));
  }
  async function I(R) {
    r.value[R] = await Vl(R);
  }
  async function W(R, P) {
    await Al(R, P), await I(R);
  }
  async function re(R, P, K) {
    await Nl(R, P, K), await I(R);
  }
  async function x(R, P) {
    await Rl(R, P), r.value[R] = (r.value[R] || []).filter((K) => K.id !== P);
  }
  function Z(R, P, K, j) {
    const D = a.value[R];
    if (!D) return;
    const se = D.findIndex((oe) => oe.package_name === P);
    se < 0 || (D[se] = {
      ...D[se],
      install_status: K,
      install_error: j.error || null,
      installed_version: j.installed_version || D[se].installed_version
    });
  }
  function Q(R, P, K) {
    c.value[R] || (c.value[R] = []), P && c.value[R].push(P), K && s();
  }
  function G(R) {
    delete c.value[R];
  }
  return {
    environments: e,
    packages: a,
    variables: r,
    buildLogs: c,
    loading: g,
    fetchEnvironments: s,
    addEnvironment: _,
    setupDefault: k,
    removeEnvironment: b,
    cloneEnvironment: V,
    fetchPackages: L,
    installPackage: T,
    uninstallPackage: B,
    fetchVariables: I,
    updatePackageFromWs: Z,
    createVariable: W,
    updateVariable: re,
    deleteVariable: x,
    appendBuildLog: Q,
    clearBuildLogs: G
  };
}), Yo = Co("explorer", () => {
  const e = N(null), a = N(null), r = N([]), c = N([]), g = N(!1), s = N(null), _ = N([]), b = N(!1), k = N(!1), V = N(null);
  let L = null;
  const T = N([]);
  function B(J) {
    T.value = J;
  }
  async function I(J, H = "") {
    g.value = !0, s.value = J;
    try {
      e.value = await gl(J, H);
    } finally {
      g.value = !1;
    }
  }
  async function W(J, H, ue) {
    g.value = !0;
    try {
      a.value = await xa(J, H, ue);
    } finally {
      g.value = !1;
    }
  }
  async function re(J, H, ue) {
    g.value = !0;
    try {
      r.value = await bl(J, H, ue);
    } finally {
      g.value = !1;
    }
  }
  async function x(J) {
    g.value = !0;
    try {
      c.value = await wl(J);
    } finally {
      g.value = !1;
    }
  }
  async function Z(J, H, ue) {
    const ce = await Ua(J, H, ue);
    return a.value = ce, ce;
  }
  async function Q(J, H, ue = "") {
    const ce = await vl(J, H, ue);
    return await I(J), ce;
  }
  async function G(J, H) {
    var ue;
    await hl(J, H), ((ue = a.value) == null ? void 0 : ue.path) === H && (a.value = null), await I(J);
  }
  async function R(J, H, ue) {
    var ge;
    const ce = await yl(J, H, ue);
    return ((ge = a.value) == null ? void 0 : ge.path) === H && (a.value = ce), await I(J), ce;
  }
  async function P(J, H) {
    await _l();
  }
  async function K(J, H) {
    await kl();
  }
  function j() {
    a.value = null, r.value = [];
  }
  function D(J) {
    try {
      const H = Ka().getRepo(J);
      if ((H == null ? void 0 : H.environment_id) != null) return H.environment_id;
      const ue = na().environments.find((ce) => ce.is_default);
      return (ue == null ? void 0 : ue.id) ?? null;
    } catch {
      return null;
    }
  }
  async function se(J) {
    if (!(k.value && V.value === J)) {
      if (b.value) {
        L = J;
        return;
      }
      b.value = !0, k.value = !1;
      try {
        const H = D(J);
        if (H != null)
          try {
            const ce = await xl(H);
            if (ce.keywords && ce.keywords.length > 0) {
              _.value = ce.keywords.map((ge) => ({
                name: ge.name,
                library: ge.library,
                doc: ge.shortdoc || "",
                doc_format: "text",
                args: ge.args || []
              })), k.value = !0, V.value = J;
              return;
            }
          } catch {
          }
        const ue = await Wo("*", J);
        _.value = (ue.results || []).map((ce) => ({
          name: ce.name,
          library: ce.library,
          doc: ce.doc || "",
          doc_format: ce.doc_format || "text",
          args: ce.args || []
        })), k.value = !0, V.value = J;
      } catch {
        _.value = [], V.value = null;
      } finally {
        if (b.value = !1, L !== null && L !== V.value) {
          const H = L;
          L = null, se(H);
        } else
          L = null;
      }
    }
  }
  async function oe(J) {
    try {
      await $l(J);
    } catch {
    }
    k.value = !1, await se(J);
  }
  function be() {
    e.value = null, a.value = null, r.value = [], c.value = [], _.value = [], k.value = !1, V.value = null, T.value = [];
  }
  return {
    tree: e,
    selectedFile: a,
    searchResults: r,
    testCases: c,
    loading: g,
    currentscopeId: s,
    keywords: _,
    keywordsLoading: b,
    keywordsLoaded: k,
    keywordsscopeId: V,
    projectKeywords: T,
    fetchTree: I,
    openFile: W,
    searchInRepo: re,
    fetchTestCases: x,
    clearSelection: j,
    clearAll: be,
    saveFile: Z,
    createFile: Q,
    deleteFileAction: G,
    renameFileAction: R,
    openInEditorAction: P,
    openInFileBrowserAction: K,
    preloadKeywords: se,
    refreshKeywords: oe,
    setProjectKeywords: B,
    resolveEnvironmentId: D
  };
});
function En() {
  const e = Yo(), a = O(() => {
    const _ = /* @__PURE__ */ new Map();
    for (const [b, k] of wo) _.set(b.toLowerCase(), k);
    for (const b of e.keywords)
      b.args && b.args.length && _.set(b.name.toLowerCase(), b.args);
    for (const b of e.projectKeywords)
      b.name && _.set(b.name.toLowerCase(), b.arguments ?? []);
    return _;
  });
  function r(_) {
    if (!_) return null;
    const b = a.value.get(_.toLowerCase());
    if (b) return b;
    const k = Aa(_);
    return k ? a.value.get(k.rest.toLowerCase()) ?? null : null;
  }
  function c(_) {
    const b = r(_);
    return b ? b.map(oa) : null;
  }
  function g(_) {
    if (!_) return null;
    const b = _.toLowerCase(), k = a.value.get(b);
    if (!k) return null;
    const V = e.keywords.find((T) => T.name.toLowerCase() === b), L = e.projectKeywords.find((T) => T.name.toLowerCase() === b);
    return L ? {
      display: L.name,
      library: L.file_path,
      doc: L.doc ?? "",
      docFormat: "text",
      args: k
    } : {
      display: (V == null ? void 0 : V.name) ?? _,
      library: (V == null ? void 0 : V.library) ?? "BuiltIn",
      doc: (V == null ? void 0 : V.doc) ?? "",
      docFormat: (V == null ? void 0 : V.doc_format) ?? "text",
      args: k
    };
  }
  async function s(_, b) {
    if (!_) return null;
    try {
      const k = await Wo(_, b), V = _.toLowerCase(), L = k.results.find((B) => B.name.toLowerCase() === V) ?? k.results[0];
      return L ? (e.keywords.some((B) => B.name.toLowerCase() === L.name.toLowerCase()) || e.keywords.push({
        name: L.name,
        library: L.library || "",
        doc: L.doc || "",
        doc_format: L.doc_format || "text",
        args: L.args || []
      }), {
        display: L.name,
        library: L.library || "BuiltIn",
        doc: L.doc || "",
        docFormat: L.doc_format || "text",
        args: L.args ?? r(_) ?? []
      }) : g(_);
    } catch {
      return g(_);
    }
  }
  return { argsByName: a, getArgs: r, getParsedArgs: c, getKeywordInfo: g, fetchKeywordInfo: s };
}
function Yl(e, a) {
  const r = (T) => T.replace(/\\/g, "/").replace(/^\.\//, ""), c = r(e), g = r(a);
  if (!c) return g;
  const s = c.split("/").slice(0, -1), _ = g.split("/");
  let b = 0;
  for (; b < s.length && b < _.length - 1 && s[b] === _[b]; )
    b++;
  const k = s.length - b, V = _.slice(b);
  return [...Array(k).fill(".."), ...V].join("/") || g;
}
const Xl = /\.(resource|robot|txt|tsv)$/i;
function Zl(e) {
  const a = e.trim();
  return Xl.test(a) || a.includes("/");
}
const jn = "robot-autotest.flowPalette.filter", fa = "robot-autotest.flowPalette.sort", Ql = 1, Jl = 5;
function er(e) {
  return e.importCount >= Ql || e.stepCount >= Jl;
}
const tr = {
  resources: !0,
  importedLibs: !0,
  exampleLibs: !0,
  builtin: !0
}, or = {
  resources: !0,
  importedLibs: !0,
  exampleLibs: !1,
  // hide the not-installed example noise
  builtin: !0
};
function nr(e) {
  return e.hasEnvData && er(e.file) ? { ...or } : { ...tr };
}
function ar(e) {
  if (!e) return null;
  try {
    const a = JSON.parse(e);
    if (typeof (a == null ? void 0 : a.resources) == "boolean" && typeof (a == null ? void 0 : a.importedLibs) == "boolean" && typeof (a == null ? void 0 : a.exampleLibs) == "boolean" && typeof (a == null ? void 0 : a.builtin) == "boolean")
      return { resources: a.resources, importedLibs: a.importedLibs, exampleLibs: a.exampleLibs, builtin: a.builtin };
  } catch {
  }
  return null;
}
function sr(e) {
  return e === "mostUsed" || e === "alpha" || e === "importedFirst" ? e : null;
}
function lr(e) {
  const a = /* @__PURE__ */ new Set();
  for (const r of e) {
    const g = (r.split("/").pop() || r).trim().replace(/\.[^.]+$/, "");
    g && a.add(g.toLowerCase());
  }
  return a;
}
function Ha(e) {
  return e.kind === "resource" ? "resources" : e.kind === "control" ? null : e.name === "BuiltIn" ? "builtin" : e.isExamples ? "exampleLibs" : "importedLibs";
}
function rr(e, a) {
  return e.filter((r) => {
    const c = Ha(r);
    return c === null ? !0 : a[c];
  });
}
function ir(e, a) {
  return e.reduce((r, c) => {
    const g = Ha(c);
    return g !== null && !a[g] ? r + 1 : r;
  }, 0);
}
function ur(e, a, r) {
  const c = [...e];
  return a === "alpha" ? c.sort((g, s) => g.name.localeCompare(s.name, void 0, { sensitivity: "base" })) : a === "importedFirst" ? c.sort((g, s) => {
    const _ = g.isExamples ? 1 : 0, b = s.isExamples ? 1 : 0;
    return _ !== b ? _ - b : (r.get(s.name) ?? 0) - (r.get(g.name) ?? 0);
  }) : c.sort((g, s) => (r.get(s.name) ?? 0) - (r.get(g.name) ?? 0)), c;
}
function ma(e, a, r) {
  const c = (e || "").trim();
  if (!c) return "";
  if (a !== "html") return c;
  const g = c.replace(/<br\s*\/?>/gi, `
`).replace(/<\/(p|div|li|h[1-6]|tr|pre|blockquote)\s*>/gi, `
`);
  return r(g).replace(/[ \t]+/g, " ").replace(/[ \t]*\n[ \t]*/g, `
`).replace(/\n{3,}/g, `

`).trim();
}
const cr = /^[A-Za-z_\u4e00-\u9fa5][\w\u4e00-\u9fa5]*$/;
function dr(e) {
  const a = [];
  for (const r of e || []) {
    const c = (r || "").trim();
    if (c && !c.includes("=")) {
      if (c.startsWith("**")) {
        a.push(`&{${c.slice(2).replace(/_$/, "")}}`);
        continue;
      }
      if (c.startsWith("*")) {
        a.push(`@{${c.slice(1).replace(/_$/, "")}}`);
        continue;
      }
      if (c.startsWith("${") || c.startsWith("@{") || c.startsWith("&{") || c.startsWith("%{")) {
        a.push(c);
        continue;
      }
      cr.test(c) && a.push(`\${${c.replace(/_$/, "")}}`);
    }
  }
  return a;
}
const pr = {
  "should be equal": ["actual", "expected", "msg=None", "values=True", "formatter=str"],
  "should contain": ["container", "item", "msg=None", "values=True"],
  "should not be empty": ["item", "msg=None"],
  "should be true": ["condition", "msg=None"],
  log: ["message", "level=INFO", "html=False", "console=False"],
  "log to console": ["message", "stream=STDOUT", "no_newline=False"],
  sleep: ["time", "reason=None"],
  "set variable": ["*values"],
  "run keyword if": ["condition", "name", "*args"],
  evaluate: ["expression", "modules=None", "namespace=None"],
  "create list": ["*items"],
  "create dictionary": ["*items"],
  "append to list": ["list_", "*values"],
  "open browser": ["url", "browser=firefox"],
  "click element": ["locator"],
  "input text": ["locator", "text"]
};
function fr(e, a) {
  const r = a(e);
  return r && r.length > 0 ? r : pr[e.toLowerCase()] ?? [];
}
const mr = { class: "keyword-palette" }, gr = { class: "palette-header" }, vr = { class: "palette-actions" }, hr = { class: "palette-menu-wrap" }, yr = ["title"], br = {
  key: 0,
  class: "palette-menu",
  "data-testid": "palette-sort-menu"
}, wr = ["onClick"], _r = { class: "palette-menu-check" }, kr = { class: "palette-menu-wrap" }, Cr = ["title"], $r = {
  key: 0,
  class: "palette-menu palette-menu--wide",
  "data-testid": "palette-filter-menu"
}, Tr = ["checked", "onChange"], Er = ["placeholder"], Sr = {
  key: 0,
  class: "palette-offline",
  "data-testid": "palette-offline"
}, Lr = {
  key: 1,
  class: "palette-hiding-hint",
  "data-testid": "palette-hiding-hint"
}, Vr = {
  key: 2,
  class: "palette-add-bar"
}, Ar = { class: "palette-add-info" }, Nr = ["title"], Rr = ["title"], Fr = {
  key: 1,
  class: "palette-args-preview"
}, Pr = {
  key: 2,
  class: "palette-add-doc",
  "data-testid": "palette-add-doc"
}, Dr = {
  key: 0,
  class: "palette-add-doc-muted"
}, Mr = ["title"], xr = {
  key: 2,
  class: "palette-add-doc-muted"
}, Ur = { class: "palette-categories" }, Br = {
  class: "palette-section-label",
  "data-testid": "palette-resources-label"
}, Kr = ["title"], Hr = ["onClick"], Or = { class: "collapse-icon" }, Ir = { class: "category-file-glyph" }, Wr = { class: "category-file" }, jr = ["title"], qr = {
  key: 0,
  class: "category-file-path"
}, zr = ["title"], Gr = { class: "category-count" }, Yr = ["title", "onDragstart", "onClick", "onDblclick"], Xr = { class: "palette-item-content" }, Zr = { class: "palette-item-name" }, Qr = {
  key: 0,
  class: "palette-item-argcount"
}, Jr = ["onClick"], ei = { class: "collapse-icon" }, ti = { class: "category-name" }, oi = ["title"], ni = { class: "category-count" }, ai = ["title", "onDragstart", "onClick", "onDblclick"], si = { class: "palette-item-content" }, li = { class: "palette-item-name" }, ri = {
  key: 0,
  class: "palette-item-argcount"
}, ii = ["title"], ui = ["title", "onDragstart", "onClick", "onDblclick"], ci = { class: "palette-item-name" }, di = {
  key: 1,
  class: "palette-empty"
}, pi = /* @__PURE__ */ qe({
  __name: "KeywordPalette",
  props: {
    scopeId: {},
    filePath: {},
    importedLibraries: {},
    usageCounts: {},
    fileImportCount: {},
    fileStepCount: {}
  },
  emits: ["add-node"],
  setup(e, { emit: a }) {
    const r = e, { t: c } = ft(), g = Yo(), s = a;
    function _(A) {
      return A.kind === "control" || A.kind === "resource" || A.name === "BuiltIn" || !r.importedLibraries ? !0 : r.importedLibraries.has(A.name.toLowerCase());
    }
    const b = N(/* @__PURE__ */ new Map()), k = N([]), V = N(!1), L = N(!1), T = N(/* @__PURE__ */ new Map());
    function B() {
      const A = /* @__PURE__ */ new Map(), w = /* @__PURE__ */ new Map();
      for (const M of g.keywords) {
        const q = M.library || "Unknown";
        w.has(q) || w.set(q, []), w.get(q).push({ name: M.name, library: M.library, doc: M.doc, args: M.args || [] }), M.args && M.args.length > 0 && A.set(M.name, M.args);
      }
      b.value = w, T.value = A;
    }
    async function I() {
      if (r.scopeId) {
        V.value = !0, L.value = !1;
        try {
          if (g.keywordsLoaded && g.keywords.length > 0)
            B();
          else {
            const w = await Wo("*", r.scopeId), M = /* @__PURE__ */ new Map(), q = /* @__PURE__ */ new Map();
            for (const me of w.results) {
              const Te = me.library || "Unknown";
              q.has(Te) || q.set(Te, []), q.get(Te).push(me), me.args && me.args.length > 0 && M.set(me.name, me.args);
            }
            b.value = q, T.value = M;
          }
          const A = await Cl(r.scopeId).catch(() => []);
          k.value = A, g.setProjectKeywords(A);
          for (const w of A)
            w.arguments && w.arguments.length > 0 && T.value.set(w.name, w.arguments);
        } catch {
          L.value = !0, b.value = /* @__PURE__ */ new Map(), k.value = [], T.value = /* @__PURE__ */ new Map();
        } finally {
          V.value = !1;
        }
      }
    }
    Go(() => I()), Se(() => r.scopeId, () => I()), Se(() => g.keywordsLoaded, (A) => {
      A && g.keywords.length > 0 && B();
    });
    const W = N(""), re = N(/* @__PURE__ */ new Set()), x = N(null);
    function Z(A) {
      re.value.has(A) ? re.value.delete(A) : re.value.add(A);
    }
    function Q(A) {
      return W.value ? !0 : re.value.has(A);
    }
    function G() {
      for (const A of vt.value)
        re.value.add(A.name);
    }
    function R() {
      re.value.clear();
    }
    function P(A) {
      return fr(A, (w) => T.value.get(w) || []);
    }
    const K = O(() => {
      const A = /* @__PURE__ */ new Map();
      for (const w of k.value) A.set(w.name, w.file_path);
      return A;
    });
    function j(A, w) {
      if (A.kind === "resource") {
        const M = K.value.get(w);
        return M && M !== r.filePath ? Yl(r.filePath || "", M) : void 0;
      }
      if (A.kind !== "control" && A.name !== "BuiltIn")
        return A.name;
    }
    const { getKeywordInfo: D, fetchKeywordInfo: se } = En(), oe = N(/* @__PURE__ */ new Map()), be = N(!1);
    let J = 0;
    function H(A) {
      return Va.sanitize(A, { ALLOWED_TAGS: [], ALLOWED_ATTR: [] });
    }
    function ue(A) {
      return g.projectKeywords.some((w) => w.name.toLowerCase() === A);
    }
    const ce = O(() => {
      const A = x.value;
      return !A || A.type ? null : D(A.name);
    }), ge = O(() => {
      var A;
      return ((A = ce.value) == null ? void 0 : A.library) ?? "";
    }), Le = O(() => {
      const A = x.value;
      if (!A || A.type) return "";
      const w = ce.value;
      if (w != null && w.doc && w.doc.trim()) return ma(w.doc, w.docFormat, H);
      const M = oe.value.get(A.name.toLowerCase());
      return M ? ma(M.doc, M.docFormat, H) : "";
    });
    Se(x, async (A) => {
      const w = ++J;
      if (be.value = !1, !A || A.type) return;
      const M = A.name.toLowerCase();
      if (oe.value.has(M) || ue(M)) return;
      const q = D(A.name);
      if (!(q != null && q.doc && q.doc.trim())) {
        be.value = !0;
        try {
          const me = await se(A.name, r.scopeId);
          if (w !== J) return;
          me && me.display.toLowerCase() === M && (oe.value.set(M, { doc: me.doc, docFormat: me.docFormat }), oe.value = new Map(oe.value));
        } finally {
          w === J && (be.value = !1);
        }
      }
    });
    function $e(A, w, M) {
      x.value = { name: A, type: w, library: M };
    }
    function Be(A) {
      var w;
      return ((w = x.value) == null ? void 0 : w.name) === A;
    }
    function Ge() {
      x.value && (x.value.type ? Bt(x.value.type) : St(x.value.name, x.value.library), x.value = null);
    }
    const ie = [
      {
        name: "BuiltIn",
        keywords: [
          "Log",
          "Log To Console",
          "Set Variable",
          "Should Be Equal",
          "Should Contain",
          "Should Not Be Empty",
          "Should Be True",
          "Sleep",
          "Run Keyword If",
          "Run Keyword And Return Status",
          "Fail",
          "Pass Execution",
          "Set Test Variable",
          "Set Suite Variable",
          "Set Global Variable",
          "Get Length",
          "Convert To String",
          "Convert To Integer",
          "Evaluate",
          "Catenate"
        ]
      },
      {
        name: "Collections",
        keywords: [
          "Create List",
          "Create Dictionary",
          "Append To List",
          "Get From Dictionary",
          "Set To Dictionary",
          "List Should Contain Value",
          "Dictionary Should Contain Key",
          "Get Length",
          "Remove From List"
        ]
      },
      {
        name: "String",
        keywords: [
          "Replace String",
          "Split String",
          "Get Substring",
          "Should Match Regexp",
          "Convert To Lower Case",
          "Convert To Upper Case",
          "Strip String",
          "Get Line Count"
        ]
      },
      {
        name: "DateTime",
        keywords: [
          "Get Current Date",
          "Convert Date",
          "Convert Time",
          "Subtract Date From Date",
          "Add Time To Date",
          "Get Time"
        ]
      },
      {
        name: "OperatingSystem",
        keywords: [
          "Run",
          "Run And Return Rc",
          "File Should Exist",
          "Directory Should Exist",
          "Create File",
          "Remove File",
          "Copy File",
          "Move File",
          "List Directory",
          "Get Environment Variable"
        ]
      },
      {
        name: "Process",
        keywords: [
          "Run Process",
          "Start Process",
          "Wait For Process",
          "Terminate Process",
          "Get Process Result",
          "Process Should Be Running"
        ]
      },
      {
        name: "XML",
        keywords: [
          "Parse XML",
          "Get Element",
          "Get Element Text",
          "Get Element Attribute",
          "Element Should Exist"
        ]
      },
      {
        name: "Browser",
        keywords: [
          "New Browser",
          "New Page",
          "Click",
          "Fill Text",
          "Get Text",
          "Wait For Elements State",
          "Take Screenshot",
          "Go To",
          "Get Url",
          "Close Browser"
        ]
      },
      {
        name: "SeleniumLibrary",
        keywords: [
          "Open Browser",
          "Close Browser",
          "Click Element",
          "Input Text",
          "Get Text",
          "Wait Until Element Is Visible",
          "Page Should Contain",
          "Capture Page Screenshot",
          "Get Title",
          "Go To"
        ]
      },
      {
        name: "RequestsLibrary",
        keywords: [
          "Create Session",
          "GET",
          "POST",
          "PUT",
          "DELETE",
          "PATCH",
          "Status Should Be",
          "Should Be Equal As Strings"
        ]
      },
      {
        name: "DatabaseLibrary",
        keywords: [
          "Connect To Database",
          "Disconnect From Database",
          "Execute Sql String",
          "Query",
          "Row Count Is Equal To",
          "Check If Exists In Database"
        ]
      }
    ], Qe = {
      name: "Control",
      kind: "control",
      items: [
        { label: "IF / ELSE", type: "if" },
        { label: "ELSE IF", type: "else_if" },
        { label: "ELSE", type: "else" },
        { label: "FOR Loop", type: "for" },
        { label: "WHILE Loop", type: "while" },
        { label: "TRY / EXCEPT", type: "try" },
        { label: "EXCEPT", type: "except" },
        { label: "FINALLY", type: "finally" },
        { label: "VAR", type: "var" },
        { label: "RETURN", type: "return" },
        { label: "BREAK", type: "break" },
        { label: "CONTINUE", type: "continue" },
        { label: "Comment", type: "comment" }
      ]
    }, mt = [
      "BuiltIn",
      "Collections",
      "String",
      "DateTime",
      "OperatingSystem",
      "Process",
      "XML",
      "Browser",
      "SeleniumLibrary",
      "RequestsLibrary",
      "DatabaseLibrary"
    ], ut = O(() => {
      if (k.value.length === 0) return [];
      const A = /* @__PURE__ */ new Map();
      for (const M of k.value) {
        const q = A.get(M.file_path) ?? [];
        q.push(M.name), A.set(M.file_path, q);
      }
      const w = [];
      for (const [M, q] of A) {
        const me = M.lastIndexOf("/"), Te = me >= 0 ? M.slice(me + 1) : M, et = me >= 0 ? M.slice(0, me) : "";
        w.push({
          name: Te,
          keywords: q,
          kind: "resource",
          relPath: et,
          isResourceFile: Te.toLowerCase().endsWith(".resource"),
          isCurrentFile: M === r.filePath
        });
      }
      return w.sort((M, q) => {
        const me = Number(q.isCurrentFile) - Number(M.isCurrentFile);
        return me !== 0 ? me : M.name.localeCompare(q.name, void 0, { sensitivity: "base" });
      }), w;
    }), Je = O(() => {
      var q, me;
      const A = [], w = /* @__PURE__ */ new Set(), M = lr(k.value.map((Te) => Te.file_path));
      for (const [Te, et] of b.value)
        M.has(Te.toLowerCase()) || (A.push({
          name: Te,
          keywords: et.map((Kt) => Kt.name),
          kind: "library",
          imported: ((q = r.importedLibraries) == null ? void 0 : q.has(Te.toLowerCase())) ?? !1
        }), w.add(Te));
      for (const Te of mt) {
        if (w.has(Te)) continue;
        const et = ie.find((Kt) => Kt.name === Te);
        et && A.push({
          name: et.name,
          keywords: et.keywords,
          kind: "library",
          isExamples: Te !== "BuiltIn",
          imported: ((me = r.importedLibraries) == null ? void 0 : me.has(Te.toLowerCase())) ?? Te === "BuiltIn"
        });
      }
      return A;
    }), gt = O(() => b.value.size > 0), Ye = N(
      sr(localStorage.getItem(fa)) ?? "mostUsed"
    ), Ke = N(!1);
    function ee(A) {
      Ye.value = A, localStorage.setItem(fa, A), Ke.value = !1;
    }
    const de = N(
      ar(localStorage.getItem(jn))
    ), ne = N(!1), fe = O(
      () => de.value ?? nr({
        hasEnvData: gt.value,
        file: { importCount: r.fileImportCount ?? 0, stepCount: r.fileStepCount ?? 0 }
      })
    );
    function Ae(A) {
      const w = { ...fe.value, [A]: !fe.value[A] };
      de.value = w, localStorage.setItem(jn, JSON.stringify(w));
    }
    function De() {
      const A = { resources: !0, importedLibs: !0, exampleLibs: !0, builtin: !0 };
      de.value = A, localStorage.setItem(jn, JSON.stringify(A));
    }
    const vt = O(() => {
      const A = r.usageCounts ?? /* @__PURE__ */ new Map(), w = ur(Je.value, Ye.value, A);
      return [...Ye.value === "alpha" ? [...ut.value].sort((q, me) => q.name.localeCompare(me.name, void 0, { sensitivity: "base" })) : ut.value, ...w, Qe];
    }), Zt = O(
      () => rr(vt.value, fe.value)
    ), Tt = O(
      () => ir(vt.value, fe.value)
    );
    function nt() {
      re.value.clear();
    }
    Se(() => r.filePath, (A, w) => {
      !A || A === w || nt();
    }), Se(() => g.keywordsLoaded, (A, w) => {
      A && w === !1 && nt();
    });
    const Qt = O(() => {
      const A = W.value.toLowerCase();
      return A ? Zt.value.map((w) => {
        if ("keywords" in w) {
          const q = w.keywords.filter((me) => me.toLowerCase().includes(A));
          return q.length ? { ...w, keywords: q } : null;
        }
        const M = w.items.filter((q) => q.label.toLowerCase().includes(A));
        return M.length ? { ...w, items: M } : null;
      }).filter((w) => w !== null) : Zt.value;
    }), Et = O(
      () => Qt.value.filter((A) => A.kind === "resource")
    ), Ut = O(
      () => Qt.value.filter((A) => A.kind !== "resource")
    );
    function Jt(A = "keyword") {
      return {
        type: A,
        keyword: "",
        args: [],
        returnVars: [],
        condition: "",
        loopVar: "${item}",
        loopFlavor: "IN",
        loopValues: [],
        exceptPattern: "",
        exceptVar: "",
        varScope: "",
        comment: ""
      };
    }
    function St(A, w) {
      const M = Jt("keyword");
      M.keyword = A, M.args = dr(P(A)), s("add-node", M, w);
    }
    function Bt(A) {
      const w = Jt(A);
      A === "if" && (w.condition = "${condition}"), A === "for" && (w.loopVar = "${item}", w.loopFlavor = "IN", w.loopValues = ["@{list}"]), A === "while" && (w.condition = "${condition}"), s("add-node", w);
    }
    function ht(A, w, M) {
      var q, me;
      (q = A.dataTransfer) == null || q.setData("application/rf-keyword", w), M && ((me = A.dataTransfer) == null || me.setData("application/rf-library", M)), A.dataTransfer.effectAllowed = "copy";
    }
    function Lt(A, w) {
      var M;
      (M = A.dataTransfer) == null || M.setData("application/rf-control", w), A.dataTransfer.effectAllowed = "copy";
    }
    return (A, w) => (i(), u("div", mr, [
      o("div", gr, [
        o("h4", null, m(p(c)("flowEditor.palette") || "Keywords"), 1),
        o("div", vr, [
          o("div", hr, [
            o("button", {
              class: "palette-action-btn",
              title: p(c)("flowEditor.sort.title"),
              "data-testid": "palette-sort-btn",
              onClick: w[0] || (w[0] = (M) => {
                Ke.value = !Ke.value, ne.value = !1;
              })
            }, "⇅", 8, yr),
            Ke.value ? (i(), u("div", br, [
              (i(), u(U, null, Y(["mostUsed", "alpha", "importedFirst"], (M) => o("button", {
                key: M,
                class: te(["palette-menu-item", { "palette-menu-item--active": Ye.value === M }]),
                onClick: (q) => ee(M)
              }, [
                o("span", _r, m(Ye.value === M ? "✓" : ""), 1),
                we(" " + m(p(c)(`flowEditor.sort.${M}`)), 1)
              ], 10, wr)), 64))
            ])) : $("", !0)
          ]),
          o("div", kr, [
            o("button", {
              class: te(["palette-action-btn", { "palette-action-btn--on": Tt.value > 0 }]),
              title: p(c)("flowEditor.filter.title"),
              "data-testid": "palette-filter-btn",
              onClick: w[1] || (w[1] = (M) => {
                ne.value = !ne.value, Ke.value = !1;
              })
            }, "▼", 10, Cr),
            ne.value ? (i(), u("div", $r, [
              (i(), u(U, null, Y(["resources", "importedLibs", "exampleLibs", "builtin"], (M) => o("label", {
                key: M,
                class: "palette-menu-item palette-menu-item--check"
              }, [
                o("input", {
                  type: "checkbox",
                  checked: fe.value[M],
                  onChange: (q) => Ae(M)
                }, null, 40, Tr),
                we(" " + m(p(c)(`flowEditor.filter.${M}`)), 1)
              ])), 64))
            ])) : $("", !0)
          ]),
          o("button", {
            class: "palette-action-btn",
            onClick: G,
            title: "Expand all"
          }, "⊞"),
          o("button", {
            class: "palette-action-btn",
            onClick: R,
            title: "Collapse all"
          }, "⊟")
        ])
      ]),
      z(o("input", {
        "onUpdate:modelValue": w[2] || (w[2] = (M) => W.value = M),
        class: "palette-search",
        placeholder: p(c)("flowEditor.searchKeywords") || "Search keywords..."
      }, null, 8, Er), [
        [ae, W.value]
      ]),
      L.value ? (i(), u("div", Sr, [...w[3] || (w[3] = [
        o("span", { class: "palette-offline-title" }, "暂无数据", -1),
        o("span", { class: "palette-offline-hint" }, "平台关键词接口不可用，已回退内置示例", -1)
      ])])) : $("", !0),
      Tt.value > 0 && !W.value ? (i(), u("div", Lr, [
        o("span", null, m(p(c)("flowEditor.filter.hidingCategories", { count: Tt.value })), 1),
        o("button", {
          class: "palette-hiding-clear",
          onClick: De
        }, m(p(c)("flowEditor.filter.clear")), 1)
      ])) : $("", !0),
      x.value ? (i(), u("div", Vr, [
        o("div", Ar, [
          o("span", {
            class: "palette-add-label",
            title: x.value.name
          }, m(x.value.name), 9, Nr),
          ge.value ? (i(), u("span", {
            key: 0,
            class: "palette-add-source",
            title: ge.value
          }, m(ge.value), 9, Rr)) : $("", !0),
          !x.value.type && P(x.value.name).length ? (i(), u("div", Fr, [
            (i(!0), u(U, null, Y(P(x.value.name), (M, q) => (i(), u("span", {
              key: q,
              class: "palette-arg-tag"
            }, m(M), 1))), 128))
          ])) : $("", !0),
          x.value.type ? $("", !0) : (i(), u("div", Pr, [
            be.value ? (i(), u("span", Dr, m(p(c)("flowEditor.docModal.loading")), 1)) : Le.value ? (i(), u("p", {
              key: 1,
              class: "palette-add-doc-text",
              "data-testid": "palette-add-doc-text",
              title: Le.value
            }, m(Le.value), 9, Mr)) : (i(), u("span", xr, m(p(c)("flowEditor.docModal.noDoc")), 1))
          ]))
        ]),
        o("button", {
          class: "palette-add-btn",
          onClick: Ge
        }, "+")
      ])) : $("", !0),
      o("div", Ur, [
        Et.value.length ? (i(), u(U, { key: 0 }, [
          o("div", Br, [
            w[4] || (w[4] = o("span", { class: "palette-section-glyph" }, "📄", -1)),
            o("span", null, m(p(c)("flowEditor.resourcesSectionLabel")), 1),
            o("span", {
              class: "palette-section-info",
              title: p(c)("flowEditor.resourcesSectionHint")
            }, "ⓘ", 8, Kr)
          ]),
          (i(!0), u(U, null, Y(Et.value, (M) => (i(), u("div", {
            key: M.name + (M.relPath || ""),
            class: te(["palette-category palette-category--resource", { "palette-category--current": M.isCurrentFile }])
          }, [
            o("div", {
              class: "category-header",
              onClick: (q) => Z(M.name)
            }, [
              o("span", Or, m(Q(M.name) ? "▼" : "▶"), 1),
              o("span", Ir, m(M.isResourceFile ? "📄" : "🤖"), 1),
              o("span", Wr, [
                o("span", {
                  class: "category-file-name",
                  title: (M.relPath ? M.relPath + "/" : "") + M.name
                }, m(M.name), 9, jr),
                M.relPath ? (i(), u("span", qr, m(M.relPath) + "/", 1)) : $("", !0)
              ]),
              M.isCurrentFile ? (i(), u("span", {
                key: 0,
                class: "category-current-badge",
                title: p(c)("flowEditor.currentFileCategoryHint")
              }, m(p(c)("flowEditor.currentFileCategoryBadge")), 9, zr)) : $("", !0),
              o("span", Gr, m(M.keywords.length), 1)
            ], 8, Hr),
            Q(M.name) ? (i(!0), u(U, { key: 0 }, Y(M.keywords, (q) => (i(), u("div", {
              key: q,
              class: te(["palette-item palette-item-keyword", { selected: Be(q) }]),
              title: q,
              draggable: "true",
              onDragstart: (me) => ht(me, q, j(M, q)),
              onClick: (me) => $e(q, void 0, j(M, q)),
              onDblclick: (me) => St(q, j(M, q))
            }, [
              w[5] || (w[5] = o("span", { class: "palette-icon" }, "⚙", -1)),
              o("div", Xr, [
                o("span", Zr, m(q), 1),
                P(q).length ? (i(), u("span", Qr, "(" + m(P(q).length) + ")", 1)) : $("", !0)
              ])
            ], 42, Yr))), 128)) : $("", !0)
          ], 2))), 128)),
          w[6] || (w[6] = o("div", { class: "palette-section-divider" }, null, -1))
        ], 64)) : $("", !0),
        (i(!0), u(U, null, Y(Ut.value, (M) => (i(), u("div", {
          key: M.name,
          class: "palette-category"
        }, [
          o("div", {
            class: "category-header",
            onClick: (q) => Z(M.name)
          }, [
            o("span", ei, m(Q(M.name) ? "▼" : "▶"), 1),
            o("span", ti, m(M.name), 1),
            "isExamples" in M && M.isExamples ? (i(), u("span", {
              key: 0,
              class: "category-examples-badge",
              title: p(c)("flowEditor.examplesCategoryHint")
            }, m(p(c)("flowEditor.examplesCategoryBadge")), 9, oi)) : $("", !0),
            o("span", ni, m("keywords" in M ? M.keywords.length : M.items.length), 1)
          ], 8, Jr),
          Q(M.name) ? (i(), u(U, { key: 0 }, [
            "keywords" in M ? (i(!0), u(U, { key: 0 }, Y(M.keywords, (q) => (i(), u("div", {
              key: q,
              class: te([
                "palette-item",
                "palette-item-keyword",
                {
                  selected: Be(q),
                  "palette-item--not-imported": !_(M)
                }
              ]),
              title: _(M) ? q : p(c)("flowEditor.keywordNotImportedHint", { library: M.name }),
              draggable: "true",
              onDragstart: (me) => ht(me, q, j(M, q)),
              onClick: (me) => $e(q, void 0, j(M, q)),
              onDblclick: (me) => St(q, j(M, q))
            }, [
              w[7] || (w[7] = o("span", { class: "palette-icon" }, "⚙", -1)),
              o("div", si, [
                o("span", li, m(q), 1),
                P(q).length ? (i(), u("span", ri, "(" + m(P(q).length) + ")", 1)) : $("", !0),
                _(M) ? $("", !0) : (i(), u("span", {
                  key: 1,
                  class: "palette-item-import-badge",
                  title: p(c)("flowEditor.keywordNotImportedBadgeTitle")
                }, "+ lib", 8, ii))
              ])
            ], 42, ai))), 128)) : $("", !0),
            "items" in M ? (i(!0), u(U, { key: 1 }, Y(M.items, (q) => (i(), u("div", {
              key: q.label,
              class: te(["palette-item", "palette-item-control", { selected: Be(q.label) }]),
              title: q.label,
              draggable: "true",
              onDragstart: (me) => Lt(me, q.type),
              onClick: (me) => $e(q.label, q.type),
              onDblclick: (me) => Bt(q.type)
            }, [
              w[8] || (w[8] = o("span", { class: "palette-icon" }, "◆", -1)),
              o("span", ci, m(q.label), 1)
            ], 42, ui))), 128)) : $("", !0)
          ], 64)) : $("", !0)
        ]))), 128)),
        Qt.value.length ? $("", !0) : (i(), u("div", di, m(p(c)("flowEditor.noResults") || "No matching keywords."), 1))
      ])
    ]));
  }
}), fi = /* @__PURE__ */ ze(pi, [["__scopeId", "data-v-a8ed7b26"]]), mi = { class: "kw-autocomplete" }, gi = ["value", "placeholder"], vi = ["aria-selected", "onMouseenter", "onMousedown"], hi = { class: "kw-autocomplete-name" }, yi = { class: "kw-autocomplete-lib" }, bi = /* @__PURE__ */ qe({
  __name: "KeywordAutocompleteInput",
  props: {
    value: {},
    placeholder: {}
  },
  emits: ["update:value", "select"],
  setup(e, { emit: a }) {
    const r = e, c = a, { t: g } = ft(), { argsByName: s } = En(), _ = Yo(), b = N(r.value);
    Se(() => r.value, (D) => {
      b.value = D;
    });
    const k = N(null), V = N(null), L = N(!1), T = N(-1), B = O(() => {
      const D = /* @__PURE__ */ new Map();
      for (const se of _.keywords)
        se.name && D.set(se.name.toLowerCase(), { display: se.name, library: se.library || "" });
      return D;
    });
    function I(D) {
      return D.replace(/\b\w/g, (se) => se.toUpperCase());
    }
    const W = O(() => {
      const D = b.value.trim().toLowerCase();
      if (D.length < 2) return [];
      const se = Array.from(s.value.keys()), oe = [], be = [];
      for (const J of se) {
        if (J === D) continue;
        const H = B.value.get(J), ue = {
          name: (H == null ? void 0 : H.display) ?? I(J),
          library: (H == null ? void 0 : H.library) ?? "BuiltIn"
        };
        J.startsWith(D) ? oe.push(ue) : J.includes(D) && be.push(ue);
      }
      return oe.sort((J, H) => J.name.length - H.name.length || J.name.localeCompare(H.name)), be.sort((J, H) => J.name.localeCompare(H.name)), [...oe.slice(0, 15), ...be.slice(0, 15)];
    });
    function re(D) {
      const se = D.target.value;
      b.value = se, c("update:value", se), L.value = !0, T.value = -1;
    }
    function x(D) {
      b.value = D, c("update:value", D), c("select", D), L.value = !1, T.value = -1;
    }
    function Z(D) {
      const se = W.value;
      if (D.key === "Enter") {
        D.preventDefault(), L.value && T.value >= 0 && T.value < se.length ? x(se[T.value].name) : (c("select", b.value), L.value = !1);
        return;
      }
      if (D.key === "Escape") {
        L.value = !1, T.value = -1;
        return;
      }
      if (D.key === "Tab") {
        L.value = !1, T.value = -1;
        return;
      }
      !L.value || se.length === 0 || (D.key === "ArrowDown" ? (D.preventDefault(), T.value = Math.min(T.value + 1, se.length - 1), Q()) : D.key === "ArrowUp" && (D.preventDefault(), T.value = Math.max(T.value - 1, 0), Q()));
    }
    function Q() {
      je(() => {
        var se, oe;
        const D = (oe = (se = V.value) == null ? void 0 : se.children) == null ? void 0 : oe[T.value];
        D && typeof D.scrollIntoView == "function" && D.scrollIntoView({ block: "nearest" });
      });
    }
    function G() {
      W.value.length > 0 && (L.value = !0);
    }
    function R(D) {
      const se = D.target;
      se instanceof Element && (se.closest(".kw-autocomplete") || (L.value = !1));
    }
    let P = !1;
    function K() {
      P || (document.addEventListener("click", R), P = !0);
    }
    function j() {
      P && (document.removeEventListener("click", R), P = !1);
    }
    return Se(L, (D) => {
      D ? K() : j();
    }), Tn(j), (D, se) => (i(), u("div", mi, [
      o("input", {
        ref_key: "inputRef",
        ref: k,
        value: b.value,
        placeholder: e.placeholder ?? p(g)("flowEditor.keyword"),
        class: "flow-input",
        autocomplete: "off",
        "data-testid": "kw-autocomplete-input",
        onInput: re,
        onFocus: G,
        onKeydown: Z
      }, null, 40, gi),
      L.value && W.value.length > 0 ? (i(), u("ul", {
        key: 0,
        ref_key: "dropdownRef",
        ref: V,
        class: "kw-autocomplete-dropdown",
        role: "listbox",
        "data-testid": "kw-autocomplete-dropdown"
      }, [
        (i(!0), u(U, null, Y(W.value, (oe, be) => (i(), u("li", {
          key: `${oe.library}-${oe.name}-${be}`,
          class: te(["kw-autocomplete-item", { "is-highlighted": be === T.value }]),
          role: "option",
          "aria-selected": be === T.value,
          onMouseenter: (J) => T.value = be,
          onMousedown: (J) => x(oe.name)
        }, [
          o("span", hi, m(oe.name), 1),
          o("span", yi, m(oe.library), 1)
        ], 42, vi))), 128))
      ], 512)) : $("", !0)
    ]));
  }
}), wi = /* @__PURE__ */ ze(bi, [["__scopeId", "data-v-358af914"]]), _i = {
  key: 0,
  class: "modal-header"
}, ki = { class: "modal-body" }, Ci = {
  key: 1,
  class: "modal-footer"
}, $i = /* @__PURE__ */ qe({
  __name: "BaseModal",
  props: {
    modelValue: { type: Boolean },
    title: {},
    size: {}
  },
  emits: ["update:modelValue"],
  setup(e, { emit: a }) {
    const r = a;
    function c() {
      r("update:modelValue", !1);
    }
    function g(s) {
      s.key === "Escape" && c();
    }
    return Go(() => document.addEventListener("keydown", g)), Io(() => document.removeEventListener("keydown", g)), (s, _) => (i(), xt(bn, { to: "body" }, [
      ve(is, { name: "fade" }, {
        default: _e(() => [
          e.modelValue ? (i(), u("div", {
            key: 0,
            class: "modal-backdrop",
            onClick: le(c, ["self"])
          }, [
            o("div", {
              class: te(["modal", [`modal-${e.size || "md"}`]])
            }, [
              e.title ? (i(), u("div", _i, [
                o("h3", null, m(e.title), 1),
                o("button", {
                  class: "modal-close",
                  onClick: c
                }, "×")
              ])) : $("", !0),
              o("div", ki, [
                Xn(s.$slots, "default", {}, void 0, !0)
              ]),
              s.$slots.footer ? (i(), u("div", Ci, [
                Xn(s.$slots, "footer", {}, void 0, !0)
              ])) : $("", !0)
            ], 2)
          ])) : $("", !0)
        ]),
        _: 3
      })
    ]));
  }
}), jo = /* @__PURE__ */ ze($i, [["__scopeId", "data-v-c981bfa5"]]), Ti = {
  key: 0,
  class: "kw-doc"
}, Ei = { class: "kw-doc-header" }, Si = { class: "kw-doc-name" }, Li = { class: "kw-doc-lib" }, Vi = {
  key: 0,
  class: "kw-doc-args"
}, Ai = { class: "kw-doc-body" }, Ni = ["innerHTML"], Ri = { key: 1 }, Fi = {
  key: 2,
  class: "kw-doc-empty"
}, Pi = {
  key: 3,
  class: "kw-doc-empty"
}, Di = {
  key: 1,
  class: "kw-doc"
}, Mi = { class: "kw-doc-empty" }, xi = /* @__PURE__ */ qe({
  __name: "KeywordDocModal",
  props: {
    modelValue: { type: Boolean },
    keyword: {},
    scopeId: {}
  },
  emits: ["update:modelValue"],
  setup(e) {
    const a = e, { t: r } = ft(), { getKeywordInfo: c, fetchKeywordInfo: g } = En(), s = N(null), _ = N(!1), b = O(() => s.value ?? c(a.keyword));
    async function k() {
      if (s.value = null, !a.modelValue || !a.keyword) return;
      const I = c(a.keyword);
      if (!(I != null && I.doc && I.doc.trim().length > 0)) {
        _.value = !0;
        try {
          const W = await g(a.keyword, a.scopeId);
          W && (s.value = W);
        } finally {
          _.value = !1;
        }
      }
    }
    Se(() => [a.modelValue, a.keyword], k, { immediate: !0 });
    const V = O(() => b.value ? b.value.args.map(oa) : []), L = O(() => {
      var I;
      return ((I = b.value) == null ? void 0 : I.docFormat) === "html";
    }), T = O(() => !b.value || !L.value ? "" : Va.sanitize(b.value.doc, {
      ALLOWED_TAGS: [
        "p",
        "br",
        "hr",
        "a",
        "code",
        "pre",
        "span",
        "strong",
        "b",
        "em",
        "i",
        "u",
        "ul",
        "ol",
        "li",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "table",
        "thead",
        "tbody",
        "tr",
        "th",
        "td",
        "blockquote"
      ],
      ALLOWED_ATTR: ["href", "title", "class"],
      ALLOW_DATA_ATTR: !1
    }));
    function B(I) {
      let W = I.name;
      return I.kind === "varargs" && (W = "*" + W), I.kind === "kwargs" && (W = "**" + W), I.type && (W += `: ${I.type}`), I.defaultValue !== null && (W += ` = ${I.defaultValue}`), W;
    }
    return (I, W) => (i(), xt(jo, {
      "model-value": e.modelValue,
      title: p(r)("flowEditor.docModal.title"),
      size: "lg",
      "onUpdate:modelValue": W[0] || (W[0] = (re) => I.$emit("update:modelValue", re))
    }, {
      default: _e(() => [
        b.value ? (i(), u("div", Ti, [
          o("div", Ei, [
            o("span", Si, m(b.value.display), 1),
            o("span", Li, m(b.value.library), 1)
          ]),
          V.value.length > 0 ? (i(), u("div", Vi, [
            o("h4", null, m(p(r)("flowEditor.docModal.argsHeader")), 1),
            o("ul", null, [
              (i(!0), u(U, null, Y(V.value, (re, x) => (i(), u("li", {
                key: x,
                class: "kw-doc-arg"
              }, [
                o("code", null, m(B(re)), 1)
              ]))), 128))
            ])
          ])) : $("", !0),
          o("div", Ai, [
            L.value && b.value.doc.trim().length > 0 ? (i(), u("div", {
              key: 0,
              class: "kw-doc-html",
              innerHTML: T.value
            }, null, 8, Ni)) : b.value.doc.trim().length > 0 ? (i(), u("pre", Ri, m(b.value.doc), 1)) : _.value ? (i(), u("p", Fi, m(p(r)("flowEditor.docModal.loading")), 1)) : (i(), u("p", Pi, m(p(r)("flowEditor.docModal.noDoc")), 1))
          ])
        ])) : (i(), u("div", Di, [
          o("p", Mi, m(p(r)("flowEditor.docModal.noDoc")), 1)
        ]))
      ]),
      _: 1
    }, 8, ["model-value", "title"]));
  }
}), Ui = /* @__PURE__ */ ze(xi, [["__scopeId", "data-v-a75d4fa6"]]), Bi = /* @__PURE__ */ new Set(["text", "css", "role", "aria"]);
function Ki(e) {
  return e.includes(">> nth=") || e.includes(":nth-match(") || e.includes(":nth-of-type(") || e.includes(">>>") || e.includes(">>");
}
function Oa(e) {
  let a;
  return e.strategy === "xpath" ? a = `xpath=${e.value}` : e.strategy === "text" ? a = e.value.startsWith("text=") ? e.value : `text=${e.value}` : a = e.value, !e.verified_unique && Bi.has(e.strategy) && !Ki(a) && !(e.strategy === "css" && e.value.includes("#")) && (a = `${a} >> nth=0`), a;
}
function Ia(e) {
  try {
    const r = new URL(e).host;
    return r ? `iframe[src*="${r}"]` : null;
  } catch {
    return null;
  }
}
function Hi(e) {
  if (!e.frame_chain || e.frame_chain.length === 0) return null;
  const a = [];
  for (const r of e.frame_chain)
    if (r.selector_candidates && r.selector_candidates.length > 0)
      a.push(r.selector_candidates[0].value);
    else {
      const c = r.url ? Ia(r.url) : null;
      if (c === null)
        return null;
      a.push(c);
    }
  return a.join(" >>> ");
}
function _o(e, a) {
  if (a.effective_override != null && a.effective_override.trim() !== "")
    return a.effective_override;
  const r = Oa(a), c = Hi(e);
  if (c !== null)
    return `${c} >>> ${r}`;
  if (e.frame_url) {
    const g = Ia(e.frame_url);
    if (g !== null)
      return `${g} >>> ${r}`;
  }
  return r;
}
const Oi = ["title"], Ii = ["title"], Wi = ["aria-expanded", "aria-label"], ji = {
  key: 1,
  class: "selector-picker__menu",
  role: "listbox"
}, qi = ["aria-selected", "onClick"], zi = { class: "selector-picker__strategy" }, Gi = ["title"], Yi = { class: "selector-picker__score" }, Xi = ["title"], Zi = ["title", "aria-label", "onClick"], Qi = ["title"], Ji = ["value"], eu = ["placeholder", "onKeydown"], tu = ["title"], ou = ["title"], nu = { class: "selector-picker__effective-row" }, au = { class: "selector-picker__effective-label" }, su = ["placeholder", "title", "onKeydown"], lu = ["title"], ru = ["title"], iu = {
  class: "selector-picker__add-row",
  role: "presentation"
}, uu = ["title"], cu = ["value"], du = ["placeholder", "onKeydown"], pu = ["title"], fu = ["title"], mu = { class: "selector-picker__effective-row" }, gu = { class: "selector-picker__effective-label" }, vu = ["placeholder", "title", "onKeydown"], hu = ["title"], yu = ["title"], bu = /* @__PURE__ */ qe({
  __name: "SelectorPicker",
  props: {
    command: {},
    compact: { type: Boolean }
  },
  emits: ["update:activeIndex", "update:candidate", "add:candidate"],
  setup(e, { emit: a }) {
    function r(de, ne, fe) {
      return _o(de, {
        strategy: fe,
        value: ne,
        verified_unique: !1
      });
    }
    const c = e, g = a, { t: s } = ft(), _ = N(!1), b = [
      "css",
      "xpath",
      "text",
      "aria",
      "testid",
      "automation_id",
      "uia_name",
      "uia_class_name"
    ], k = O(() => c.command.selector_candidates.length === 0 ? null : c.command.selector_candidates[c.command.active_candidate_index] ?? null), V = /* @__PURE__ */ new Set(["pw_locator"]), L = O(
      () => c.command.selector_candidates.map((de, ne) => ({ candidate: de, originalIndex: ne })).filter(({ candidate: de }) => !V.has(de.strategy))
    ), T = O(() => L.value.length > 1), B = O(() => L.value.length >= 1);
    function I(de) {
      g("update:activeIndex", de), _.value = !1;
    }
    function W(de) {
      return _o(c.command, de);
    }
    function re(de) {
      return `recorder.selector.strategy.${de}`;
    }
    function x(de) {
      const ne = de.target;
      ne && ne instanceof Element && ne.closest(".selector-picker") || (_.value = !1, ge());
    }
    function Z() {
      document.addEventListener("click", x);
    }
    function Q() {
      document.removeEventListener("click", x);
    }
    function G() {
      _.value = !_.value, _.value ? Z() : Q();
    }
    Tn(Q);
    const R = N(null), P = N(""), K = N("css"), j = N(""), D = N(!1), se = N(null), oe = N(!1), be = N(""), J = N("css"), H = N(""), ue = N(!1), ce = N(null);
    function ge() {
      R.value = null, oe.value = !1;
    }
    function Le(de) {
      const ne = c.command.selector_candidates[de];
      ne && (oe.value = !1, R.value = de, P.value = ne.value, K.value = ne.strategy, j.value = ne.effective_override != null && ne.effective_override.trim() !== "" ? ne.effective_override : _o(c.command, ne), D.value = !1, je(() => {
        var fe, Ae;
        (Ae = (fe = se.value) == null ? void 0 : fe.focus) == null || Ae.call(fe);
      }));
    }
    function $e() {
      if (R.value === null) return;
      const de = P.value.trim();
      if (!de) {
        R.value = null;
        return;
      }
      const ne = j.value.trim(), fe = r(c.command, de, K.value), Ae = ne === "" || ne === fe ? "" : ne;
      g("update:candidate", {
        index: R.value,
        value: de,
        strategy: K.value,
        effective: Ae
      }), R.value = null;
    }
    function Be() {
      R.value = null;
    }
    function Ge() {
      R.value = null, oe.value = !0, be.value = "", J.value = "css", H.value = "", ue.value = !1, je(() => {
        var de, ne;
        (ne = (de = ce.value) == null ? void 0 : de.focus) == null || ne.call(de);
      });
    }
    function ie() {
      const de = be.value.trim();
      if (!de) {
        oe.value = !1;
        return;
      }
      const ne = H.value.trim(), fe = r(c.command, de, J.value), Ae = ne === "" || ne === fe ? "" : ne;
      g("add:candidate", {
        value: de,
        strategy: J.value,
        effective: Ae
      }), oe.value = !1;
    }
    function Qe() {
      oe.value = !1;
    }
    function mt(de) {
      const ne = de.trim();
      return ne.startsWith("//") || ne.startsWith("..") || ne.startsWith("xpath=") ? "xpath" : ne.startsWith("text=") ? "text" : /^\[(data-test-id|data-testid|data-qa|data-test)=/i.test(ne) ? "testid" : /^\[role=/.test(ne) || ne.startsWith("aria/") || /^role=/.test(ne) ? "aria" : "css";
    }
    Se(be, (de) => {
      J.value = mt(de);
    }), Se([P, K], ([de, ne]) => {
      R.value !== null && (D.value || (j.value = r(c.command, de, ne)));
    }), Se([be, J], ([de, ne]) => {
      oe.value && (ue.value || (H.value = r(c.command, de, ne)));
    });
    function ut() {
      D.value = !0;
    }
    function Je() {
      ue.value = !0;
    }
    function gt() {
      j.value = r(
        c.command,
        P.value.trim(),
        K.value
      ), D.value = !1;
    }
    function Ye() {
      H.value = r(
        c.command,
        be.value.trim(),
        J.value
      ), ue.value = !1;
    }
    const Ke = O(() => {
      if (R.value === null) return !1;
      const de = P.value.trim();
      if (!de) return !1;
      const ne = r(c.command, de, K.value), fe = j.value.trim();
      return fe !== "" && fe !== ne;
    }), ee = O(() => {
      if (!oe.value) return !1;
      const de = be.value.trim();
      if (!de) return !1;
      const ne = r(c.command, de, J.value), fe = H.value.trim();
      return fe !== "" && fe !== ne;
    });
    return (de, ne) => k.value ? (i(), u("span", {
      key: 0,
      class: te(["selector-picker", { "selector-picker--compact": e.compact }])
    }, [
      o("span", {
        class: te(["selector-picker__dot", `selector-picker__dot--${p(hn)(k.value.quality_score)}`]),
        title: p(s)(re(k.value.strategy)) + ` · ${k.value.quality_score}/100`,
        "aria-hidden": "true"
      }, null, 10, Oi),
      o("code", {
        class: "selector-picker__value",
        title: k.value.value
      }, m(W(k.value)), 9, Ii),
      B.value ? (i(), u("button", {
        key: 0,
        type: "button",
        class: "selector-picker__toggle",
        "aria-expanded": _.value,
        "aria-label": T.value ? p(s)("recorder.selector.swapAriaLabel") : p(s)("recorder.selector.editOrAddAriaLabel"),
        onClick: le(G, ["stop"])
      }, " ▾ ", 8, Wi)) : $("", !0),
      _.value ? (i(), u("ul", ji, [
        (i(!0), u(U, null, Y(L.value, ({ candidate: fe, originalIndex: Ae }) => (i(), u("li", {
          key: `${fe.strategy}-${fe.value}-${Ae}`,
          role: "option",
          "aria-selected": Ae === e.command.active_candidate_index,
          class: te(["selector-picker__item", { "is-active": Ae === e.command.active_candidate_index }]),
          onClick: (De) => R.value !== Ae && I(Ae)
        }, [
          R.value !== Ae ? (i(), u(U, { key: 0 }, [
            o("span", {
              class: te(["selector-picker__dot", `selector-picker__dot--${p(hn)(fe.quality_score)}`]),
              "aria-hidden": "true"
            }, null, 2),
            o("span", zi, m(p(s)(re(fe.strategy))), 1),
            o("code", {
              class: "selector-picker__value",
              title: fe.value
            }, m(W(fe)), 9, Gi),
            o("span", Yi, m(fe.quality_score), 1),
            fe.verified_unique ? (i(), u("span", {
              key: 0,
              class: "selector-picker__unique",
              "aria-hidden": "true",
              title: p(s)("recorder.selector.verifiedUniqueTitle")
            }, "✓", 8, Xi)) : $("", !0),
            o("button", {
              type: "button",
              class: "selector-picker__edit",
              title: p(s)("recorder.selector.editTitle"),
              "aria-label": p(s)("recorder.selector.editTitle"),
              onClick: le((De) => Le(Ae), ["stop"])
            }, "✏", 8, Zi)
          ], 64)) : (i(), u(U, { key: 1 }, [
            o("span", {
              class: te(["selector-picker__dot", `selector-picker__dot--${p(hn)(fe.quality_score)}`]),
              "aria-hidden": "true"
            }, null, 2),
            z(o("select", {
              "onUpdate:modelValue": ne[0] || (ne[0] = (De) => K.value = De),
              class: "selector-picker__strategy-select",
              title: p(s)("recorder.selector.strategyLabel")
            }, [
              (i(), u(U, null, Y(b, (De) => o("option", {
                key: De,
                value: De
              }, m(p(s)(re(De))), 9, Ji)), 64))
            ], 8, Qi), [
              [it, K.value]
            ]),
            z(o("input", {
              ref_for: !0,
              ref_key: "editInputRef",
              ref: se,
              "onUpdate:modelValue": ne[1] || (ne[1] = (De) => P.value = De),
              class: "selector-picker__edit-input",
              placeholder: p(s)("recorder.selector.valuePlaceholder"),
              onKeydown: [
                Ze(le($e, ["prevent"]), ["enter"]),
                Ze(le(Be, ["prevent"]), ["escape"])
              ]
            }, null, 40, eu), [
              [ae, P.value]
            ]),
            o("button", {
              type: "button",
              class: "selector-picker__edit-action selector-picker__edit-action--save",
              title: p(s)("editor.common.save"),
              onClick: le($e, ["stop"])
            }, "✓", 8, tu),
            o("button", {
              type: "button",
              class: "selector-picker__edit-action selector-picker__edit-action--cancel",
              title: p(s)("editor.common.cancel"),
              onClick: le(Be, ["stop"])
            }, "×", 8, ou),
            o("div", nu, [
              o("label", au, m(p(s)("recorder.selector.effectiveLabel")), 1),
              z(o("input", {
                "onUpdate:modelValue": ne[2] || (ne[2] = (De) => j.value = De),
                class: te(["selector-picker__effective-input", { "is-override": Ke.value }]),
                placeholder: p(s)("recorder.selector.effectivePlaceholder"),
                title: p(s)("recorder.selector.effectiveTitle"),
                "data-testid": "selector-picker-effective-edit",
                onInput: ut,
                onKeydown: [
                  Ze(le($e, ["prevent"]), ["enter"]),
                  Ze(le(Be, ["prevent"]), ["escape"])
                ]
              }, null, 42, su), [
                [ae, j.value]
              ]),
              Ke.value ? (i(), u("button", {
                key: 0,
                type: "button",
                class: "selector-picker__effective-reset",
                title: p(s)("recorder.selector.effectiveResetTitle"),
                "data-testid": "selector-picker-effective-reset-edit",
                onClick: le(gt, ["stop"])
              }, "↺", 8, lu)) : $("", !0),
              Ke.value ? (i(), u("span", {
                key: 1,
                class: "selector-picker__effective-badge",
                title: p(s)("recorder.selector.effectiveOverrideTitle")
              }, m(p(s)("recorder.selector.effectiveOverrideBadge")), 9, ru)) : $("", !0)
            ])
          ], 64))
        ], 10, qi))), 128)),
        o("li", iu, [
          oe.value ? (i(), u(U, { key: 1 }, [
            ne[6] || (ne[6] = o("span", {
              class: "selector-picker__dot selector-picker__dot--neutral",
              "aria-hidden": "true"
            }, null, -1)),
            z(o("select", {
              "onUpdate:modelValue": ne[3] || (ne[3] = (fe) => J.value = fe),
              class: "selector-picker__strategy-select",
              title: p(s)("recorder.selector.strategyLabel")
            }, [
              (i(), u(U, null, Y(b, (fe) => o("option", {
                key: fe,
                value: fe
              }, m(p(s)(re(fe))), 9, cu)), 64))
            ], 8, uu), [
              [it, J.value]
            ]),
            z(o("input", {
              ref_key: "addInputRef",
              ref: ce,
              "onUpdate:modelValue": ne[4] || (ne[4] = (fe) => be.value = fe),
              class: "selector-picker__edit-input",
              placeholder: p(s)("recorder.selector.valuePlaceholder"),
              onKeydown: [
                Ze(le(ie, ["prevent"]), ["enter"]),
                Ze(le(Qe, ["prevent"]), ["escape"])
              ]
            }, null, 40, du), [
              [ae, be.value]
            ]),
            o("button", {
              type: "button",
              class: "selector-picker__edit-action selector-picker__edit-action--save",
              "data-testid": "selector-picker-add-save",
              title: p(s)("editor.common.save"),
              onClick: le(ie, ["stop"])
            }, "✓", 8, pu),
            o("button", {
              type: "button",
              class: "selector-picker__edit-action selector-picker__edit-action--cancel",
              title: p(s)("editor.common.cancel"),
              onClick: le(Qe, ["stop"])
            }, "×", 8, fu),
            o("div", mu, [
              o("label", gu, m(p(s)("recorder.selector.effectiveLabel")), 1),
              z(o("input", {
                "onUpdate:modelValue": ne[5] || (ne[5] = (fe) => H.value = fe),
                class: te(["selector-picker__effective-input", { "is-override": ee.value }]),
                placeholder: p(s)("recorder.selector.effectivePlaceholder"),
                title: p(s)("recorder.selector.effectiveTitle"),
                "data-testid": "selector-picker-effective-add",
                onInput: Je,
                onKeydown: [
                  Ze(le(ie, ["prevent"]), ["enter"]),
                  Ze(le(Qe, ["prevent"]), ["escape"])
                ]
              }, null, 42, vu), [
                [ae, H.value]
              ]),
              ee.value ? (i(), u("button", {
                key: 0,
                type: "button",
                class: "selector-picker__effective-reset",
                title: p(s)("recorder.selector.effectiveResetTitle"),
                "data-testid": "selector-picker-effective-reset-add",
                onClick: le(Ye, ["stop"])
              }, "↺", 8, hu)) : $("", !0),
              ee.value ? (i(), u("span", {
                key: 1,
                class: "selector-picker__effective-badge",
                title: p(s)("recorder.selector.effectiveOverrideTitle")
              }, m(p(s)("recorder.selector.effectiveOverrideBadge")), 9, yu)) : $("", !0)
            ])
          ], 64)) : (i(), u("button", {
            key: 0,
            type: "button",
            class: "selector-picker__add-btn",
            "data-testid": "selector-picker-add",
            onClick: le(Ge, ["stop"])
          }, "+ " + m(p(s)("recorder.selector.addCustom")), 1))
        ])
      ])) : $("", !0)
    ], 2)) : $("", !0);
  }
}), wu = /* @__PURE__ */ ze(bu, [["__scopeId", "data-v-00ec4dc3"]]);
async function Wa(e) {
  return ye().startDebugSession(e);
}
async function _u(e) {
  return Wa(e);
}
async function ga(e, a) {
  return ye().postDebugControl(e, a);
}
async function ku(e) {
  return ye().getDebugState(e);
}
async function Cu(e) {
  return ye().installDebugPrerequisites(e);
}
function qn(e) {
  if (e && typeof e == "object" && "response" in e) {
    const a = e.response;
    if (a && typeof a == "object" && "status" in a) {
      const r = a.status;
      if (typeof r == "number") return r;
    }
  }
  return null;
}
function ko(e, a) {
  if (e && typeof e == "object" && "response" in e) {
    const r = e.response;
    if (r && typeof r == "object" && "data" in r) {
      const c = r.data;
      if (c && typeof c == "object" && "detail" in c) {
        const g = c.detail;
        if (typeof g == "string" && g.length > 0)
          return g;
        if (g && typeof g == "object" && "message" in g && typeof g.message == "string")
          return g.message;
      }
      if (typeof c == "string" && c.length > 0)
        return c;
    }
  }
  return a;
}
const $u = { file: null, line: null, keyword: null }, zn = {
  session_id: "",
  paused: !1,
  terminated: !1,
  paused_at: $u,
  scopes: [],
  call_stack: [],
  output_lines: []
}, ja = Co("debug", () => {
  const e = N(null), a = N(null), r = N(null), c = N(null), g = N({ ...zn }), s = N([]), _ = N(!1), b = N(null), k = N(null), V = N(!1), L = N(null);
  let T = null;
  const B = O(() => e.value !== null && !g.value.terminated), I = O(() => g.value.paused), W = O(() => g.value.paused_at), re = O(() => g.value.scopes), x = O(() => g.value.call_stack);
  async function Z(H) {
    _.value = !0, b.value = null;
    try {
      try {
        const ue = await Wa({ run_id: H });
        return R(ue), ue;
      } catch (ue) {
        if (qn(ue) === 424)
          return se(ue, { kind: "run", runId: H }), null;
        throw ue;
      }
    } finally {
      _.value = !1;
    }
  }
  async function Q(H) {
    var ue, ce;
    _.value = !0, b.value = null;
    try {
      try {
        const ge = await _u(H);
        return R(ge), { resp: ge, resumed: !1 };
      } catch (ge) {
        if (qn(ge) === 424)
          return se(ge, { kind: "step", payload: H }), null;
        if (qn(ge) === 409) {
          const Le = (ce = (ue = ge == null ? void 0 : ge.response) == null ? void 0 : ue.data) == null ? void 0 : ce.detail;
          if (Le && typeof Le == "object") {
            const $e = Le, Be = {
              session_id: String($e.session_id ?? ""),
              robot_file: String($e.robot_file ?? H.file),
              breakpoint_line: Number($e.breakpoint_line ?? H.line),
              test_name: $e.test_name ?? null
            };
            if (Be.session_id)
              return R(Be), { resp: Be, resumed: !0 };
          }
        }
        throw ge;
      }
    } finally {
      _.value = !1;
    }
  }
  function G(H, ue) {
    return !e.value || g.value.terminated ? "idle" : a.value === H && r.value === ue ? "same" : "different";
  }
  function R(H) {
    e.value = H.session_id, a.value = H.robot_file, r.value = H.breakpoint_line, c.value = H.test_name, g.value = { ...zn, session_id: H.session_id }, s.value = [];
  }
  async function P() {
    e.value && (g.value = await ku(e.value));
  }
  async function K(H) {
    e.value && await ga(e.value, H);
  }
  async function j() {
    if (!e.value) return;
    const H = e.value;
    try {
      await ga(H, "disconnect");
    } finally {
      D();
    }
  }
  function D() {
    e.value = null, a.value = null, r.value = null, c.value = null, g.value = { ...zn }, s.value = [], b.value = null, k.value = null, V.value = !1, L.value = null, T = null;
  }
  function se(H, ue) {
    var ge, Le;
    T = ue;
    const ce = (Le = (ge = H == null ? void 0 : H.response) == null ? void 0 : ge.data) == null ? void 0 : Le.detail;
    if (ce && typeof ce == "object") {
      const $e = ce;
      k.value = {
        scopeId: Number($e.scope_id ?? 0),
        envId: Number($e.env_id ?? 0),
        packageName: String($e.package ?? "robotcode"),
        message: String($e.message ?? "")
      };
    } else
      k.value = {
        scopeId: 0,
        envId: 0,
        packageName: "robotcode",
        message: ""
      };
    L.value = null;
  }
  async function oe() {
    if (!k.value || !T) return !1;
    V.value = !0, L.value = null;
    try {
      await Cu(k.value.scopeId);
    } catch (ue) {
      return L.value = ko(ue, "Install failed"), V.value = !1, !1;
    }
    const H = T;
    k.value = null, T = null, V.value = !1;
    try {
      return H.kind === "run" ? await Z(H.runId) !== null : await Q(H.payload) !== null;
    } catch (ue) {
      return b.value = ko(ue, "Retry failed"), !1;
    }
  }
  function be() {
    k.value = null, T = null, V.value = !1, L.value = null;
  }
  function J(H) {
    if (e.value && H.topic === `debug:session:${e.value}`)
      switch (H.kind) {
        case "state":
          g.value = H.body;
          break;
        case "output": {
          const ue = String(H.body.output ?? "").replace(/\n+$/, "");
          ue && s.value.push(ue), s.value.length > 300 && (s.value = s.value.slice(-300));
          break;
        }
        case "terminated":
          g.value = { ...g.value, terminated: !0, paused: !1 };
          break;
      }
  }
  return {
    sessionId: e,
    robotFile: a,
    breakpointLine: r,
    testName: c,
    state: g,
    outputLog: s,
    loading: _,
    lastError: b,
    isActive: B,
    paused: I,
    pausedAt: W,
    scopes: re,
    callStack: x,
    prereqMissing: k,
    installing: V,
    installError: L,
    startFromRun: Z,
    startFromStep: Q,
    classifyStepClick: G,
    refreshState: P,
    control: K,
    stop: j,
    reset: D,
    handleWsEvent: J,
    installPrereqAndRetry: oe,
    cancelPrereq: be
  };
}), Tu = {
  class: "debug-panel",
  "data-testid": "debug-panel"
}, Eu = { class: "debug-panel__header" }, Su = { class: "debug-panel__title" }, Lu = { class: "debug-panel__paused-at" }, Vu = {
  key: 0,
  class: "debug-panel__badge debug-panel__badge--terminated"
}, Au = {
  key: 1,
  class: "debug-panel__badge debug-panel__badge--paused"
}, Nu = { class: "debug-panel__toolbar" }, Ru = {
  key: 0,
  class: "debug-panel__error"
}, Fu = {
  key: 1,
  class: "debug-panel__line-callout",
  "data-testid": "debug-line-callout"
}, Pu = {
  class: "debug-panel__line-number",
  "data-testid": "debug-line-number"
}, Du = { class: "debug-panel__line-label" }, Mu = { class: "debug-panel__line-value" }, xu = { class: "debug-panel__line-meta" }, Uu = ["title"], Bu = {
  key: 1,
  class: "debug-panel__line-keyword"
}, Ku = { class: "debug-panel__body" }, Hu = { class: "debug-panel__stack" }, Ou = {
  key: 0,
  class: "debug-panel__stack-list"
}, Iu = ["title"], Wu = ["title"], ju = { key: 0 }, qu = {
  key: 1,
  class: "debug-panel__empty"
}, zu = { class: "debug-panel__scopes" }, Gu = {
  key: 0,
  class: "debug-panel__empty"
}, Yu = ["aria-expanded", "onClick"], Xu = { class: "debug-panel__scope-chevron" }, Zu = { class: "debug-panel__scope-name" }, Qu = { class: "debug-panel__scope-count" }, Ju = {
  key: 0,
  class: "debug-panel__scope-vars"
}, ec = {
  key: 0,
  class: "debug-panel__empty"
}, tc = { class: "debug-panel__var-name" }, oc = {
  key: 0,
  class: "debug-panel__var-type"
}, nc = { class: "debug-panel__var-value" }, ac = { class: "debug-panel__output" }, sc = {
  key: 0,
  class: "debug-panel__empty"
}, lc = {
  key: 1,
  class: "debug-panel__output-log"
}, rc = /* @__PURE__ */ qe({
  __name: "DebugPanel",
  props: {
    runId: {}
  },
  emits: ["closed"],
  setup(e, { emit: a }) {
    const r = e, c = a, { t: g } = ft(), s = ja(), _ = N(null);
    Go(async () => {
      if (r.runId !== void 0 && !s.isActive)
        try {
          await s.startFromRun(r.runId);
        } catch (G) {
          _.value = ko(G, g("debug.error.startFailed"));
        }
    }), Io(() => {
    });
    const b = O(() => {
      const G = s.pausedAt;
      if (!G.file && !G.keyword) return g("debug.panel.notPaused");
      const R = G.line !== null ? `${G.file ?? ""}:${G.line}` : G.file ?? "";
      return G.keyword ? `${G.keyword}  —  ${R}` : R;
    });
    function k(G) {
      if (!G) return "";
      const R = Math.max(G.lastIndexOf("/"), G.lastIndexOf("\\"));
      return R >= 0 ? G.slice(R + 1) : G;
    }
    const V = O(() => k(s.pausedAt.file));
    async function L() {
      await re(() => s.control("continue"));
    }
    async function T() {
      await re(() => s.control("next"));
    }
    async function B() {
      await re(() => s.control("stepIn"));
    }
    async function I() {
      await re(() => s.control("stepOut"));
    }
    async function W() {
      try {
        await s.stop();
      } catch {
      } finally {
        c("closed");
      }
    }
    async function re(G) {
      _.value = null;
      try {
        await G();
      } catch (R) {
        _.value = ko(R, g("debug.error.commandFailed"));
      }
    }
    const x = N(/* @__PURE__ */ new Set(["Local"]));
    function Z(G) {
      x.value.has(G) ? x.value.delete(G) : x.value.add(G), x.value = new Set(x.value);
    }
    function Q(G, R = 200) {
      return G.length <= R ? G : G.slice(0, R) + "…";
    }
    return (G, R) => (i(), u("div", Tu, [
      o("header", Eu, [
        o("div", Su, [
          R[0] || (R[0] = o("span", {
            class: "debug-panel__icon",
            "aria-hidden": "true"
          }, "🐞", -1)),
          o("span", Lu, m(b.value), 1),
          p(s).state.terminated ? (i(), u("span", Vu, m(p(g)("debug.panel.terminated")), 1)) : p(s).paused ? (i(), u("span", Au, m(p(g)("debug.panel.paused")), 1)) : $("", !0)
        ]),
        o("div", Nu, [
          ve(Oe, {
            size: "sm",
            variant: "primary",
            disabled: !p(s).paused || p(s).state.terminated,
            onClick: L
          }, {
            default: _e(() => [
              we("▶ " + m(p(g)("debug.panel.toolbar.continue")), 1)
            ]),
            _: 1
          }, 8, ["disabled"]),
          ve(Oe, {
            size: "sm",
            variant: "ghost",
            disabled: !p(s).paused || p(s).state.terminated,
            onClick: T
          }, {
            default: _e(() => [
              we("⤼ " + m(p(g)("debug.panel.toolbar.stepOver")), 1)
            ]),
            _: 1
          }, 8, ["disabled"]),
          ve(Oe, {
            size: "sm",
            variant: "ghost",
            disabled: !p(s).paused || p(s).state.terminated,
            onClick: B
          }, {
            default: _e(() => [
              we("↳ " + m(p(g)("debug.panel.toolbar.stepIn")), 1)
            ]),
            _: 1
          }, 8, ["disabled"]),
          ve(Oe, {
            size: "sm",
            variant: "ghost",
            disabled: !p(s).paused || p(s).state.terminated,
            onClick: I
          }, {
            default: _e(() => [
              we("↰ " + m(p(g)("debug.panel.toolbar.stepOut")), 1)
            ]),
            _: 1
          }, 8, ["disabled"]),
          ve(Oe, {
            size: "sm",
            variant: "danger",
            onClick: W
          }, {
            default: _e(() => [
              we(" ✕ " + m(p(g)("debug.panel.toolbar.stop")), 1)
            ]),
            _: 1
          })
        ])
      ]),
      _.value ? (i(), u("div", Ru, m(_.value), 1)) : $("", !0),
      p(s).paused && p(s).pausedAt.line !== null ? (i(), u("div", Fu, [
        o("div", Pu, [
          o("span", Du, m(p(g)("debug.panel.lineLabel")), 1),
          o("span", Mu, m(p(s).pausedAt.line), 1)
        ]),
        o("div", xu, [
          V.value ? (i(), u("span", {
            key: 0,
            class: "debug-panel__line-file",
            title: p(s).pausedAt.file ?? ""
          }, m(V.value), 9, Uu)) : $("", !0),
          p(s).pausedAt.keyword ? (i(), u("span", Bu, m(p(s).pausedAt.keyword), 1)) : $("", !0)
        ])
      ])) : $("", !0),
      o("div", Ku, [
        o("aside", Hu, [
          o("h3", null, m(p(g)("debug.panel.callStack")), 1),
          p(s).callStack.length ? (i(), u("ul", Ou, [
            (i(!0), u(U, null, Y(p(s).callStack, (P, K) => (i(), u("li", {
              key: K,
              class: te({ "debug-panel__stack-item": !0, "is-top": K === 0 })
            }, [
              o("span", {
                class: "debug-panel__stack-name",
                title: P.name
              }, m(P.name), 9, Iu),
              P.file ? (i(), u("span", {
                key: 0,
                class: "debug-panel__stack-file",
                title: P.file + (P.line ? ":" + P.line : "")
              }, [
                we(m(k(P.file)), 1),
                P.line ? (i(), u("span", ju, ":" + m(P.line), 1)) : $("", !0)
              ], 8, Wu)) : $("", !0)
            ], 2))), 128))
          ])) : (i(), u("p", qu, m(p(g)("debug.panel.callStackEmpty")), 1))
        ]),
        o("section", zu, [
          o("h3", null, m(p(g)("debug.panel.scopes")), 1),
          p(s).scopes.length ? $("", !0) : (i(), u("div", Gu, m(p(g)("debug.panel.scopesEmpty")), 1)),
          (i(!0), u(U, null, Y(p(s).scopes, (P) => (i(), u("div", {
            key: P.name,
            class: "debug-panel__scope"
          }, [
            o("button", {
              class: "debug-panel__scope-header",
              "aria-expanded": x.value.has(P.name),
              onClick: (K) => Z(P.name)
            }, [
              o("span", Xu, m(x.value.has(P.name) ? "▾" : "▸"), 1),
              o("span", Zu, m(P.name), 1),
              o("span", Qu, m(P.variables.length), 1)
            ], 8, Yu),
            x.value.has(P.name) ? (i(), u("div", Ju, [
              P.variables.length ? $("", !0) : (i(), u("div", ec, m(p(g)("debug.panel.scopeEmpty")), 1)),
              (i(!0), u(U, null, Y(P.variables, (K) => (i(), u("div", {
                key: K.name,
                class: "debug-panel__var"
              }, [
                o("span", tc, m(K.name), 1),
                K.type ? (i(), u("span", oc, "(" + m(K.type) + ")", 1)) : $("", !0),
                o("span", nc, m(Q(K.value)), 1)
              ]))), 128))
            ])) : $("", !0)
          ]))), 128))
        ])
      ]),
      o("footer", ac, [
        o("h3", null, m(p(g)("debug.panel.output")), 1),
        p(s).outputLog.length === 0 ? (i(), u("div", sc, m(p(g)("debug.panel.outputEmpty")), 1)) : (i(), u("pre", lc, m(p(s).outputLog.join(`
`)), 1))
      ])
    ]));
  }
}), ic = /* @__PURE__ */ ze(rc, [["__scopeId", "data-v-77a3a274"]]), uc = { class: "prereq-body" }, cc = {
  key: 0,
  class: "prereq-error",
  role: "alert"
}, dc = {
  key: 1,
  class: "prereq-status"
}, pc = /* @__PURE__ */ qe({
  __name: "DebugPrereqDialog",
  props: {
    open: { type: Boolean },
    packageName: {},
    installing: { type: Boolean },
    installError: {}
  },
  emits: ["install", "cancel"],
  setup(e, { emit: a }) {
    const r = e, c = a, { t: g } = ft();
    function s() {
      c("install");
    }
    function _() {
      c("cancel");
    }
    return (b, k) => (i(), u(U, null, [
      r.open ? (i(), xt(bn, {
        key: 0,
        to: "body"
      }, [
        k[0] || (k[0] = o("span", {
          "data-testid": "debug-prereq-dialog",
          style: { display: "none" },
          "aria-hidden": "true"
        }, null, -1))
      ])) : $("", !0),
      ve(jo, {
        "model-value": r.open,
        title: p(g)("debug.prereq.title"),
        size: "md"
      }, {
        footer: _e(() => [
          ve(Oe, {
            variant: "ghost",
            disabled: r.installing,
            "data-testid": "debug-prereq-cancel-btn",
            onClick: _
          }, {
            default: _e(() => [
              we(m(p(g)("debug.prereq.cancel")), 1)
            ]),
            _: 1
          }, 8, ["disabled"]),
          ve(Oe, {
            variant: "primary",
            loading: r.installing,
            disabled: r.installing,
            "data-testid": "debug-prereq-install-btn",
            onClick: s
          }, {
            default: _e(() => [
              we(m(r.installing ? p(g)("debug.prereq.installing", { package: r.packageName }) : p(g)("debug.prereq.install")), 1)
            ]),
            _: 1
          }, 8, ["loading", "disabled"])
        ]),
        default: _e(() => [
          o("p", uc, m(p(g)("debug.prereq.body", { package: r.packageName })), 1),
          r.installError ? (i(), u("p", cc, m(r.installError), 1)) : r.installing ? (i(), u("p", dc, m(p(g)("debug.prereq.installing", { package: r.packageName })), 1)) : $("", !0)
        ]),
        _: 1
      }, 8, ["model-value", "title"])
    ], 64));
  }
}), fc = /* @__PURE__ */ ze(pc, [["__scopeId", "data-v-82fc15e8"]]), mc = (e) => ye().login(e.email, e.password), gc = () => ye().getMe(), vc = (e) => ye().patchFirstLoginComplete(e);
async function hc() {
  const e = await ye().getFeatures();
  return { flags: e.flags ?? {}, locked: e.locked ?? {} };
}
const Zn = N({}), Qn = N({}), yn = N(!1);
let gn = null;
async function va() {
  try {
    const e = await hc();
    Zn.value = e.flags, Qn.value = e.locked, yn.value = !0;
  } catch {
  }
}
function ha() {
  !yn.value && !gn && (gn = va().finally(() => {
    gn = null;
  }));
  function e(c) {
    return Zn.value[c] !== !1;
  }
  function a(c) {
    return Qn.value[c] === !0;
  }
  function r() {
    yn.value = !1, Zn.value = {}, Qn.value = {}, gn = null;
  }
  return { isEnabled: e, isLocked: a, refresh: va, reset: r, loaded: yn };
}
const yc = Co("auth", () => {
  const e = N(null), a = N(localStorage.getItem("access_token")), r = O(() => !!a.value), c = O(() => e.value), g = O(() => {
    var L;
    return ((L = e.value) == null ? void 0 : L.role) ?? "viewer";
  });
  function s(L) {
    const T = { viewer: 0, runner: 1, editor: 2, admin: 3 };
    return (T[g.value] ?? -1) >= (T[L] ?? 999);
  }
  async function _(L, T) {
    const B = await mc({ email: L, password: T });
    a.value = B.access_token, localStorage.setItem("access_token", B.access_token), localStorage.setItem("refresh_token", B.refresh_token), await b(), ha().refresh();
  }
  async function b() {
    if (a.value)
      try {
        e.value = await gc();
      } catch {
        k();
      }
  }
  function k() {
    e.value = null, a.value = null, localStorage.removeItem("access_token"), localStorage.removeItem("refresh_token"), ha().reset();
  }
  async function V() {
    const L = await vc(!0);
    e.value = L;
  }
  return {
    user: e,
    token: a,
    isAuthenticated: r,
    currentUser: c,
    userRole: g,
    hasMinRole: s,
    login: _,
    logout: k,
    fetchCurrentUser: b,
    markFirstLoginComplete: V
  };
}), Gn = 768, bc = Co("ui", () => {
  const e = N(window.innerWidth >= Gn), a = N(window.innerWidth), r = N([]);
  let c = 0;
  const g = O(() => a.value < Gn), s = N(localStorage.getItem("notifications_enabled") === "true"), _ = N(
    typeof Notification < "u" ? Notification.permission : "denied"
  );
  async function b() {
    if (typeof Notification > "u") return !1;
    const G = await Notification.requestPermission();
    return _.value = G, G === "granted";
  }
  async function k() {
    if (s.value) {
      s.value = !1, localStorage.setItem("notifications_enabled", "false");
      return;
    }
    _.value !== "granted" && !await b() || (s.value = !0, localStorage.setItem("notifications_enabled", "true"));
  }
  function V(G, R, P) {
    s.value && (typeof Notification > "u" || Notification.permission !== "granted" || document.hasFocus() || new Notification(G, { body: R, tag: P, icon: "/favicon.ico" }));
  }
  function L() {
    a.value = window.innerWidth, a.value < Gn && (e.value = !1);
  }
  window.addEventListener("resize", L);
  function T() {
    e.value = !e.value;
  }
  function B() {
    g.value && (e.value = !1);
  }
  function I(G, R, P = "info", K = 5e3) {
    const j = ++c;
    r.value.push({ id: j, title: G, message: R, type: P, timeout: K }), K > 0 && setTimeout(() => W(j), K);
  }
  function W(G) {
    r.value = r.value.filter((R) => R.id !== G);
  }
  function re(G, R = "") {
    I(G, R, "success");
  }
  function x(G, R = "") {
    I(G, R, "error", 8e3);
  }
  function Z(G, R = "") {
    I(G, R, "info");
  }
  function Q(G, R = "") {
    I(G, R, "warning");
  }
  return { sidebarOpen: e, isMobile: g, windowWidth: a, toasts: r, notificationsEnabled: s, notificationPermission: _, toggleSidebar: T, closeSidebarOnMobile: B, addToast: I, removeToast: W, success: re, error: x, info: Z, warning: Q, toggleNotifications: k, sendBrowserNotification: V };
});
function Jn() {
  const e = bc();
  return {
    success: e.success,
    error: e.error,
    info: e.info,
    warning: e.warning
  };
}
const wc = /* @__PURE__ */ new Set([
  "keyword",
  "assignment",
  "var",
  "for",
  "end",
  "if",
  "else_if",
  "else",
  "while",
  "try",
  "except",
  "finally",
  "break",
  "continue",
  "return",
  "comment"
]);
function _c(e) {
  return wc.has(e);
}
const kc = /* @__PURE__ */ new Set(["keyword", "assignment"]);
function Cn(e) {
  return kc.has(e.type);
}
function Cc(e, a) {
  if (a < 0 || a >= e.length || !Cn(e[a])) return -1;
  let r = 0;
  for (let c = 0; c < a; c++)
    Cn(e[c]) && r++;
  return r;
}
function $c(e, a, r) {
  if (!a || r < 0 || r >= e.length) return null;
  const c = e[r];
  if (!Cn(c)) return null;
  if (c.rbs_id)
    return a.commands.find((_) => _.id === c.rbs_id) ?? null;
  if (e.some((_) => Cn(_) && !!_.rbs_id)) return null;
  const s = Cc(e, r);
  return s < 0 ? null : a.commands[s] ?? null;
}
function Tc(e, a) {
  if (!a || !e.keyword) return null;
  const r = a.get(e.keyword.toLowerCase());
  return r ? r.map(oa).filter(
    (c) => c.kind !== "positional-only-sep" && c.kind !== "named-only-sep" && c.kind !== "optional-sep"
  ) : null;
}
function Ec(e, a, r) {
  const c = a.selector_candidates[r];
  if (!c) return !1;
  const g = _o(a, c);
  return e.args.length === 0 ? e.args.push(g) : e.args[0] = g, a.active_candidate_index = r, !0;
}
function ya(e, a) {
  return _o(e, a);
}
const Sc = /\s*>>\s*nth=\d+\s*$/;
function Lc(e) {
  let a = e;
  const r = a.lastIndexOf(" >>> ");
  return r >= 0 && (a = a.slice(r + 5)), a.replace(Sc, "").trim();
}
function ba(e, a) {
  if (a.selector_candidates.length === 0) return !1;
  const r = e.args[0] ?? "";
  if (r === "" || a.selector_candidates.some((g) => g.value === r) || a.selector_candidates.some(
    (g) => _o(a, g) === r
  )) return !1;
  const c = Lc(r);
  return c === r ? !0 : !a.selector_candidates.some(
    (g) => g.value === c || Oa(g) === c
  );
}
const ea = 50, Vc = 60, co = 0, wa = 30, _a = 12, Ac = 16;
function Nc(e) {
  return {
    ...e,
    args: [...e.args],
    returnVars: [...e.returnVars],
    loopValues: [...e.loopValues],
    // _lineNumber is a primitive — covered by `...step`. Listed here
    // explicitly so a future contributor sees that DEBUG-3 metadata
    // intentionally rides through the clone.
    _lineNumber: e._lineNumber
  };
}
const aa = 32, Rc = 44;
function Oo(e) {
  const a = Xa(e.type);
  if (a === "start" || a === "end") return aa;
  let r = Rc;
  return (a === "keyword" || a === "assignment") && (e.args.length > 0 && (r += 4 + e.args.length * 22), e.returnVars.length > 0 && (r += 20)), a === "control" && (e.condition && (r += 20), (e.type === "for" || e.type === "while") && e.loopVar && e.loopValues.length > 0 && (r += 20), e.exceptPattern && (r += 20)), r;
}
const qa = /* @__PURE__ */ new Set(["if", "for", "while", "try"]);
function Fc(e, a) {
  let r = 1, c = a + 1;
  for (; c < e.length && r > 0; )
    qa.has(e[c].type) && r++, e[c].type === "end" && r--, c++;
  return c - 1;
}
const Pc = {
  if: { border: "#E8A838", bg: "rgba(232, 168, 56, 0.06)" },
  for: { border: "#7B61FF", bg: "rgba(123, 97, 255, 0.06)" },
  while: { border: "#7B61FF", bg: "rgba(123, 97, 255, 0.06)" },
  try: { border: "#38B2AC", bg: "rgba(56, 178, 172, 0.06)" }
};
function Dc(e, a, r) {
  za(e, a, r, 0, e.length, 0);
}
function za(e, a, r, c, g, s) {
  let _ = c;
  for (; _ < g; )
    if (qa.has(e[_].type)) {
      const b = Fc(e, _), k = [];
      for (let V = _; V < b; V++) {
        if (e[V].type === "end") continue;
        const L = a.find((T) => T.id === `${r}-step-${V}`);
        L && k.push(L);
      }
      if (k.length > 0) {
        const V = Math.min(...k.map((re) => re.position.y)), L = Math.max(...k.map((re) => re.position.y)), T = k[k.length - 1].data, B = Oo(T.step), I = Pc[e[_].type] || { border: "#888", bg: "rgba(0,0,0,0.03)" }, W = s * 14;
        a.push({
          id: `${r}-frame-${_}`,
          type: "control-frame",
          position: { x: co - wa + W, y: V - _a },
          data: { stepType: e[_].type, borderColor: I.border, bgColor: I.bg },
          style: {
            width: `${320 + (wa - W) * 2}px`,
            height: `${L - V + B + _a + Ac}px`
          },
          zIndex: -1 - s,
          selectable: !1,
          draggable: !1
        });
      }
      za(e, a, r, _ + 1, b, s + 1), _ = b + 1;
    } else
      _++;
}
function Ga(e, a, r, c, g, s = null, _ = null) {
  const b = [], k = [];
  b.push({
    id: `${r}-start`,
    type: "start",
    position: { x: co, y: 0 },
    data: { label: a || void 0 }
  });
  let V = `${r}-start`, L = aa + ea;
  for (let B = 0; B < e.length; B++) {
    const I = e[B];
    if (I.type === "end") continue;
    const W = `${r}-step-${B}`, re = Xa(I.type), x = jc(I), Z = Oo(I);
    b.push({
      id: W,
      type: re,
      position: { x: co, y: L },
      data: {
        label: x,
        stepType: I.type,
        // Deep-clone the step's arrays so v-model edits inside the
        // detail panel don't mutate the form's arrays directly. The
        // shallow spread `{ ...step }` previously left `args` /
        // `returnVars` / `loopValues` as SHARED REFERENCES, so each
        // keystroke fired the deep watcher on `props.form` — which
        // reset `selectedNode` and tore down the panel mid-edit.
        // `updateStepFromNode` replaces the form's arrays on blur via
        // `Object.assign`, so the round-trip still lands.
        step: Nc(I),
        section: c,
        sectionIndex: g,
        stepIndex: B,
        recording: $c(e, s, B),
        argSpecs: Tc(I, _),
        bdd: I.type === "keyword" || I.type === "assignment" ? Aa(I.keyword) : null,
        envRefs: Fa([
          ...I.args,
          I.condition,
          ...I.loopValues
        ])
      }
    });
    const Q = qc(I, B > 0 ? e[B - 1] : void 0);
    k.push({
      id: `${r}-e-${V}-${W}`,
      source: V,
      target: W,
      animated: I.type === "if" || I.type === "for" || I.type === "try",
      label: Q
    }), V = W, L += Z + ea;
  }
  const T = `${r}-end`;
  return b.push({
    id: T,
    type: "end",
    position: { x: co, y: L },
    data: {}
  }), k.push({
    id: `${r}-e-${V}-${T}`,
    source: V,
    target: T
  }), Dc(e, b, r), { nodes: b, edges: k };
}
const Mc = [
  "documentation",
  "tags",
  "setup",
  "teardown",
  "template",
  "timeout"
], xc = [
  "documentation",
  "arguments",
  "tags",
  "setup",
  "teardown",
  "timeout"
], Uc = {
  documentation: "[Documentation]",
  tags: "[Tags]",
  arguments: "[Arguments]",
  setup: "[Setup]",
  teardown: "[Teardown]",
  template: "[Template]",
  timeout: "[Timeout]"
};
function Bc(e, a) {
  switch (a) {
    case "documentation":
      return e.documentation;
    case "tags":
      return e.tags.join(", ");
    case "arguments":
      return "arguments" in e ? e.arguments.join(", ") : "";
    case "setup":
      return e.setup;
    case "teardown":
      return e.teardown;
    case "template":
      return "template" in e ? e.template : "";
    case "timeout":
      return e.timeout;
  }
}
function Kc(e, a) {
  switch (a) {
    case "tags":
      return e.tags.length > 0;
    case "arguments":
      return "arguments" in e && e.arguments.length > 0;
    case "template":
      return "template" in e && e.template !== "";
    case "documentation":
    case "setup":
    case "teardown":
    case "timeout":
      return e[a] !== "";
  }
}
function Hc(e, a, r = null, c = null) {
  const g = Ga(e.steps, e.name, `tc${a}`, "testcase", a, r, c);
  if (Ya(g.nodes, g.edges, `tc${a}`, e, "testcase", a, Mc), e.templateRows && e.templateRows.length > 0) {
    const s = `tc${a}`, _ = g.nodes.find((L) => L.id === `${s}-start`), b = `${s}-template-table`;
    g.nodes.push({
      id: b,
      type: "template-table",
      position: { x: co, y: ((_ == null ? void 0 : _.position.y) ?? 0) + aa + ea },
      data: {
        templateKeyword: e.template,
        templateRows: e.templateRows,
        section: "testcase",
        sectionIndex: a
      }
    });
    const k = `${s}-end`, V = g.edges.findIndex((L) => L.source === `${s}-start` && L.target === k);
    V >= 0 && g.edges.splice(V, 1), g.edges.push({ id: `${s}-e-start-table`, source: `${s}-start`, target: b }), g.edges.push({ id: `${s}-e-table-end`, source: b, target: k });
  }
  return g;
}
function Oc(e, a, r = null, c = null) {
  const g = Ga(e.steps, e.name, `kw${a}`, "keyword", a, r, c);
  return Ya(g.nodes, g.edges, `kw${a}`, e, "keyword", a, xc), g;
}
function Ya(e, a, r, c, g, s, _) {
  const b = e.find((L) => L.id === `${r}-start`);
  if (!b) return;
  let k = 0;
  const V = 96;
  for (const L of _) {
    if (!Kc(c, L)) continue;
    const T = Bc(c, L), B = `${r}-${L}`;
    e.push({
      id: B,
      type: "setting-meta",
      position: { x: co - 280, y: b.position.y + k * V },
      data: {
        kind: L,
        label: Uc[L],
        text: T,
        section: g,
        sectionIndex: s
      },
      selectable: !0,
      draggable: !1
    }), a.push({
      id: `${r}-${L}-edge`,
      source: B,
      target: `${r}-start`,
      // Connect side note's RIGHT handle → Start's LEFT handle so the
      // dashed edge runs horizontally between them rather than the
      // default Bottom→Top route (which crossed the whole canvas).
      sourceHandle: "right",
      targetHandle: "left",
      type: "default",
      style: { strokeDasharray: "6 4", stroke: "#9AA5BF", strokeWidth: 1.5 },
      animated: !1
    }), k++;
  }
}
function Ic(e, a = null, r = null) {
  const c = [], g = [];
  for (let s = 0; s < e.testCases.length; s++) {
    const _ = s === 0 ? a : null, { nodes: b, edges: k } = Hc(e.testCases[s], s, _, r), V = s * 500;
    for (const L of b)
      L.position.x += V, c.push(L);
    g.push(...k);
  }
  return { nodes: c, edges: g };
}
function Wc(e, a = null) {
  const r = [], c = [];
  for (let g = 0; g < e.keywords.length; g++) {
    const { nodes: s, edges: _ } = Oc(e.keywords[g], g, null, a), b = g * 500;
    for (const k of s)
      k.position.x += b, r.push(k);
    c.push(..._);
  }
  return { nodes: r, edges: c };
}
function ot(e, a) {
  var g, s;
  const r = a.section === "testcase" ? (g = e.testCases[a.sectionIndex]) == null ? void 0 : g.steps : (s = e.keywords[a.sectionIndex]) == null ? void 0 : s.steps;
  if (!r) return;
  const c = r[a.stepIndex];
  c && Object.assign(c, a.step);
}
function Xa(e) {
  switch (e) {
    case "if":
    case "else_if":
    case "else":
    case "for":
    case "while":
    case "try":
    case "except":
    case "finally":
      return "control";
    case "assignment":
      return "assignment";
    case "comment":
      return "comment";
    case "return":
      return "return";
    case "break":
    case "continue":
      return "flow-control";
    default:
      return "keyword";
  }
}
function jc(e) {
  switch (e.type) {
    case "keyword":
      return e.keyword || "Keyword";
    case "assignment":
      return `${e.returnVars.join(", ")} = ${e.keyword}`;
    case "if":
      return `IF  ${e.condition}`;
    case "else_if":
      return `ELSE IF  ${e.condition}`;
    case "else":
      return "ELSE";
    case "for":
      return `FOR ${e.loopVar} ${e.loopFlavor} ${e.loopValues.join("  ")}`;
    case "while":
      return `WHILE  ${e.condition}`;
    case "try":
      return "TRY";
    case "except":
      return e.exceptPattern ? `EXCEPT  ${e.exceptPattern}` : "EXCEPT";
    case "finally":
      return "FINALLY";
    case "return":
      return `RETURN  ${e.args.join("  ")}`;
    case "break":
      return "BREAK";
    case "continue":
      return "CONTINUE";
    case "comment":
      return `# ${e.comment}`;
    case "var":
      return `VAR ${e.keyword} = ${e.args.join("  ")}`;
    default:
      return e.keyword || e.type;
  }
}
function qc(e, a) {
  if (e.type === "else" || e.type === "else_if") return "false";
  if ((a == null ? void 0 : a.type) === "if" || (a == null ? void 0 : a.type) === "else_if") return "true";
}
function ka(e) {
  return e ? e.split(`
`).length : 0;
}
function Yn(e) {
  return e !== void 0 && e !== "";
}
function zc(e, a, r, c) {
  if (a || r < 0 || r >= e.testCases.length) return null;
  const g = e.testCases[r];
  if (c < 0 || c >= g.steps.length) return null;
  let s = 0, _;
  for (const b of e.preambleLines)
    s += 1, _ = b;
  if (e.settings.length > 0) {
    s > 0 && Yn(_) && (s += 1, _ = ""), s += 1, _ = "*** Settings ***";
    for (const b of e.settings) {
      if (b.key === "#") {
        s += 1, _ = b.value;
        continue;
      }
      const k = b.value && b.value.includes(`
`) ? ka(b.value) : 1;
      s += k, _ = "set";
    }
  }
  if (e.variables.length > 0) {
    s > 0 && Yn(_) && (s += 1, _ = ""), s += 1, _ = "*** Variables ***";
    for (const b of e.variables)
      s += 1, _ = b.name === "#" ? b.value : b.name;
  }
  if (e.testCases.length > 0) {
    s > 0 && Yn(_) && (s += 1, _ = ""), s += 1, _ = "*** Test Cases ***";
    for (let b = 0; b < e.testCases.length; b++) {
      const k = e.testCases[b];
      if (s += 1, k.documentation && (s += ka(k.documentation)), k.tags.length > 0 && (s += 1), k.setup && (s += 1), k.teardown && (s += 1), k.timeout && (s += 1), k.template && (s += 1), b === r)
        return s + c + 1;
      s += k.steps.length, s += 1, _ = "";
    }
  }
  return null;
}
const Za = Object.freeze({
  Click: "Heal Click",
  "Fill Text": "Heal Fill Text",
  "Type Text": "Heal Type Text",
  Hover: "Heal Hover",
  "Press Keys": "Heal Press Keys",
  "Wait For Elements State": "Heal Wait For Elements State",
  "Upload File": "Heal Upload File",
  "Check Checkbox": "Heal Check Checkbox",
  "Uncheck Checkbox": "Heal Uncheck Checkbox",
  "Select Options By": "Heal Select Options By",
  "Get Text": "Heal Get Text",
  "Get Element Count": "Heal Get Element Count",
  "Drag And Drop": "Heal Drag And Drop"
}), Gc = Object.freeze(
  Object.fromEntries(
    Object.entries(Za).map(([e, a]) => [a, e])
  )
);
function qo(e) {
  const a = e.trim();
  return Za[a] ?? null;
}
function sa(e) {
  const a = e.trim();
  return Gc[a] ?? null;
}
function zo(e) {
  return sa(e) !== null;
}
function Yc(e) {
  return e.findIndex(
    (a) => a.key === "Library" && a.value === "RoboScopeHeal" && a.args.length === 0
  );
}
function Xc(e) {
  return e.some((a) => a.key === "Library" && a.value === "RoboScopeHeal");
}
function Qa(e) {
  if (Xc(e)) return e;
  const a = { key: "Library", value: "RoboScopeHeal", args: [] };
  return [...e, a];
}
function Ja(e, a) {
  if (a) return e;
  const r = Yc(e);
  return r === -1 ? e : [...e.slice(0, r), ...e.slice(r + 1)];
}
function es(e) {
  let a = 0;
  for (const r of e.testCases)
    for (const c of r.steps) zo(c.keyword) && (a += 1);
  for (const r of e.keywords)
    for (const c of r.steps) zo(c.keyword) && (a += 1);
  return a;
}
function ts(e) {
  return e.settings.some((a) => {
    if (a.key !== "Library") return !1;
    const r = a.value.trim();
    return /^(?:Browser|robotframework[-_]browser(?:[-_]batteries)?)$/i.test(r);
  });
}
function os(e) {
  return e.settings.some(
    (a) => a.key === "Library" && a.value.trim() === "RoboScopeHeal"
  );
}
function Zc(e) {
  let a = 0;
  for (const r of e.testCases)
    for (const c of r.steps) qo(c.keyword) !== null && (a += 1);
  for (const r of e.keywords)
    for (const c of r.steps) qo(c.keyword) !== null && (a += 1);
  return a;
}
function Ca(e, a) {
  if (a === "enable") {
    const r = qo(e.keyword);
    if (r !== null) return { ...e, keyword: r };
  } else {
    const r = sa(e.keyword);
    if (r !== null) return { ...e, keyword: r };
  }
  return e;
}
function Qc(e, a) {
  let r = 0;
  const c = e.testCases.map((b) => {
    let k = !1;
    const V = b.steps.map((L) => {
      const T = Ca(L, a);
      return T !== L && (k = !0, r += 1), T;
    });
    return k ? { ...b, steps: V } : b;
  }), g = e.keywords.map((b) => {
    let k = !1;
    const V = b.steps.map((L) => {
      const T = Ca(L, a);
      return T !== L && (k = !0, r += 1), T;
    });
    return k ? { ...b, steps: V } : b;
  }), s = c.some((b) => b.steps.some((k) => zo(k.keyword))) || g.some((b) => b.steps.some((k) => zo(k.keyword)));
  let _ = e.settings;
  return a === "enable" && s ? _ = Qa(_) : a === "disable" && (_ = Ja(_, s)), {
    form: {
      ...e,
      settings: _,
      testCases: c,
      keywords: g
    },
    changedKeywords: r
  };
}
const Jc = { class: "flow-editor" }, ed = { class: "flow-section-bar" }, td = { class: "flow-section-tabs" }, od = { class: "flow-item-tabs" }, nd = ["onClick"], ad = ["title"], sd = ["onClick"], ld = ["title"], rd = ["title"], id = { "aria-hidden": "true" }, ud = ["title"], cd = { "aria-hidden": "true" }, dd = ["title"], pd = { "aria-hidden": "true" }, fd = {
  key: 0,
  class: "flow-libraries"
}, md = {
  key: 0,
  class: "flow-libraries__empty"
}, gd = {
  key: 1,
  class: "flow-libraries__group",
  "data-testid": "flow-resources-group"
}, vd = { class: "flow-libraries__group-label" }, hd = { class: "flow-libraries__chips" }, yd = ["title"], bd = ["title", "onClick"], wd = {
  key: 2,
  class: "flow-libraries__group",
  "data-testid": "flow-libraries-group"
}, _d = { class: "flow-libraries__group-label" }, kd = { class: "flow-libraries__chips" }, Cd = ["title"], $d = ["title", "onClick"], Td = { class: "flow-libraries__input-row" }, Ed = ["placeholder", "onKeydown"], Sd = ["disabled"], Ld = {
  key: 3,
  class: "flow-libraries__suggestions"
}, Vd = ["onClick"], Ad = {
  key: 1,
  class: "flow-libraries",
  "data-testid": "flow-variables-panel"
}, Nd = { class: "flow-vars__rows" }, Rd = {
  key: 0,
  class: "flow-libraries__empty"
}, Fd = ["value", "onChange"], Pd = ["value", "onChange"], Dd = ["title", "onClick"], Md = { class: "flow-libraries__input-row" }, xd = ["placeholder", "onKeydown"], Ud = ["placeholder", "onKeydown"], Bd = ["disabled"], Kd = {
  key: 0,
  class: "flow-vars__envrefs",
  "data-testid": "flow-env-vars"
}, Hd = ["title"], Od = ["data-state", "title"], Id = { class: "flow-libraries__chip-name" }, Wd = {
  key: 2,
  class: "flow-libraries",
  "data-testid": "flow-suite-settings-panel"
}, jd = { class: "flow-vars__rows" }, qd = {
  key: 0,
  class: "flow-libraries__empty"
}, zd = { class: "flow-vars__name flow-vars__name--readonly" }, Gd = ["value", "onChange"], Yd = ["title", "onClick"], Xd = {
  key: 0,
  class: "flow-libraries__suggestions"
}, Zd = ["onClick"], Qd = {
  key: 3,
  class: "flow-empty"
}, Jd = { class: "flow-empty__actions" }, ep = {
  key: 4,
  class: "flow-canvas-wrapper"
}, tp = { class: "flow-node-doc-meta__body" }, op = { class: "flow-node-doc-meta__label" }, np = {
  key: 0,
  class: "flow-node-doc-meta__text"
}, ap = {
  key: 1,
  class: "flow-node-doc-meta__text flow-node-doc-meta__text--empty"
}, sp = ["onDragstart"], lp = {
  class: "flow-node-template",
  "data-testid": "flow-template-table"
}, rp = { class: "flow-node-template__head" }, ip = { class: "flow-node-template__kw" }, up = { class: "flow-node-template__table" }, cp = ["value", "onChange"], dp = ["title", "onClick"], pp = { class: "flow-node-template__actions" }, fp = ["onClick"], mp = ["onClick"], gp = ["onDragstart"], vp = ["onDragstart"], hp = {
  key: 0,
  class: "flow-node-return-values"
}, yp = {
  key: 1,
  class: "flow-node-return-empty"
}, bp = ["title"], wp = { class: "flow-detail-header" }, _p = { class: "flow-detail-row flow-settings-add-row" }, kp = ["title", "data-testid", "onClick"], Cp = {
  key: 0,
  class: "flow-detail-hint"
}, $p = ["title"], Tp = { class: "flow-detail-header" }, Ep = { class: "flow-detail-actions" }, Sp = ["title"], Lp = { class: "flow-detail-row" }, Vp = ["placeholder"], Ap = ["placeholder"], Np = { class: "flow-detail-hint" }, Rp = ["title"], Fp = { class: "flow-detail-header" }, Pp = { class: "flow-detail-actions" }, Dp = ["title"], Mp = {
  key: 0,
  class: "flow-debug-action-row"
}, xp = ["title"], Up = {
  key: 0,
  class: "flow-debug-action-error",
  role: "alert"
}, Bp = {
  key: 1,
  class: "flow-detail-row"
}, Kp = {
  key: 2,
  class: "flow-detail-row flow-detail-row--heal"
}, Hp = ["for"], Op = { class: "flow-heal-toggle" }, Ip = ["id", "checked"], Wp = { class: "flow-heal-toggle__hint" }, jp = {
  key: 3,
  class: "flow-detail-row"
}, qp = ["title"], zp = ["title"], Gp = { class: "flow-arg-type-icon" }, Yp = { class: "flow-arg-type-label" }, Xp = {
  key: 0,
  class: "flow-arg-type-opt"
}, Zp = {
  key: 1,
  class: "flow-selector-picker-wrap"
}, Qp = {
  key: 0,
  class: "flow-selector-custom-hint"
}, Jp = ["checked", "onChange", "data-testid"], ef = ["onUpdate:modelValue", "data-testid"], tf = ["value"], of = {
  value: "",
  disabled: "",
  hidden: ""
}, nf = ["value"], af = ["onUpdate:modelValue", "step", "placeholder"], sf = ["onUpdate:modelValue", "placeholder", "title"], lf = ["onUpdate:modelValue", "placeholder"], rf = ["title", "data-testid", "onClick"], uf = ["onClick"], cf = {
  key: 0,
  class: "flow-add-arg-caret"
}, df = {
  key: 0,
  class: "flow-add-arg-empty"
}, pf = ["data-testid", "onClick"], ff = { class: "flow-add-arg-option-name" }, mf = {
  key: 0,
  class: "flow-add-arg-option-default"
}, gf = {
  key: 1,
  class: "flow-add-arg-option-named"
}, vf = {
  class: "flow-add-arg-custom",
  "data-testid": "add-arg-custom"
}, hf = { class: "flow-add-arg-custom-label" }, yf = { class: "flow-add-arg-custom-row" }, bf = ["placeholder", "onKeydown"], wf = ["disabled"], _f = {
  key: 4,
  class: "flow-detail-row"
}, kf = ["onUpdate:modelValue"], Cf = ["onClick"], $f = {
  key: 5,
  class: "flow-detail-row"
}, Tf = ["onUpdate:modelValue", "placeholder", "data-testid"], Ef = ["onClick"], Sf = {
  key: 6,
  class: "flow-detail-row"
}, Lf = {
  key: 7,
  class: "flow-detail-row"
}, Vf = {
  key: 8,
  class: "flow-detail-row"
}, Af = {
  key: 9,
  class: "flow-detail-row"
}, Nf = ["onUpdate:modelValue"], Rf = ["onClick"], Ff = {
  key: 10,
  class: "flow-detail-row"
}, Pf = {
  key: 11,
  class: "flow-detail-row"
}, Df = {
  key: 12,
  class: "flow-detail-row"
}, Mf = {
  key: 13,
  class: "flow-detail-row"
}, xf = {
  key: 14,
  class: "flow-detail-row"
}, Uf = { key: 0 }, Bf = {
  key: 0,
  class: "flow-debug-overlay",
  "data-testid": "flow-debug-overlay",
  role: "dialog",
  "aria-modal": "true"
}, Kf = { class: "flow-debug-overlay__inner" }, Hf = 240, Of = 720, If = /* @__PURE__ */ qe({
  __name: "FlowEditor",
  props: {
    form: {},
    sidecar: {},
    dirty: { type: Boolean }
  },
  emits: ["update:step", "update:sidecar", "add-test-case", "add-keyword", "libraries-changed"],
  setup(e, { emit: a }) {
    const r = e, c = Da(), g = a, { t: s } = ft(), _ = Jn(), b = N([]), k = N([]), { fitView: V, project: L } = fs(), T = N("testcases"), B = N(0), I = O(
      () => r.form.testCases.map((t, n) => t.name || `Test Case ${n + 1}`)
    ), W = O(
      () => r.form.keywords.map((t, n) => t.name || `Keyword ${n + 1}`)
    ), re = O(() => r.form.testCases.length > 0), x = O(() => r.form.keywords.length > 0);
    O(() => re.value || x.value);
    const Z = O(() => T.value === "testcases" && !re.value ? "testcases" : T.value === "keywords" && !x.value ? "keywords" : null), Q = N(!1), G = N(""), R = O(() => {
      const t = [];
      return r.form.settings.forEach((n, v) => {
        const F = n.key.toLowerCase();
        F === "library" ? t.push({ idx: v, kind: "library", value: n.value }) : F === "resource" && t.push({ idx: v, kind: "resource", value: n.value });
      }), t;
    }), P = O(
      () => R.value.filter((t) => t.kind === "resource")
    ), K = O(
      () => R.value.filter((t) => t.kind === "library")
    ), j = O(() => {
      const t = /* @__PURE__ */ new Set(["builtin"]);
      for (const n of R.value)
        n.kind === "library" && t.add(n.value.toLowerCase());
      return t;
    }), D = [
      "BuiltIn",
      "Collections",
      "String",
      "OperatingSystem",
      "Process",
      "DateTime",
      "XML",
      "Dialogs",
      "Screenshot",
      "Telnet",
      "Remote",
      "SeleniumLibrary",
      "Browser",
      "RequestsLibrary",
      "DatabaseLibrary",
      "SSHLibrary",
      "FTPLibrary",
      "ExcelLibrary",
      "JSONLibrary",
      "AppiumLibrary",
      "SwingLibrary",
      "SikuliLibrary",
      "ImapLibrary",
      "ArchiveLibrary",
      "CryptoLibrary",
      "RESTinstance"
    ], se = O(() => {
      const t = G.value.trim().toLowerCase(), n = new Set(
        R.value.filter((v) => v.kind === "library").map((v) => v.value.toLowerCase())
      );
      return D.filter((v) => n.has(v.toLowerCase()) ? !1 : t ? v.toLowerCase().includes(t) : !0).slice(0, 8);
    }), oe = /* @__PURE__ */ new Set([
      "collections",
      "string",
      "datetime",
      "operatingsystem",
      "process",
      "xml",
      "dialogs",
      "screenshot",
      "telnet",
      "remote"
    ]);
    function be(t) {
      const n = t.trim();
      if (!n) return !1;
      const v = Zl(n), F = v ? "Resource" : "Library";
      if (r.form.settings.find(
        (S) => S.key.toLowerCase() === F.toLowerCase() && S.value.trim().toLowerCase() === n.toLowerCase()
      )) return !1;
      r.form.settings.push({ key: F, value: n, args: [] }), G.value = "";
      const C = v || oe.has(n.toLowerCase());
      return g("libraries-changed", C ? void 0 : n), v ? "resource" : "library";
    }
    function J(t, n) {
      _.success(
        s(t === "resource" ? "flowEditor.importAdded.resourceTitle" : "flowEditor.importAdded.libraryTitle"),
        s("flowEditor.importAdded.message", { name: n })
      );
    }
    function H() {
      be(G.value);
    }
    function ue(t) {
      t < 0 || t >= r.form.settings.length || (r.form.settings.splice(t, 1), g("libraries-changed"));
    }
    const ce = N(!1), ge = N(""), Le = N(""), $e = O(() => {
      const t = [];
      return r.form.variables.forEach((n, v) => {
        n.name !== "#" && t.push({ idx: v, name: n.name, value: n.value });
      }), t;
    });
    function Be(t) {
      const n = t.trim();
      return !n || /^[$@&]\{.*\}$/.test(n) ? n : `\${${n}}`;
    }
    function Ge() {
      at = !0;
    }
    function ie() {
      const t = Be(ge.value);
      if (!t) return;
      r.form.variables.some(
        (v) => v.name.toLowerCase() === t.toLowerCase()
      ) || (Ge(), r.form.variables.push({ name: t, value: Le.value.trim() })), ge.value = "", Le.value = "";
    }
    function Qe(t, n) {
      if (t < 0 || t >= r.form.variables.length) return;
      const v = Be(n);
      v && (Ge(), r.form.variables[t].name = v);
    }
    function mt(t, n) {
      t < 0 || t >= r.form.variables.length || (Ge(), r.form.variables[t].value = n);
    }
    function ut(t) {
      t < 0 || t >= r.form.variables.length || (Ge(), r.form.variables.splice(t, 1));
    }
    function Je(t) {
      const n = r.form.testCases[t];
      return n ? (n.templateRows || (n.templateRows = []), n.templateRows) : [];
    }
    function gt(t, n, v, F) {
      const X = Je(t);
      if (!(n < 0 || n >= X.length)) {
        for (Ge(); X[n].length <= v; ) X[n].push("");
        X[n][v] = F;
      }
    }
    function Ye(t) {
      const n = Je(t), v = n.length ? Math.max(...n.map((F) => F.length)) : 1;
      n.push(Array.from({ length: v }, () => "")), Ne(`tc${t}-template-table`);
    }
    function Ke(t, n) {
      const v = Je(t);
      n >= 0 && n < v.length && v.splice(n, 1), Ne(`tc${t}-template-table`);
    }
    function ee(t) {
      for (const n of Je(t)) n.push("");
      Ne(`tc${t}-template-table`);
    }
    const de = O(() => {
      const t = T.value === "testcases" ? r.form.testCases[B.value] : r.form.keywords[B.value];
      if (!t) return [];
      const n = [];
      for (const v of t.steps)
        n.push(...v.args, v.condition, ...v.loopValues);
      return Fa(n);
    }), ne = Yo(), fe = na(), Ae = O(() => c.scopeId == null ? null : ne.resolveEnvironmentId(c.scopeId)), De = O(() => {
      var t;
      return ((t = fe.environments.find((n) => n.id === Ae.value)) == null ? void 0 : t.name) ?? "";
    }), vt = O(() => {
      const t = Ae.value === null ? void 0 : fe.variables[Ae.value];
      return t ? new Set(t.map((n) => n.key)) : null;
    });
    Se([() => c.scopeId, Ae], ([, t]) => {
      t !== null && fe.fetchVariables(t).catch(() => {
      });
    }, { immediate: !0 }), us("envVarKeys", vt);
    const Zt = O(() => Pa(de.value, vt.value));
    function Tt(t, n) {
      const v = De.value;
      if (n === "defined") return s("flowEditor.envVarDefined", { env: v });
      if (n === "default") return s("flowEditor.envVarDefaultUsed", { env: v });
      if (n === "missing") return s("flowEditor.envVarMissing", { env: v, name: t });
    }
    const nt = N(!1), Qt = [
      "Documentation",
      "Metadata",
      "Suite Setup",
      "Suite Teardown",
      "Test Setup",
      "Test Teardown",
      "Force Tags",
      "Default Tags",
      "Test Timeout"
    ], Et = O(() => {
      const t = [];
      return r.form.settings.forEach((n, v) => {
        const F = n.key.toLowerCase();
        F === "library" || F === "resource" || F === "variables" || F === "#" || t.push({ idx: v, key: n.key, value: n.value });
      }), t;
    }), Ut = O(() => {
      const t = new Set(
        r.form.settings.map((n) => n.key.toLowerCase())
      );
      return Qt.filter((n) => !t.has(n.toLowerCase()));
    });
    function Jt(t) {
      r.form.settings.some((n) => n.key.toLowerCase() === t.toLowerCase()) || (Ge(), r.form.settings.push({ key: t, value: "", args: [] }));
    }
    function St(t, n) {
      t < 0 || t >= r.form.settings.length || (Ge(), r.form.settings[t].value = n);
    }
    function Bt(t) {
      t < 0 || t >= r.form.settings.length || (Ge(), r.form.settings.splice(t, 1));
    }
    async function ht() {
      g("add-test-case"), await je(), r.form.testCases.length > 0 && (T.value = "testcases", B.value = r.form.testCases.length - 1);
    }
    async function Lt() {
      g("add-keyword"), await je(), r.form.keywords.length > 0 && (T.value = "keywords", B.value = r.form.keywords.length - 1);
    }
    const A = N(null), w = O(() => !A.value || A.value.type === "setting-meta" || A.value.type === "start" || A.value.type === "template-table" ? null : A.value.data), M = O(
      () => {
        var t;
        return ((t = A.value) == null ? void 0 : t.type) === "start";
      }
    ), q = [
      "documentation",
      "tags",
      "setup",
      "teardown",
      "template",
      "timeout"
    ], me = [
      "documentation",
      "arguments",
      "tags",
      "setup",
      "teardown",
      "timeout"
    ], Te = {
      documentation: "[Documentation]",
      tags: "[Tags]",
      arguments: "[Arguments]",
      setup: "[Setup]",
      teardown: "[Teardown]",
      template: "[Template]",
      timeout: "[Timeout]"
    };
    function et() {
      return T.value === "testcases" ? r.form.testCases[B.value] : r.form.keywords[B.value];
    }
    function Kt(t) {
      const n = et();
      return n ? t === "tags" ? n.tags.length === 0 : t === "arguments" ? "arguments" in n ? n.arguments.length === 0 : !1 : t === "template" ? "template" in n ? !n.template : !1 : !n[t] : !1;
    }
    const Xo = O(
      () => T.value === "testcases" ? q : me
    ), Fe = O(() => {
      if (!A.value || A.value.type !== "setting-meta") return null;
      const t = A.value.data;
      return { kind: t.kind, section: t.section, sectionIndex: t.sectionIndex };
    });
    function Ht() {
      if (!Fe.value) return;
      const { section: t, sectionIndex: n } = Fe.value;
      return t === "testcase" ? r.form.testCases[n] : r.form.keywords[n];
    }
    const Vt = N("");
    function At() {
      const t = Ht();
      if (!t || !Fe.value) return "";
      const n = Fe.value.kind;
      return n === "tags" ? t.tags.join(", ") : n === "arguments" ? "arguments" in t ? t.arguments.join(", ") : "" : n === "template" ? "template" in t ? t.template : "" : t[n];
    }
    Se(
      () => Fe.value && [
        Fe.value.section,
        Fe.value.sectionIndex,
        Fe.value.kind
      ].join("|"),
      (t) => {
        t && (Vt.value = At());
      },
      { immediate: !0 }
    );
    function po() {
      const t = Ht();
      if (!t || !Fe.value) return;
      const n = Vt.value, v = Fe.value.kind;
      v === "tags" ? t.tags = Zo(n) : v === "arguments" && "arguments" in t ? t.arguments = Zo(n) : v === "template" && "template" in t ? t.template = n : (v === "documentation" || v === "setup" || v === "teardown" || v === "timeout") && (t[v] = n), Ne();
    }
    function Zo(t) {
      return t.split(/,\s*|\n+/).map((n) => n.trim()).filter((n) => n.length > 0);
    }
    function $o() {
      if (!Fe.value) return;
      const t = Ht();
      if (!t) return;
      const n = Fe.value.kind;
      n === "tags" ? t.tags = [] : n === "arguments" && "arguments" in t ? t.arguments = [] : n === "template" && "template" in t ? t.template = "" : (n === "documentation" || n === "setup" || n === "teardown" || n === "timeout") && (t[n] = ""), A.value = null, Ne();
    }
    function Sn(t) {
      const n = et();
      n && (t === "tags" ? n.tags = [""] : t === "arguments" && "arguments" in n ? n.arguments = [""] : t === "template" && "template" in n ? n.template = " " : (t === "documentation" || t === "setup" || t === "teardown" || t === "timeout") && (n[t] = " "), Ne(), je(() => {
        const v = T.value === "testcases" ? `tc${B.value}` : `kw${B.value}`, F = b.value.find((X) => X.id === `${v}-${t}`);
        F && (A.value = F);
      }));
    }
    const eo = N(300), ct = N(!1);
    function to(t) {
      t.preventDefault(), ct.value = !0;
      const n = t.clientX, v = eo.value;
      function F(C) {
        const S = n - C.clientX, he = Math.min(Of, Math.max(Hf, v + S));
        eo.value = he;
      }
      function X() {
        ct.value = !1, window.removeEventListener("pointermove", F), window.removeEventListener("pointerup", X), window.removeEventListener("pointercancel", X);
      }
      window.addEventListener("pointermove", F), window.addEventListener("pointerup", X), window.addEventListener("pointercancel", X);
    }
    const { argsByName: Ot, getKeywordInfo: To, getParsedArgs: Qo } = En();
    function Nt(t) {
      if (t.type !== "keyword" && t.type !== "assignment" || t.args.length > 0) return;
      const n = Qo(t.keyword);
      if (n)
        for (const v of n)
          if (v.kind === "positional") t.args.push("");
          else break;
    }
    const oo = O(() => {
      const t = /* @__PURE__ */ new Map(), n = (v) => {
        for (const F of v) {
          if (F.type !== "keyword" && F.type !== "assignment" || !F.keyword) continue;
          const X = To(F.keyword), C = X == null ? void 0 : X.library;
          C && t.set(C, (t.get(C) ?? 0) + 1);
        }
      };
      for (const v of r.form.testCases) n(v.steps);
      for (const v of r.form.keywords) n(v.steps);
      return t;
    }), Ln = O(() => R.value.length), Vn = O(() => {
      let t = 0;
      for (const n of r.form.testCases) t += n.steps.length;
      for (const n of r.form.keywords) t += n.steps.length;
      return t;
    });
    function yt() {
      const t = r.sidecar ?? null, n = Ot.value;
      if (T.value === "testcases") {
        const v = Ic(r.form, t, n);
        b.value = v.nodes, k.value = v.edges;
      } else {
        const v = Wc(r.form, n);
        b.value = v.nodes, k.value = v.edges;
      }
    }
    const Eo = O(() => {
      const t = T.value === "testcases" ? `tc${B.value}-` : `kw${B.value}-`;
      return b.value.filter((n) => n.id.startsWith(t));
    }), An = O(() => {
      const t = Eo.value;
      if (zt.value === null) return t;
      const n = io(zt.value), v = {
        id: "__drop-indicator__",
        type: "drop-indicator",
        position: { x: co - 50, y: n - 2 },
        data: {},
        draggable: !1,
        selectable: !1
      };
      return [...t, v];
    }), Rt = O(() => {
      const t = T.value === "testcases" ? `tc${B.value}-` : `kw${B.value}-`;
      return k.value.filter((n) => n.id.startsWith(t));
    });
    let at = !1;
    Se([() => r.form, T], () => {
      const t = at;
      at = !1, t || (B.value = 0, A.value = null), yt(), t || je(() => {
        V({ padding: 0.3 }), setTimeout(() => V({ padding: 0.3 }), 100);
      });
    }, { deep: !0 }), Se(() => r.sidecar, () => {
      yt();
    }), Se(Ot, () => {
      yt();
    }), Se(B, () => {
      A.value = null, je(() => {
        V({ padding: 0.3 }), setTimeout(() => V({ padding: 0.3 }), 100);
      });
    });
    function Ie(t) {
      if (t.key !== "Backspace" && t.key !== "Delete" || !A.value || !w.value) return;
      const n = t.target;
      if (n) {
        const v = n.tagName;
        if (v === "INPUT" || v === "TEXTAREA" || v === "SELECT" || n.isContentEditable) return;
      }
      t.preventDefault(), cn();
    }
    Go(() => {
      !re.value && x.value && (T.value = "keywords"), yt(), setTimeout(() => V({ padding: 0.3 }), 200), setTimeout(() => V({ padding: 0.3 }), 500), window.addEventListener("keydown", Ie);
    }), Io(() => {
      window.removeEventListener("keydown", Ie);
    });
    function Jo(t) {
      if (t.node.type === "end") {
        A.value = null;
        return;
      }
      A.value = t.node;
    }
    function So() {
      A.value = null;
    }
    function Ne(t) {
      var v;
      const n = t ?? ((v = A.value) == null ? void 0 : v.id);
      at = !0, yt(), n && je(() => {
        const F = b.value.find((X) => X.id === n);
        A.value = F || null;
      });
    }
    function Lo(t) {
      return `${T.value === "testcases" ? `tc${B.value}` : `kw${B.value}`}-step-${t}`;
    }
    function Me() {
      w.value && (ot(r.form, w.value), Ne(), g("update:step", w.value));
    }
    function Nn(t) {
      if (!w.value) return;
      const n = w.value.recording;
      if (!n) return;
      const v = n.selector_candidates[t];
      v && (ba(w.value.step, n) && !window.confirm(
        s("flowEditor.selector.replaceCustomConfirm", {
          current: w.value.step.args[0] ?? "",
          next: v.value
        })
      ) || Ec(w.value.step, n, t) && (ot(r.form, w.value), Ne(), g("update:step", w.value), r.sidecar && g("update:sidecar", r.sidecar)));
    }
    function en(t) {
      if (!w.value) return;
      const n = w.value.recording;
      if (!n) return;
      const v = n.selector_candidates[t.index];
      if (v) {
        if (v.value = t.value, v.strategy = t.strategy, v.quality_score = 50, v.verified_unique = !1, t.effective !== void 0 && (v.effective_override = t.effective === "" ? null : t.effective), t.index === n.active_candidate_index) {
          const F = ya(n, v);
          w.value.step.args.length === 0 ? w.value.step.args.push(F) : w.value.step.args[0] = F, ot(r.form, w.value);
        }
        Ne(), g("update:step", w.value), r.sidecar && g("update:sidecar", r.sidecar);
      }
    }
    function Rn(t) {
      if (!w.value) return;
      const n = w.value.recording;
      if (!n) return;
      const v = {
        strategy: t.strategy,
        value: t.value,
        quality_score: 50,
        verified_unique: !1,
        effective_override: t.effective !== void 0 && t.effective !== "" ? t.effective : null
      };
      n.selector_candidates.push(v);
      const F = n.selector_candidates.length - 1;
      n.active_candidate_index = F;
      const X = ya(n, v);
      w.value.step.args.length === 0 ? w.value.step.args.push(X) : w.value.step.args[0] = X, ot(r.form, w.value), Ne(), g("update:step", w.value), r.sidecar && g("update:sidecar", r.sidecar);
    }
    const tn = O(() => {
      var n;
      const t = (n = w.value) == null ? void 0 : n.recording;
      return !!t && t.selector_candidates.length > 0;
    }), on = O(() => {
      const t = w.value;
      return t != null && t.recording ? ba(t.step, t.recording) : !1;
    });
    function fo(t) {
      var X;
      const n = w.value;
      if (!n) return null;
      const v = n.step.args[t] ?? "", F = /^([A-Za-z_][\w]*)\s*=/.exec(v);
      if (F && n.argSpecs) {
        const C = n.argSpecs.find((S) => S.name === F[1]);
        if (C) return { spec: C, viaName: !0 };
      }
      return (X = n.argSpecs) != null && X[t] ? { spec: n.argSpecs[t], viaName: !1 } : null;
    }
    function Fn(t) {
      var v;
      const n = fo(t);
      return n != null && n.viaName ? n.spec.name : Na(((v = w.value) == null ? void 0 : v.argSpecs) ?? null, t, s);
    }
    function Pn(t) {
      w.value && (w.value.step.keyword = t);
    }
    const Vo = O(() => {
      const t = w.value;
      if (!t || t.stepType !== "keyword" && t.stepType !== "assignment") return "hidden";
      const n = (t.step.keyword ?? "").trim(), v = zo(n), F = qo(n) !== null;
      return !v && !F || !ts(r.form) && !os(r.form) ? "hidden" : v ? "on" : "off";
    });
    function mo(t) {
      const n = w.value;
      if (!n) return;
      const v = n.step.keyword.trim(), F = t ? qo(v) : sa(v);
      if (F === null) return;
      n.step.keyword = F, Me();
      const X = es(r.form) > 0, C = t ? Qa(r.form.settings) : Ja(r.form.settings, X);
      C !== r.form.settings && r.form.settings.splice(0, r.form.settings.length, ...C);
    }
    const st = N(!1), nn = O(() => {
      var t;
      return ((t = w.value) == null ? void 0 : t.step.keyword) ?? "";
    }), no = O(() => {
      const t = w.value;
      return !t || t.stepType !== "keyword" && t.stepType !== "assignment" ? !1 : (t.step.keyword ?? "").trim().length > 0;
    });
    function bt() {
      no.value && (st.value = !0);
    }
    function tt(t) {
      const n = fo(t);
      return (n == null ? void 0 : n.spec.defaultValue) != null && n.spec.defaultValue !== "" ? n.spec.defaultValue : s("flowEditor.argLabels.fallback", { n: t + 1 });
    }
    function Ue(t) {
      const n = fo(t);
      return wn((n == null ? void 0 : n.spec.type) ?? null);
    }
    function an(t) {
      const n = Ue(t);
      if (n.raw)
        return n.raw + (n.optional ? s("flowEditor.argTypes.optionalSuffix") : "");
    }
    const ao = N(/* @__PURE__ */ new Set());
    function Ao(t) {
      var n;
      return `${((n = A.value) == null ? void 0 : n.id) ?? "?"}#${t}`;
    }
    function go(t) {
      return ao.value.has(Ao(t));
    }
    function sn(t) {
      const n = Ao(t), v = new Set(ao.value);
      v.has(n) ? v.delete(n) : v.add(n), ao.value = v;
    }
    Se(A, () => {
      ao.value = /* @__PURE__ */ new Set();
    });
    function Ft(t) {
      var C;
      if (go(t)) return "text";
      const n = Ue(t).control;
      if (n === "text") return n;
      const v = ((C = w.value) == null ? void 0 : C.step.args[t]) ?? "", F = /^([A-Za-z_][\w]*)\s*=(.*)$/.exec(v), X = F ? F[2] : v;
      return Ks(X) ? "text" : n;
    }
    const ln = /^([A-Za-z_][\w]*)\s*=(.*)$/;
    function rn(t, n) {
      if (!w.value) return;
      const v = n.target.checked, F = w.value.step.args[t] ?? "", X = ln.exec(F);
      w.value.step.args[t] = X ? `${X[1]}=${ua(v)}` : ua(v), Me();
    }
    function Dn(t) {
      var C, S;
      const n = ((C = w.value) == null ? void 0 : C.step.args[t]) ?? "", v = ln.exec(n), F = v ? v[2] : n;
      if (F) return ia(F);
      const X = (S = fo(t)) == null ? void 0 : S.spec.defaultValue;
      return ia(X ?? "");
    }
    const lt = N(!1), Pt = N(""), No = O(() => {
      const t = w.value;
      if (!(t != null && t.argSpecs)) return [];
      const n = t.step.args, v = new Set(t.argSpecs.map((C) => C.name)), F = /* @__PURE__ */ new Set();
      for (let C = 0; C < n.length; C++) {
        const S = n[C], he = /^([A-Za-z_][\w]*)\s*=/.exec(S);
        if (he && v.has(he[1]))
          F.add(he[1]);
        else {
          const Pe = t.argSpecs[C];
          Pe && (Pe.kind === "positional" || Pe.kind === "optional") && F.add(Pe.name);
        }
      }
      const X = [];
      for (let C = 0; C < t.argSpecs.length; C++) {
        const S = t.argSpecs[C];
        S.kind !== "positional" && S.kind !== "optional" || !S.name || F.has(S.name) || X.push({
          name: S.name,
          defaultValue: S.defaultValue,
          isNextPositional: C === n.length
        });
      }
      return X;
    }), xe = N(null), It = N(null), dt = N({
      top: "0px",
      left: "0px",
      minWidth: "200px"
    });
    function Wt() {
      var S;
      const t = xe.value;
      if (!t) return;
      const n = t.getBoundingClientRect(), v = Math.max(n.width, 200), F = Math.max(8, window.innerWidth - v - 8), X = ((S = It.value) == null ? void 0 : S.offsetHeight) || 240;
      let C = n.bottom + 4;
      if (C + X + 8 > window.innerHeight) {
        const he = n.top - X - 4;
        C = he >= 8 ? he : Math.max(8, window.innerHeight - X - 8);
      }
      dt.value = {
        top: `${C}px`,
        left: `${Math.min(n.left, F)}px`,
        minWidth: `${v}px`
      };
    }
    function Mn() {
      lt.value = !lt.value, lt.value && je(Wt);
    }
    function wt(t) {
      if (!w.value) return;
      let n;
      t.isNextPositional ? n = "" : t.defaultValue != null && t.defaultValue !== "" ? n = `${t.name}=${t.defaultValue}` : n = `${t.name}=`, w.value.step.args.push(n), ot(r.form, w.value), Ne(), lt.value = !1;
    }
    function Dt() {
      if (!w.value) return;
      const t = Pt.value.trim();
      t && (w.value.step.args.push(t), ot(r.form, w.value), Ne(), Pt.value = "", lt.value = !1);
    }
    let vo = !1;
    function so(t) {
      const n = t.target;
      n instanceof Element && (n.closest(".flow-add-arg-wrap, .flow-add-arg-popover") || (lt.value = !1));
    }
    function _t() {
      lt.value && Wt();
    }
    function ho() {
      vo || (document.addEventListener("click", so), window.addEventListener("scroll", _t, !0), window.addEventListener("resize", _t), vo = !0);
    }
    function lo() {
      vo && (document.removeEventListener("click", so), window.removeEventListener("scroll", _t, !0), window.removeEventListener("resize", _t), vo = !1);
    }
    Se(lt, (t) => {
      t ? ho() : (lo(), Pt.value = "");
    }), Io(lo);
    function Ve(t) {
      w.value && (w.value.step.args.splice(t, 1), ot(r.form, w.value), Ne());
    }
    function jt() {
      if (!w.value) return;
      const t = w.value.step;
      t.returnVars.push("${var}"), t.type === "keyword" && (t.type = "assignment"), ot(r.form, w.value), Ne();
    }
    function rt(t) {
      if (!w.value) return;
      const n = w.value.step;
      n.returnVars.splice(t, 1), n.returnVars.length === 0 && n.type === "assignment" && (n.type = "keyword"), ot(r.form, w.value), Ne();
    }
    function Ro() {
      w.value && (w.value.step.loopValues.push(""), ot(r.form, w.value), Ne());
    }
    function xn(t) {
      w.value && (w.value.step.loopValues.splice(t, 1), ot(r.form, w.value), Ne());
    }
    function yo() {
      w.value && (w.value.step.args.push(""), ot(r.form, w.value), Ne());
    }
    function un(t) {
      w.value && (w.value.step.args.splice(t, 1), ot(r.form, w.value), Ne());
    }
    function ke(t) {
      return {
        type: t,
        keyword: "",
        args: [],
        returnVars: [],
        condition: "",
        loopVar: "",
        loopFlavor: "",
        loopValues: [],
        exceptPattern: "",
        exceptVar: "",
        varScope: "",
        comment: ""
      };
    }
    function Fo(t) {
      return t === "try" ? [ke("except"), ke("end")] : t === "if" || t === "for" || t === "while" ? [ke("end")] : [];
    }
    function Un(t, n) {
      var X, C;
      if (n) {
        const S = be(n);
        S && J(S, n);
      }
      Nt(t);
      const v = T.value === "testcases" ? (X = r.form.testCases[B.value]) == null ? void 0 : X.steps : (C = r.form.keywords[B.value]) == null ? void 0 : C.steps;
      if (!v) return;
      const F = w.value ? w.value.stepIndex + 1 : v.length;
      v.splice(F, 0, t, ...Fo(t.type)), Ne(Lo(F));
    }
    function qt() {
      var t, n;
      return T.value === "testcases" ? ((t = r.form.testCases[B.value]) == null ? void 0 : t.steps) ?? null : ((n = r.form.keywords[B.value]) == null ? void 0 : n.steps) ?? null;
    }
    function ro() {
      if (!w.value) return;
      const t = qt();
      if (!t) return;
      const n = w.value.stepIndex;
      if (n <= 0) return;
      const v = t[n];
      t[n] = t[n - 1], t[n - 1] = v, w.value.stepIndex = n - 1, Ne(Lo(n - 1)), g("update:step", w.value);
    }
    function Bn() {
      if (!w.value) return;
      const t = qt();
      if (!t) return;
      const n = w.value.stepIndex;
      if (n >= t.length - 1) return;
      const v = t[n];
      t[n] = t[n + 1], t[n + 1] = v, w.value.stepIndex = n + 1, Ne(Lo(n + 1)), g("update:step", w.value);
    }
    function cn() {
      if (!w.value) return;
      const t = qt();
      t && (t.splice(w.value.stepIndex, 1), A.value = null, at = !0, yt());
    }
    const zt = N(null), Po = N(null);
    function dn() {
      return Eo.value.filter((t) => t.type !== "start" && t.type !== "end").sort((t, n) => t.position.y - n.position.y);
    }
    function Do(t) {
      const n = dn();
      if (n.length === 0) return 0;
      for (let v = 0; v < n.length; v++) {
        const F = n[v].position.y, X = n[v].data, C = F + Oo(X.step) / 2;
        if (t < C) return v;
      }
      return n.length;
    }
    function Mo(t) {
      const n = Po.value;
      if (!n) return 0;
      const v = n.getBoundingClientRect();
      return L({ x: t.clientX - v.left, y: t.clientY - v.top }).y;
    }
    function io(t) {
      const n = dn();
      if (n.length === 0) return Vc;
      if (t <= 0) return n[0].position.y - 25;
      if (t >= n.length) {
        const C = n[n.length - 1], S = C.data;
        return C.position.y + Oo(S.step) + 25;
      }
      const v = n[t - 1], F = n[t];
      return (v.position.y + Oo(v.data.step) + F.position.y) / 2;
    }
    function Kn(t) {
      var F, X;
      const n = (F = t.dataTransfer) == null ? void 0 : F.getData("application/rf-keyword"), v = (X = t.dataTransfer) == null ? void 0 : X.getData("application/rf-control");
      return n ? {
        type: "keyword",
        keyword: n,
        args: [],
        returnVars: [],
        condition: "",
        loopVar: "${item}",
        loopFlavor: "IN",
        loopValues: [],
        exceptPattern: "",
        exceptVar: "",
        varScope: "",
        comment: ""
      } : v && _c(v) ? {
        type: v,
        keyword: "",
        args: [],
        returnVars: [],
        condition: v === "if" || v === "while" ? "${condition}" : "",
        loopVar: v === "for" ? "${item}" : "",
        loopFlavor: v === "for" ? "IN" : "",
        loopValues: v === "for" ? ["@{list}"] : [],
        exceptPattern: "",
        exceptVar: "",
        varScope: "",
        comment: ""
      } : null;
    }
    function xo(t, n) {
      const v = qt();
      if (!v) return;
      let F = 0, X = 0;
      for (let C = 0; C < v.length; C++) {
        if (v[C].type === "end") {
          F++;
          continue;
        }
        if (X === n) break;
        X++, F++;
      }
      v.splice(F, 0, t, ...Fo(t.type)), at = !0, yt();
    }
    function Uo(t) {
      var S, he;
      t.preventDefault(), zt.value = null;
      const n = (S = t.dataTransfer) == null ? void 0 : S.getData("application/rf-reorder");
      if (n !== void 0 && n !== "") {
        const Pe = parseInt(n, 10), Ce = Mo(t), kt = Do(Ce);
        uo(Pe, kt);
        return;
      }
      const v = Kn(t);
      if (!v) return;
      const F = ((he = t.dataTransfer) == null ? void 0 : he.getData("application/rf-library")) || "";
      if (F) {
        const Pe = be(F);
        Pe && J(Pe, F);
      }
      Nt(v);
      const X = Mo(t), C = Do(X);
      xo(v, C);
    }
    function bo(t) {
      t.preventDefault(), t.dataTransfer.dropEffect = "copy";
      const n = Mo(t);
      zt.value = Do(n);
    }
    function pn() {
      zt.value = null;
    }
    function fn(t, n) {
      var C;
      const v = n.data, F = qt();
      if (!F) return;
      let X = 0;
      for (let S = 0; S < F.length; S++)
        if (F[S].type !== "end") {
          if (S === v.stepIndex) break;
          X++;
        }
      (C = t.dataTransfer) == null || C.setData("application/rf-reorder", String(X)), t.dataTransfer.effectAllowed = "move";
    }
    function uo(t, n) {
      const v = qt();
      if (!v) return;
      const F = [];
      for (let kt = 0; kt < v.length; kt++)
        v[kt].type !== "end" && F.push(kt);
      if (t < 0 || t >= F.length) return;
      const X = F[t];
      let C;
      if (n >= F.length ? C = v.length : (n <= t, C = F[n]), X === C || X + 1 === C) return;
      const S = v[X], Pe = ["if", "for", "while", "try"].includes(S.type) && X + 1 < v.length && v[X + 1].type === "end" ? v.splice(X, 2) : v.splice(X, 1);
      let Ce;
      C > X ? Ce = C - Pe.length : Ce = C, Ce = Math.max(0, Math.min(Ce, v.length)), v.splice(Ce, 0, ...Pe), at = !0, yt();
    }
    function Gt(t, n) {
      const v = Eo.value.find((F) => F.id === n);
      v && fn(t, v);
    }
    const Hn = yc(), Xe = ja(), Yt = O(() => {
      const t = w.value;
      return !(!t || t.section !== "testcase" || !Hn.hasMinRole("runner") || ![
        "keyword",
        "assignment",
        "return",
        "if",
        "else_if",
        "else",
        "for",
        "while",
        "try",
        "except",
        "finally"
      ].includes(t.step.type) || !c.filePath || c.scopeId == null);
    }), pt = N(!1), He = N(null), Mt = N(null);
    function On() {
      var X;
      const t = w.value;
      if (!t || t.section !== "testcase" || !c.filePath || c.scopeId == null) return null;
      const n = r.form.testCases[t.sectionIndex];
      if (!n) return null;
      const v = ((X = c.filePath) == null ? void 0 : X.toLowerCase().endsWith(".resource")) ?? !1, F = zc(r.form, v, t.sectionIndex, t.stepIndex);
      return F === null || F < 1 ? null : {
        file: c.filePath,
        testName: n.name,
        line: F,
        scopeId: c.scopeId
      };
    }
    async function In() {
      if (Mt.value = null, r.dirty) {
        pt.value = !0;
        return;
      }
      const t = On();
      if (!t) {
        Mt.value = s("flowEditor.debug.unresolvedLine");
        return;
      }
      const n = Xe.classifyStepClick(t.file, t.line);
      if (n === "same") {
        l.value = !0;
        return;
      }
      if (n === "different") {
        He.value = {
          fromLine: Xe.breakpointLine ?? 0,
          toLine: t.line,
          payload: t
        };
        return;
      }
      await f(t);
    }
    async function f(t) {
      try {
        await Xe.startFromStep({
          file: t.file,
          test_name: t.testName,
          line: t.line,
          scope_id: t.scopeId
        }) !== null && (l.value = !0);
      } catch (n) {
        Mt.value = ko(n, s("flowEditor.debug.startFailed"));
      }
    }
    async function y() {
      await Xe.installPrereqAndRetry() && Xe.isActive && (l.value = !0);
    }
    function d() {
      Xe.cancelPrereq();
    }
    async function h() {
      const t = He.value;
      if (t) {
        He.value = null;
        try {
          await Xe.stop();
        } catch {
        }
        await f(t.payload);
      }
    }
    const l = N(!1);
    function E() {
      l.value = !1;
    }
    return (t, n) => {
      var v, F, X;
      return i(), u("div", Jc, [
        o("div", ed, [
          o("div", td, [
            o("button", {
              class: te(["flow-section-tab", { active: T.value === "testcases" }]),
              onClick: n[0] || (n[0] = (C) => T.value = "testcases")
            }, m(p(s)("robotEditor.testCasesSection")) + " (" + m(r.form.testCases.length) + ") ", 3),
            o("button", {
              class: te(["flow-section-tab", { active: T.value === "keywords" }]),
              onClick: n[1] || (n[1] = (C) => T.value = "keywords")
            }, m(p(s)("robotEditor.keywordsSection")) + " (" + m(r.form.keywords.length) + ") ", 3)
          ]),
          o("div", od, [
            T.value === "testcases" ? (i(), u(U, { key: 0 }, [
              (i(!0), u(U, null, Y(I.value, (C, S) => (i(), u("button", {
                key: "tc" + S,
                class: te(["flow-item-tab", { active: B.value === S }]),
                onClick: (he) => B.value = S
              }, m(C), 11, nd))), 128)),
              o("button", {
                type: "button",
                class: "flow-item-tab flow-item-tab--add",
                title: p(s)("flowEditor.addTestCaseTitle"),
                "data-testid": "flow-add-test-case",
                onClick: ht
              }, "+ " + m(p(s)("flowEditor.addTestCase")), 9, ad)
            ], 64)) : (i(), u(U, { key: 1 }, [
              (i(!0), u(U, null, Y(W.value, (C, S) => (i(), u("button", {
                key: "kw" + S,
                class: te(["flow-item-tab", { active: B.value === S }]),
                onClick: (he) => B.value = S
              }, m(C), 11, sd))), 128)),
              o("button", {
                type: "button",
                class: "flow-item-tab flow-item-tab--add",
                title: p(s)("flowEditor.addKeywordTitle"),
                "data-testid": "flow-add-keyword",
                onClick: Lt
              }, "+ " + m(p(s)("flowEditor.addKeyword")), 9, ld)
            ], 64))
          ]),
          o("button", {
            type: "button",
            class: te(["flow-libs-toggle", { active: Q.value }]),
            title: p(s)("flowEditor.librariesTitle"),
            "data-testid": "flow-libraries-toggle",
            onClick: n[2] || (n[2] = (C) => Q.value = !Q.value)
          }, [
            we(" 📚 " + m(p(s)("flowEditor.libraries")) + " (" + m(R.value.length) + ") ", 1),
            o("span", id, m(Q.value ? "▴" : "▾"), 1)
          ], 10, rd),
          o("button", {
            type: "button",
            class: te(["flow-libs-toggle", { active: ce.value }]),
            title: p(s)("flowEditor.variablesTitle"),
            "data-testid": "flow-variables-toggle",
            onClick: n[3] || (n[3] = (C) => ce.value = !ce.value)
          }, [
            we(" 📦 " + m(p(s)("flowEditor.variables")) + " (" + m($e.value.length) + ") ", 1),
            o("span", cd, m(ce.value ? "▴" : "▾"), 1)
          ], 10, ud),
          o("button", {
            type: "button",
            class: te(["flow-libs-toggle", { active: nt.value }]),
            title: p(s)("flowEditor.suiteSettingsTitle"),
            "data-testid": "flow-suite-settings-toggle",
            onClick: n[4] || (n[4] = (C) => nt.value = !nt.value)
          }, [
            we(" ⚙️ " + m(p(s)("flowEditor.suiteSettings")) + " (" + m(Et.value.length) + ") ", 1),
            o("span", pd, m(nt.value ? "▴" : "▾"), 1)
          ], 10, dd)
        ]),
        Q.value ? (i(), u("div", fd, [
          R.value.length === 0 ? (i(), u("span", md, m(p(s)("flowEditor.librariesNone")), 1)) : $("", !0),
          P.value.length ? (i(), u("div", gd, [
            o("div", vd, m(p(s)("flowEditor.resourcePanelLabel")) + " (" + m(P.value.length) + ")", 1),
            o("div", hd, [
              (i(!0), u(U, null, Y(P.value, (C) => (i(), u("span", {
                key: C.idx,
                class: "flow-libraries__chip flow-libraries__chip--resource"
              }, [
                n[33] || (n[33] = o("span", { class: "flow-libraries__chip-kind" }, "R", -1)),
                o("span", {
                  class: "flow-libraries__chip-name",
                  title: C.value
                }, m(C.value), 9, yd),
                o("button", {
                  type: "button",
                  class: "flow-libraries__chip-remove",
                  title: p(s)("flowEditor.libraryRemoveTitle", { name: C.value }),
                  onClick: (S) => ue(C.idx),
                  "data-testid": "flow-library-remove"
                }, "×", 8, bd)
              ]))), 128))
            ])
          ])) : $("", !0),
          K.value.length ? (i(), u("div", wd, [
            o("div", _d, m(p(s)("flowEditor.librariesPanelLabel")) + " (" + m(K.value.length) + ")", 1),
            o("div", kd, [
              (i(!0), u(U, null, Y(K.value, (C) => (i(), u("span", {
                key: C.idx,
                class: "flow-libraries__chip"
              }, [
                n[34] || (n[34] = o("span", { class: "flow-libraries__chip-kind" }, "L", -1)),
                o("span", {
                  class: "flow-libraries__chip-name",
                  title: C.value
                }, m(C.value), 9, Cd),
                o("button", {
                  type: "button",
                  class: "flow-libraries__chip-remove",
                  title: p(s)("flowEditor.libraryRemoveTitle", { name: C.value }),
                  onClick: (S) => ue(C.idx),
                  "data-testid": "flow-library-remove"
                }, "×", 8, $d)
              ]))), 128))
            ])
          ])) : $("", !0),
          o("div", Td, [
            z(o("input", {
              "onUpdate:modelValue": n[5] || (n[5] = (C) => G.value = C),
              type: "text",
              class: "flow-libraries__input",
              placeholder: p(s)("flowEditor.libraryInputPlaceholder"),
              autocomplete: "off",
              spellcheck: "false",
              "data-testid": "flow-library-input",
              onKeydown: Ze(le(H, ["prevent"]), ["enter"])
            }, null, 40, Ed), [
              [ae, G.value]
            ]),
            o("button", {
              type: "button",
              class: "flow-libraries__add",
              disabled: !G.value.trim(),
              "data-testid": "flow-library-add",
              onClick: H
            }, "+ " + m(p(s)("flowEditor.libraryAdd")), 9, Sd)
          ]),
          se.value.length ? (i(), u("div", Ld, [
            (i(!0), u(U, null, Y(se.value, (C) => (i(), u("button", {
              key: C,
              type: "button",
              class: "flow-libraries__suggestion",
              onClick: (S) => be(C)
            }, m(C), 9, Vd))), 128))
          ])) : $("", !0)
        ])) : $("", !0),
        ce.value ? (i(), u("div", Ad, [
          o("div", Nd, [
            $e.value.length === 0 ? (i(), u("span", Rd, m(p(s)("flowEditor.variablesNone")), 1)) : $("", !0),
            (i(!0), u(U, null, Y($e.value, (C) => (i(), u("div", {
              key: C.idx,
              class: "flow-vars__row"
            }, [
              o("input", {
                class: "flow-vars__name",
                value: C.name,
                spellcheck: "false",
                autocomplete: "off",
                "data-testid": "flow-variable-name",
                onChange: (S) => Qe(C.idx, S.target.value)
              }, null, 40, Fd),
              o("input", {
                class: "flow-vars__value",
                value: C.value,
                spellcheck: "false",
                autocomplete: "off",
                "data-testid": "flow-variable-value",
                onChange: (S) => mt(C.idx, S.target.value)
              }, null, 40, Pd),
              o("button", {
                type: "button",
                class: "flow-libraries__chip-remove",
                title: p(s)("flowEditor.variableRemoveTitle", { name: C.name }),
                "data-testid": "flow-variable-remove",
                onClick: (S) => ut(C.idx)
              }, "×", 8, Dd)
            ]))), 128))
          ]),
          o("div", Md, [
            z(o("input", {
              "onUpdate:modelValue": n[6] || (n[6] = (C) => ge.value = C),
              type: "text",
              class: "flow-vars__name",
              placeholder: p(s)("flowEditor.variableNamePlaceholder"),
              autocomplete: "off",
              spellcheck: "false",
              "data-testid": "flow-variable-new-name",
              onKeydown: Ze(le(ie, ["prevent"]), ["enter"])
            }, null, 40, xd), [
              [ae, ge.value]
            ]),
            z(o("input", {
              "onUpdate:modelValue": n[7] || (n[7] = (C) => Le.value = C),
              type: "text",
              class: "flow-vars__value",
              placeholder: p(s)("flowEditor.variableValuePlaceholder"),
              autocomplete: "off",
              spellcheck: "false",
              "data-testid": "flow-variable-new-value",
              onKeydown: Ze(le(ie, ["prevent"]), ["enter"])
            }, null, 40, Ud), [
              [ae, Le.value]
            ]),
            o("button", {
              type: "button",
              class: "flow-libraries__add",
              disabled: !ge.value.trim(),
              "data-testid": "flow-variable-add",
              onClick: ie
            }, "+ " + m(p(s)("flowEditor.variableAdd")), 9, Bd)
          ]),
          de.value.length ? (i(), u("div", Kd, [
            o("span", {
              class: "flow-vars__envrefs-title",
              title: vt.value ? p(s)("flowEditor.envVarCheckedAgainst", { env: De.value }) : void 0
            }, m(p(s)("flowEditor.envVarsUsed")), 9, Hd),
            (i(!0), u(U, null, Y(Zt.value, ({ ref: C, state: S }) => (i(), u("span", {
              key: C.name,
              class: te(["flow-libraries__chip", { "flow-vars__envref--missing": S === "missing" }]),
              "data-state": S,
              title: Tt(C.name, S),
              "data-testid": "flow-env-var"
            }, [
              o("span", Id, "%" + m("{") + m(C.name) + m(C.default !== null ? "=" + C.default : "") + m("}"), 1)
            ], 10, Od))), 128))
          ])) : $("", !0)
        ])) : $("", !0),
        nt.value ? (i(), u("div", Wd, [
          o("div", jd, [
            Et.value.length === 0 ? (i(), u("span", qd, m(p(s)("flowEditor.suiteSettingsNone")), 1)) : $("", !0),
            (i(!0), u(U, null, Y(Et.value, (C) => (i(), u("div", {
              key: C.idx,
              class: "flow-vars__row"
            }, [
              o("span", zd, m(C.key), 1),
              o("input", {
                class: "flow-vars__value",
                value: C.value,
                spellcheck: "false",
                autocomplete: "off",
                "data-testid": "flow-suite-setting-value",
                onChange: (S) => St(C.idx, S.target.value)
              }, null, 40, Gd),
              o("button", {
                type: "button",
                class: "flow-libraries__chip-remove",
                title: p(s)("flowEditor.suiteSettingRemoveTitle", { name: C.key }),
                "data-testid": "flow-suite-setting-remove",
                onClick: (S) => Bt(C.idx)
              }, "×", 8, Yd)
            ]))), 128))
          ]),
          Ut.value.length ? (i(), u("div", Xd, [
            (i(!0), u(U, null, Y(Ut.value, (C) => (i(), u("button", {
              key: C,
              type: "button",
              class: "flow-libraries__suggestion",
              "data-testid": "flow-suite-setting-add",
              onClick: (S) => Jt(C)
            }, "+ " + m(C), 9, Zd))), 128))
          ])) : $("", !0)
        ])) : $("", !0),
        Z.value ? (i(), u("div", Qd, [
          o("p", null, m(Z.value === "testcases" ? p(s)("flowEditor.noTestCasesYet") : p(s)("flowEditor.noKeywordsYet")), 1),
          o("div", Jd, [
            Z.value === "testcases" ? (i(), u("button", {
              key: 0,
              type: "button",
              class: "flow-empty__cta",
              "data-testid": "flow-empty-add-test-case",
              onClick: ht
            }, "+ " + m(p(s)("flowEditor.addTestCase")), 1)) : $("", !0),
            Z.value === "keywords" ? (i(), u("button", {
              key: 1,
              type: "button",
              class: "flow-empty__cta",
              "data-testid": "flow-empty-add-keyword",
              onClick: Lt
            }, "+ " + m(p(s)("flowEditor.addKeyword")), 1)) : $("", !0)
          ])
        ])) : (i(), u("div", ep, [
          ve(fi, {
            "scope-id": p(c).scopeId ?? void 0,
            "file-path": p(c).filePath,
            "imported-libraries": j.value,
            "usage-counts": oo.value,
            "file-import-count": Ln.value,
            "file-step-count": Vn.value,
            onAddNode: Un
          }, null, 8, ["scope-id", "file-path", "imported-libraries", "usage-counts", "file-import-count", "file-step-count"]),
          o("div", {
            ref_key: "canvasRef",
            ref: Po,
            class: "flow-canvas"
          }, [
            ve(p(ms), {
              onDrop: Uo,
              onDragover: bo,
              onDragleave: pn,
              nodes: An.value,
              edges: Rt.value,
              "default-viewport": { zoom: 0.9, x: 0, y: 0 },
              "min-zoom": 0.2,
              "max-zoom": 2,
              "nodes-draggable": !1,
              "fit-view-on-init": "",
              onNodeClick: Jo,
              onPaneClick: So
            }, {
              "node-keyword": _e((C) => {
                var S;
                return [
                  ve(da, Bo(C, {
                    "reorder-enabled": !0,
                    selected: ((S = A.value) == null ? void 0 : S.id) === C.id,
                    onReorderDragStart: (he) => Gt(he, C.id)
                  }), null, 16, ["selected", "onReorderDragStart"])
                ];
              }),
              "node-assignment": _e((C) => {
                var S;
                return [
                  ve(da, Bo(C, {
                    "reorder-enabled": !0,
                    selected: ((S = A.value) == null ? void 0 : S.id) === C.id,
                    onReorderDragStart: (he) => Gt(he, C.id)
                  }), null, 16, ["selected", "onReorderDragStart"])
                ];
              }),
              "node-control": _e((C) => {
                var S;
                return [
                  ve(cl, Bo(C, {
                    "reorder-enabled": !0,
                    selected: ((S = A.value) == null ? void 0 : S.id) === C.id,
                    onReorderDragStart: (he) => Gt(he, C.id)
                  }), null, 16, ["selected", "onReorderDragStart"])
                ];
              }),
              "node-control-frame": _e((C) => [
                ve(pl, cs(ds(C)), null, 16)
              ]),
              "node-start": _e((C) => [
                ve(pa, Bo(C, { type: "start" }), null, 16)
              ]),
              "node-end": _e((C) => [
                ve(pa, Bo(C, { type: "end" }), null, 16)
              ]),
              "node-setting-meta": _e((C) => {
                var S;
                return [
                  o("div", {
                    class: te(["flow-node-doc-meta", [
                      `flow-node-doc-meta--${C.data.kind}`,
                      { "flow-node--selected": ((S = A.value) == null ? void 0 : S.id) === C.id }
                    ]])
                  }, [
                    o("div", tp, [
                      o("span", op, m(C.data.label), 1),
                      C.data.text && C.data.text.trim() ? (i(), u("p", np, m(C.data.text), 1)) : (i(), u("p", ap, m(p(s)("flowEditor.settingMeta.emptyHint")), 1))
                    ]),
                    ve(p(Ct), {
                      type: "source",
                      position: p($t).Right,
                      id: "right",
                      style: { opacity: "0", "pointer-events": "none" }
                    }, null, 8, ["position"])
                  ], 2)
                ];
              }),
              "node-comment": _e((C) => {
                var S;
                return [
                  o("div", {
                    class: te(["flow-node-comment", { "flow-node--selected": ((S = A.value) == null ? void 0 : S.id) === C.id }])
                  }, [
                    o("div", {
                      class: "flow-drag-handle",
                      draggable: "true",
                      onMousedown: n[8] || (n[8] = le(() => {
                      }, ["stop"])),
                      onDragstart: le((he) => Gt(he, C.id), ["stop"])
                    }, "☰", 40, sp),
                    o("span", null, m(C.data.label), 1)
                  ], 2)
                ];
              }),
              "node-template-table": _e((C) => [
                o("div", lp, [
                  ve(p(Ct), {
                    type: "target",
                    position: p($t).Top
                  }, null, 8, ["position"]),
                  o("div", rp, [
                    n[35] || (n[35] = o("span", { class: "flow-node-template__badge" }, "[Template]", -1)),
                    o("span", ip, m(C.data.templateKeyword || "—"), 1)
                  ]),
                  o("table", up, [
                    o("tbody", null, [
                      (i(!0), u(U, null, Y(C.data.templateRows, (S, he) => (i(), u("tr", { key: he }, [
                        (i(!0), u(U, null, Y(S, (Pe, Ce) => (i(), u("td", { key: Ce }, [
                          o("input", {
                            class: "flow-node-template__cell",
                            value: Pe,
                            spellcheck: "false",
                            "data-testid": "flow-template-cell",
                            onMousedown: n[9] || (n[9] = le(() => {
                            }, ["stop"])),
                            onChange: (kt) => gt(C.data.sectionIndex, he, Ce, kt.target.value)
                          }, null, 40, cp)
                        ]))), 128)),
                        o("td", null, [
                          o("button", {
                            type: "button",
                            class: "flow-node-template__rm",
                            "data-testid": "flow-template-remove-row",
                            title: p(s)("flowEditor.templateRemoveRow"),
                            onMousedown: n[10] || (n[10] = le(() => {
                            }, ["stop"])),
                            onClick: le((Pe) => Ke(C.data.sectionIndex, he), ["stop"])
                          }, "×", 40, dp)
                        ])
                      ]))), 128))
                    ])
                  ]),
                  o("div", pp, [
                    o("button", {
                      type: "button",
                      "data-testid": "flow-template-add-row",
                      onMousedown: n[11] || (n[11] = le(() => {
                      }, ["stop"])),
                      onClick: le((S) => Ye(C.data.sectionIndex), ["stop"])
                    }, "+ " + m(p(s)("flowEditor.templateAddRow")), 41, fp),
                    o("button", {
                      type: "button",
                      "data-testid": "flow-template-add-col",
                      onMousedown: n[12] || (n[12] = le(() => {
                      }, ["stop"])),
                      onClick: le((S) => ee(C.data.sectionIndex), ["stop"])
                    }, "+ " + m(p(s)("flowEditor.templateAddColumn")), 41, mp)
                  ]),
                  ve(p(Ct), {
                    type: "source",
                    position: p($t).Bottom
                  }, null, 8, ["position"])
                ])
              ]),
              "node-flow-control": _e((C) => {
                var S;
                return [
                  o("div", {
                    class: te(["flow-node-flowctrl", { "flow-node--selected": ((S = A.value) == null ? void 0 : S.id) === C.id }])
                  }, [
                    o("div", {
                      class: "flow-drag-handle",
                      draggable: "true",
                      onMousedown: n[13] || (n[13] = le(() => {
                      }, ["stop"])),
                      onDragstart: le((he) => Gt(he, C.id), ["stop"])
                    }, "☰", 40, gp),
                    o("span", null, m(C.data.label), 1)
                  ], 2)
                ];
              }),
              "node-return": _e((C) => {
                var S, he, Pe;
                return [
                  o("div", {
                    class: te(["flow-node-return-block", { "flow-node--selected": ((S = A.value) == null ? void 0 : S.id) === C.id }])
                  }, [
                    o("div", {
                      class: "flow-drag-handle",
                      draggable: "true",
                      onMousedown: n[14] || (n[14] = le(() => {
                      }, ["stop"])),
                      onDragstart: le((Ce) => Gt(Ce, C.id), ["stop"])
                    }, "☰", 40, vp),
                    n[36] || (n[36] = o("span", { class: "flow-node-return-label" }, "↵ RETURN", -1)),
                    (Pe = (he = C.data.step) == null ? void 0 : he.args) != null && Pe.length ? (i(), u("div", hp, [
                      (i(!0), u(U, null, Y(C.data.step.args, (Ce, kt) => (i(), u("span", {
                        key: kt,
                        class: "flow-node-return-value"
                      }, m(Ce), 1))), 128))
                    ])) : (i(), u("span", yp, m(p(s)("flowEditor.returnNoValue")), 1))
                  ], 2)
                ];
              }),
              "node-drop-indicator": _e(() => [...n[37] || (n[37] = [
                o("div", { class: "flow-drop-indicator" }, [
                  o("div", { class: "flow-drop-dot" }),
                  o("div", { class: "flow-drop-line" }),
                  o("div", { class: "flow-drop-dot" })
                ], -1)
              ])]),
              default: _e(() => [
                ve(p(gs)),
                ve(p(vs)),
                ve(p(hs))
              ]),
              _: 1
            }, 8, ["nodes", "edges"])
          ], 512),
          M.value ? (i(), u("div", {
            key: 0,
            class: te(["flow-detail-panel", { "flow-detail-panel--resizing": ct.value }]),
            style: Ee({ width: eo.value + "px" })
          }, [
            o("div", {
              class: "flow-detail-resizer",
              role: "separator",
              "aria-orientation": "vertical",
              title: p(s)("flowEditor.resizePanelHint"),
              onPointerdown: to
            }, null, 40, bp),
            o("div", wp, [
              o("h4", null, m(T.value === "testcases" ? p(s)("flowEditor.testCaseSettings") : p(s)("flowEditor.keywordSettings")), 1)
            ]),
            o("div", _p, [
              (i(!0), u(U, null, Y(Xo.value, (C) => (i(), u(U, { key: C }, [
                Kt(C) ? (i(), u("button", {
                  key: 0,
                  type: "button",
                  class: "flow-btn-add",
                  title: p(s)(`flowEditor.settingMeta.${C}.addTitle`),
                  "data-testid": `flow-add-${C}`,
                  onClick: (S) => Sn(C)
                }, "+ " + m(Te[C]), 9, kp)) : $("", !0)
              ], 64))), 128)),
              Xo.value.every((C) => !Kt(C)) ? (i(), u("p", Cp, m(p(s)("flowEditor.settingMeta.allPresent")), 1)) : $("", !0)
            ])
          ], 6)) : Fe.value ? (i(), u("div", {
            key: 1,
            class: te(["flow-detail-panel", { "flow-detail-panel--resizing": ct.value }]),
            style: Ee({ width: eo.value + "px" })
          }, [
            o("div", {
              class: "flow-detail-resizer",
              role: "separator",
              "aria-orientation": "vertical",
              title: p(s)("flowEditor.resizePanelHint"),
              onPointerdown: to
            }, null, 40, $p),
            o("div", Tp, [
              o("h4", null, m(Te[Fe.value.kind]), 1),
              o("div", Ep, [
                o("button", {
                  class: "flow-action-btn flow-action-delete",
                  onClick: $o,
                  title: p(s)(`flowEditor.settingMeta.${Fe.value.kind}.removeTitle`)
                }, "×", 8, Sp)
              ])
            ]),
            o("div", Lp, [
              o("label", null, m(p(s)(`flowEditor.settingMeta.${Fe.value.kind}.label`)), 1),
              Fe.value.kind === "documentation" ? z((i(), u("textarea", {
                key: 0,
                "onUpdate:modelValue": n[15] || (n[15] = (C) => Vt.value = C),
                class: "flow-input flow-textarea",
                rows: "8",
                placeholder: p(s)("flowEditor.settingMeta.documentation.placeholder"),
                onBlur: po
              }, null, 40, Vp)), [
                [ae, Vt.value]
              ]) : z((i(), u("input", {
                key: 1,
                "onUpdate:modelValue": n[16] || (n[16] = (C) => Vt.value = C),
                type: "text",
                class: "flow-input",
                placeholder: p(s)(`flowEditor.settingMeta.${Fe.value.kind}.placeholder`),
                onBlur: po
              }, null, 40, Ap)), [
                [ae, Vt.value]
              ])
            ]),
            o("p", Np, m(p(s)(`flowEditor.settingMeta.${Fe.value.kind}.hint`)), 1)
          ], 6)) : w.value ? (i(), u("div", {
            key: 2,
            class: te(["flow-detail-panel", { "flow-detail-panel--resizing": ct.value }]),
            style: Ee({ width: eo.value + "px" }),
            onDrop: le(Uo, ["stop"]),
            onDragover: le(bo, ["stop", "prevent"]),
            onDragleave: le(pn, ["stop"])
          }, [
            o("div", {
              class: "flow-detail-resizer",
              role: "separator",
              "aria-orientation": "vertical",
              title: p(s)("flowEditor.resizePanelHint"),
              onPointerdown: to
            }, null, 40, Rp),
            o("div", Fp, [
              o("h4", null, m(w.value.stepType.toUpperCase().replace("_", " ")), 1),
              o("div", Pp, [
                o("button", {
                  class: "flow-action-btn",
                  onClick: ro,
                  title: "Move up"
                }, "↑"),
                o("button", {
                  class: "flow-action-btn",
                  onClick: Bn,
                  title: "Move down"
                }, "↓"),
                no.value ? (i(), u("button", {
                  key: 0,
                  class: "flow-action-btn flow-action-info",
                  title: p(s)("flowEditor.docModal.button"),
                  "data-testid": "kw-doc-info-btn",
                  onClick: bt
                }, "ⓘ", 8, Dp)) : $("", !0),
                o("button", {
                  class: "flow-action-btn flow-action-delete",
                  onClick: cn,
                  title: "Delete"
                }, "×")
              ])
            ]),
            Yt.value ? (i(), u("div", Mp, [
              o("button", {
                class: "flow-debug-step-btn",
                title: p(s)("flowEditor.debug.btnTitle"),
                "data-testid": "flow-debug-step-btn",
                onClick: In
              }, [
                n[38] || (n[38] = o("span", { "aria-hidden": "true" }, "▶", -1)),
                we(" " + m(p(s)("flowEditor.debug.btnLabel")), 1)
              ], 8, xp),
              Mt.value ? (i(), u("p", Up, m(Mt.value), 1)) : $("", !0)
            ])) : $("", !0),
            ["keyword", "assignment"].includes(w.value.stepType) ? (i(), u("div", Bp, [
              o("label", null, m(p(s)("flowEditor.keyword")), 1),
              ve(wi, {
                value: w.value.step.keyword,
                "onUpdate:value": Pn,
                onSelect: Me
              }, null, 8, ["value"])
            ])) : $("", !0),
            Vo.value !== "hidden" ? (i(), u("div", Kp, [
              o("label", {
                for: "heal-toggle-" + ((v = A.value) == null ? void 0 : v.id)
              }, m(p(s)("flowEditor.heal.toggleLabel")), 9, Hp),
              o("div", Op, [
                o("input", {
                  id: "heal-toggle-" + ((F = A.value) == null ? void 0 : F.id),
                  type: "checkbox",
                  checked: Vo.value === "on",
                  "data-testid": "flow-step-heal-toggle",
                  onChange: n[17] || (n[17] = (C) => mo(C.target.checked))
                }, null, 40, Ip),
                o("span", Wp, m(p(s)("flowEditor.heal.toggleHint")), 1)
              ])
            ])) : $("", !0),
            ["keyword", "assignment"].includes(w.value.stepType) ? (i(), u("div", jp, [
              o("label", null, m(p(s)("flowEditor.arguments")), 1),
              (i(!0), u(U, null, Y(w.value.step.args, (C, S) => {
                var he, Pe;
                return i(), u("div", {
                  key: S,
                  class: "flow-arg-row"
                }, [
                  o("span", {
                    class: "flow-arg-name-inline",
                    title: ((Pe = (he = w.value.argSpecs) == null ? void 0 : he[S]) == null ? void 0 : Pe.type) ?? void 0
                  }, m(Fn(S)) + ":", 9, qp),
                  !(S === 0 && tn.value && w.value.recording) && Ue(S).labelKey !== "flowEditor.argTypes.unknown" ? (i(), u("span", {
                    key: 0,
                    class: "flow-arg-type-chip",
                    title: an(S),
                    "data-testid": "arg-type-chip"
                  }, [
                    o("span", Gp, m(Ue(S).icon), 1),
                    o("span", Yp, m(p(s)(Ue(S).labelKey)), 1),
                    Ue(S).optional ? (i(), u("span", Xp, "?")) : $("", !0)
                  ], 8, zp)) : $("", !0),
                  S === 0 && tn.value && w.value.recording ? (i(), u("div", Zp, [
                    ve(wu, {
                      command: w.value.recording,
                      "onUpdate:activeIndex": Nn,
                      "onUpdate:candidate": en,
                      "onAdd:candidate": Rn
                    }, null, 8, ["command"]),
                    on.value ? (i(), u("span", Qp, m(p(s)("flowEditor.selector.customValueHint")), 1)) : $("", !0)
                  ])) : (i(), u(U, { key: 2 }, [
                    Ft(S) === "checkbox" ? (i(), u("input", {
                      key: 0,
                      type: "checkbox",
                      class: "flow-input-checkbox",
                      checked: Dn(S),
                      onChange: (Ce) => rn(S, Ce),
                      "data-testid": `arg-bool-${S}`
                    }, null, 40, Jp)) : Ft(S) === "select" ? z((i(), u("select", {
                      key: 1,
                      "onUpdate:modelValue": (Ce) => w.value.step.args[S] = Ce,
                      class: "flow-input flow-input-sm",
                      "data-testid": `arg-select-${S}`,
                      onChange: Me
                    }, [
                      w.value.step.args[S] && !(Ue(S).choices ?? []).includes(w.value.step.args[S]) ? (i(), u("option", {
                        key: 0,
                        value: w.value.step.args[S]
                      }, m(w.value.step.args[S]) + " (" + m(p(s)("flowEditor.argTypes.customValue")) + ")", 9, tf)) : $("", !0),
                      o("option", of, m(tt(S)), 1),
                      (i(!0), u(U, null, Y(Ue(S).choices ?? [], (Ce) => (i(), u("option", {
                        key: Ce,
                        value: Ce
                      }, m(Ce), 9, nf))), 128))
                    ], 40, ef)), [
                      [it, w.value.step.args[S]]
                    ]) : Ft(S) === "integer" || Ft(S) === "number" ? z((i(), u("input", {
                      key: 2,
                      "onUpdate:modelValue": (Ce) => w.value.step.args[S] = Ce,
                      type: "number",
                      step: Ft(S) === "integer" ? "1" : "any",
                      inputmode: "decimal",
                      class: "flow-input flow-input-sm",
                      placeholder: tt(S),
                      onChange: Me
                    }, null, 40, af)), [
                      [ae, w.value.step.args[S]]
                    ]) : Ft(S) === "duration" ? z((i(), u("input", {
                      key: 3,
                      "onUpdate:modelValue": (Ce) => w.value.step.args[S] = Ce,
                      class: "flow-input flow-input-sm",
                      placeholder: tt(S) || p(s)("flowEditor.argTypes.durationHint"),
                      title: p(s)("flowEditor.argTypes.durationHint"),
                      onChange: Me
                    }, null, 40, sf)), [
                      [ae, w.value.step.args[S]]
                    ]) : z((i(), u("input", {
                      key: 4,
                      "onUpdate:modelValue": (Ce) => w.value.step.args[S] = Ce,
                      class: "flow-input flow-input-sm",
                      placeholder: tt(S),
                      onChange: Me
                    }, null, 40, lf)), [
                      [ae, w.value.step.args[S]]
                    ]),
                    Ue(S).control !== "text" ? (i(), u("button", {
                      key: 5,
                      type: "button",
                      class: "flow-input-toggle",
                      title: go(S) ? p(s)("flowEditor.argTypes.toggleBackToTyped") : p(s)("flowEditor.argTypes.toggleToText"),
                      "data-testid": `arg-text-toggle-${S}`,
                      onClick: (Ce) => sn(S)
                    }, m(go(S) ? "⌨" : "{}"), 9, rf)) : $("", !0)
                  ], 64)),
                  o("button", {
                    class: "flow-btn-remove",
                    onClick: (Ce) => Ve(S)
                  }, "×", 8, uf)
                ]);
              }), 128)),
              o("div", {
                ref_key: "addArgTriggerRef",
                ref: xe,
                class: "flow-add-arg-wrap"
              }, [
                o("button", {
                  class: "flow-btn-add",
                  type: "button",
                  onClick: Mn
                }, [
                  we(" + " + m(p(s)("flowEditor.addArg")) + " ", 1),
                  No.value.length > 0 ? (i(), u("span", cf, "▾")) : $("", !0)
                ]),
                (i(), xt(bn, { to: "body" }, [
                  lt.value ? (i(), u("div", {
                    key: 0,
                    ref_key: "addArgPopoverRef",
                    ref: It,
                    class: "flow-add-arg-popover",
                    role: "listbox",
                    "data-testid": "add-arg-popover",
                    style: Ee(dt.value)
                  }, [
                    No.value.length === 0 ? (i(), u("div", df, m(p(s)("flowEditor.addArgPicker.noUnused")), 1)) : $("", !0),
                    (i(!0), u(U, null, Y(No.value, (C) => (i(), u("button", {
                      key: C.name,
                      type: "button",
                      class: "flow-add-arg-option",
                      "data-testid": `add-arg-option-${C.name}`,
                      onClick: (S) => wt(C)
                    }, [
                      o("span", ff, m(C.name), 1),
                      C.defaultValue !== null ? (i(), u("span", mf, " = " + m(C.defaultValue), 1)) : $("", !0),
                      C.isNextPositional ? $("", !0) : (i(), u("span", gf, m(p(s)("flowEditor.addArgPicker.namedHint")), 1))
                    ], 8, pf))), 128)),
                    o("div", vf, [
                      o("span", hf, m(p(s)("flowEditor.addArgPicker.customLabel")), 1),
                      o("div", yf, [
                        z(o("input", {
                          "onUpdate:modelValue": n[18] || (n[18] = (C) => Pt.value = C),
                          type: "text",
                          class: "flow-add-arg-custom-input",
                          placeholder: p(s)("flowEditor.addArgPicker.customPlaceholder"),
                          autocomplete: "off",
                          spellcheck: "false",
                          "data-testid": "add-arg-custom-input",
                          onKeydown: [
                            Ze(le(Dt, ["prevent"]), ["enter"]),
                            n[19] || (n[19] = le(() => {
                            }, ["stop"]))
                          ]
                        }, null, 40, bf), [
                          [ae, Pt.value]
                        ]),
                        o("button", {
                          type: "button",
                          class: "flow-add-arg-custom-add",
                          disabled: !Pt.value.trim(),
                          "data-testid": "add-arg-custom-add",
                          onClick: Dt
                        }, m(p(s)("flowEditor.addArgPicker.customAdd")), 9, wf)
                      ])
                    ])
                  ], 4)) : $("", !0)
                ]))
              ], 512)
            ])) : $("", !0),
            ["keyword", "assignment"].includes(w.value.stepType) ? (i(), u("div", _f, [
              o("label", null, m(p(s)("flowEditor.returnVars")), 1),
              (i(!0), u(U, null, Y(w.value.step.returnVars, (C, S) => (i(), u("div", {
                key: S,
                class: "flow-arg-row"
              }, [
                z(o("input", {
                  "onUpdate:modelValue": (he) => w.value.step.returnVars[S] = he,
                  class: "flow-input flow-input-sm",
                  onChange: Me
                }, null, 40, kf), [
                  [ae, w.value.step.returnVars[S]]
                ]),
                o("button", {
                  class: "flow-btn-remove",
                  onClick: (he) => rt(S)
                }, "×", 8, Cf)
              ]))), 128)),
              o("button", {
                class: "flow-btn-add",
                onClick: jt
              }, "+ " + m(p(s)("flowEditor.addVar")), 1)
            ])) : $("", !0),
            w.value.stepType === "return" ? (i(), u("div", $f, [
              o("label", null, m(p(s)("flowEditor.returnValues")), 1),
              (i(!0), u(U, null, Y(w.value.step.args, (C, S) => (i(), u("div", {
                key: S,
                class: "flow-arg-row"
              }, [
                z(o("input", {
                  "onUpdate:modelValue": (he) => w.value.step.args[S] = he,
                  class: "flow-input flow-input-sm",
                  placeholder: p(s)("flowEditor.returnValuePlaceholder"),
                  "data-testid": `return-value-${S}`,
                  onChange: Me
                }, null, 40, Tf), [
                  [ae, w.value.step.args[S]]
                ]),
                o("button", {
                  class: "flow-btn-remove",
                  onClick: (he) => un(S)
                }, "×", 8, Ef)
              ]))), 128)),
              o("button", {
                type: "button",
                class: "flow-btn-add",
                "data-testid": "add-return-value",
                onClick: yo
              }, "+ " + m(p(s)("flowEditor.addValue")), 1)
            ])) : $("", !0),
            ["if", "else_if", "while"].includes(w.value.stepType) ? (i(), u("div", Sf, [
              o("label", null, m(p(s)("flowEditor.condition")), 1),
              z(o("input", {
                "onUpdate:modelValue": n[20] || (n[20] = (C) => w.value.step.condition = C),
                class: "flow-input",
                onChange: Me
              }, null, 544), [
                [ae, w.value.step.condition]
              ])
            ])) : $("", !0),
            w.value.stepType === "for" ? (i(), u("div", Lf, [
              o("label", null, m(p(s)("flowEditor.loopVar")), 1),
              z(o("input", {
                "onUpdate:modelValue": n[21] || (n[21] = (C) => w.value.step.loopVar = C),
                class: "flow-input",
                onChange: Me
              }, null, 544), [
                [ae, w.value.step.loopVar]
              ])
            ])) : $("", !0),
            w.value.stepType === "for" ? (i(), u("div", Vf, [
              o("label", null, m(p(s)("flowEditor.loopFlavor")), 1),
              z(o("select", {
                "onUpdate:modelValue": n[22] || (n[22] = (C) => w.value.step.loopFlavor = C),
                class: "flow-input",
                onChange: Me
              }, [...n[39] || (n[39] = [
                o("option", null, "IN", -1),
                o("option", null, "IN RANGE", -1),
                o("option", null, "IN ENUMERATE", -1),
                o("option", null, "IN ZIP", -1)
              ])], 544), [
                [it, w.value.step.loopFlavor]
              ])
            ])) : $("", !0),
            w.value.stepType === "for" ? (i(), u("div", Af, [
              o("label", null, m(p(s)("flowEditor.loopValues")), 1),
              (i(!0), u(U, null, Y(w.value.step.loopValues, (C, S) => (i(), u("div", {
                key: S,
                class: "flow-arg-row"
              }, [
                z(o("input", {
                  "onUpdate:modelValue": (he) => w.value.step.loopValues[S] = he,
                  class: "flow-input flow-input-sm",
                  onChange: Me
                }, null, 40, Nf), [
                  [ae, w.value.step.loopValues[S]]
                ]),
                o("button", {
                  class: "flow-btn-remove",
                  onClick: (he) => xn(S)
                }, "×", 8, Rf)
              ]))), 128)),
              o("button", {
                class: "flow-btn-add",
                onClick: Ro
              }, "+ " + m(p(s)("flowEditor.addValue")), 1)
            ])) : $("", !0),
            w.value.stepType === "except" ? (i(), u("div", Ff, [
              o("label", null, m(p(s)("flowEditor.exceptPattern")), 1),
              z(o("input", {
                "onUpdate:modelValue": n[23] || (n[23] = (C) => w.value.step.exceptPattern = C),
                class: "flow-input",
                onChange: Me
              }, null, 544), [
                [ae, w.value.step.exceptPattern]
              ])
            ])) : $("", !0),
            w.value.stepType === "except" ? (i(), u("div", Pf, [
              o("label", null, m(p(s)("flowEditor.exceptVar")), 1),
              z(o("input", {
                "onUpdate:modelValue": n[24] || (n[24] = (C) => w.value.step.exceptVar = C),
                class: "flow-input",
                placeholder: "AS ${error}",
                onChange: Me
              }, null, 544), [
                [ae, w.value.step.exceptVar]
              ])
            ])) : $("", !0),
            w.value.stepType === "var" ? (i(), u("div", Df, [
              o("label", null, m(p(s)("flowEditor.varName")), 1),
              z(o("input", {
                "onUpdate:modelValue": n[25] || (n[25] = (C) => w.value.step.keyword = C),
                class: "flow-input",
                onChange: Me
              }, null, 544), [
                [ae, w.value.step.keyword]
              ])
            ])) : $("", !0),
            w.value.stepType === "var" ? (i(), u("div", Mf, [
              o("label", null, m(p(s)("flowEditor.varScope")), 1),
              z(o("select", {
                "onUpdate:modelValue": n[26] || (n[26] = (C) => w.value.step.varScope = C),
                class: "flow-input",
                onChange: Me
              }, [...n[40] || (n[40] = [
                o("option", { value: "" }, "default", -1),
                o("option", null, "LOCAL", -1),
                o("option", null, "TEST", -1),
                o("option", null, "TASK", -1),
                o("option", null, "SUITE", -1),
                o("option", null, "GLOBAL", -1)
              ])], 544), [
                [it, w.value.step.varScope]
              ])
            ])) : $("", !0),
            w.value.stepType === "comment" ? (i(), u("div", xf, [
              o("label", null, m(p(s)("flowEditor.comment")), 1),
              z(o("input", {
                "onUpdate:modelValue": n[27] || (n[27] = (C) => w.value.step.comment = C),
                class: "flow-input",
                onChange: Me
              }, null, 544), [
                [ae, w.value.step.comment]
              ])
            ])) : $("", !0)
          ], 38)) : $("", !0)
        ])),
        ve(Ui, {
          modelValue: st.value,
          "onUpdate:modelValue": n[28] || (n[28] = (C) => st.value = C),
          keyword: nn.value,
          "scope-id": p(c).scopeId ?? void 0
        }, null, 8, ["modelValue", "keyword", "scope-id"]),
        ve(jo, {
          modelValue: pt.value,
          "onUpdate:modelValue": n[30] || (n[30] = (C) => pt.value = C),
          title: p(s)("flowEditor.debug.unsavedHintTitle"),
          size: "sm",
          "data-testid": "flow-debug-unsaved-modal"
        }, {
          footer: _e(() => [
            ve(Oe, {
              variant: "ghost",
              onClick: n[29] || (n[29] = (C) => pt.value = !1)
            }, {
              default: _e(() => [
                we(m(p(s)("editor.common.close")), 1)
              ]),
              _: 1
            })
          ]),
          default: _e(() => [
            o("p", null, m(p(s)("flowEditor.debug.unsavedHint")), 1)
          ]),
          _: 1
        }, 8, ["modelValue", "title"]),
        ve(jo, {
          "model-value": He.value !== null,
          title: p(s)("flowEditor.debug.confirmReplaceTitle"),
          size: "sm",
          "data-testid": "flow-debug-confirm-modal",
          "onUpdate:modelValue": n[32] || (n[32] = (C) => {
            C || (He.value = null);
          })
        }, {
          footer: _e(() => [
            ve(Oe, {
              variant: "ghost",
              onClick: n[31] || (n[31] = (C) => He.value = null)
            }, {
              default: _e(() => [
                we(m(p(s)("editor.common.cancel")), 1)
              ]),
              _: 1
            }),
            ve(Oe, {
              variant: "primary",
              "data-testid": "flow-debug-confirm-restart-btn",
              onClick: h
            }, {
              default: _e(() => [
                we(m(p(s)("flowEditor.debug.confirmReplaceConfirm")), 1)
              ]),
              _: 1
            })
          ]),
          default: _e(() => [
            He.value ? (i(), u("p", Uf, m(p(s)("flowEditor.debug.confirmReplace", {
              lineFrom: He.value.fromLine,
              lineTo: He.value.toLine
            })), 1)) : $("", !0)
          ]),
          _: 1
        }, 8, ["model-value", "title"]),
        (i(), xt(bn, { to: "body" }, [
          l.value ? (i(), u("div", Bf, [
            o("div", Kf, [
              ve(ic, { onClosed: E })
            ])
          ])) : $("", !0)
        ])),
        ve(fc, {
          open: p(Xe).prereqMissing !== null,
          "package-name": ((X = p(Xe).prereqMissing) == null ? void 0 : X.packageName) ?? "robotcode",
          installing: p(Xe).installing,
          "install-error": p(Xe).installError,
          onInstall: y,
          onCancel: d
        }, null, 8, ["open", "package-name", "installing", "install-error"])
      ]);
    };
  }
}), Wf = /* @__PURE__ */ ze(If, [["__scopeId", "data-v-a6829e74"]]), jf = { class: "read-only-banner__msg" }, qf = ["href"], zf = /* @__PURE__ */ qe({
  __name: "ReadOnlyBanner",
  props: {
    role: {},
    adminContactEmail: {}
  },
  setup(e) {
    const a = e, { t: r } = ft(), c = O(() => a.role === "viewer" || a.role === "runner"), g = O(() => a.role === "viewer" ? r("editor.readOnly.viewer") : a.role === "runner" ? r("editor.readOnly.runner") : ""), s = O(
      () => a.adminContactEmail ? `mailto:${a.adminContactEmail}?subject=${encodeURIComponent("Access request")}` : null
    );
    return (_, b) => c.value ? (i(), u("div", {
      key: 0,
      class: te(["read-only-banner", `read-only-banner--${e.role}`]),
      role: "status",
      "aria-live": "polite"
    }, [
      o("span", jf, m(g.value), 1),
      s.value ? (i(), u("a", {
        key: 0,
        href: s.value,
        class: "read-only-banner__cta"
      }, m(p(r)("editor.readOnly.contactAdmin")), 9, qf)) : $("", !0)
    ], 2)) : $("", !0);
  }
}), Gf = /* @__PURE__ */ ze(zf, [["__scopeId", "data-v-078dbe3d"]]), Yf = /* @__PURE__ */ new Set([
  // BuiltIn library
  "call method",
  "catenate",
  "comment",
  "continue for loop",
  "continue for loop if",
  "convert to binary",
  "convert to boolean",
  "convert to bytes",
  "convert to hex",
  "convert to integer",
  "convert to number",
  "convert to octal",
  "convert to string",
  "create dictionary",
  "create list",
  "evaluate",
  "exit for loop",
  "exit for loop if",
  "fail",
  "fatal error",
  "get count",
  "get length",
  "get library instance",
  "get time",
  "get variable value",
  "get variables",
  "import library",
  "import resource",
  "import variables",
  "keyword should exist",
  "length should be",
  "log",
  "log many",
  "log to console",
  "log variables",
  "no operation",
  "pass execution",
  "pass execution if",
  "regexp escape",
  "reload library",
  "remove tags",
  "repeat keyword",
  "replace variables",
  "return from keyword",
  "return from keyword if",
  "run keyword",
  "run keyword and continue on failure",
  "run keyword and expect error",
  "run keyword and ignore error",
  "run keyword and return",
  "run keyword and return if",
  "run keyword and return status",
  "run keyword if",
  "run keyword if all critical tests passed",
  "run keyword if all tests passed",
  "run keyword if any critical tests failed",
  "run keyword if any tests failed",
  "run keyword if test failed",
  "run keyword if test passed",
  "run keyword if timeout occurred",
  "run keyword unless",
  "run keywords",
  "set global variable",
  "set library search order",
  "set log level",
  "set suite documentation",
  "set suite metadata",
  "set suite variable",
  "set tags",
  "set test documentation",
  "set test message",
  "set test variable",
  "set variable",
  "set variable if",
  "should be empty",
  "should be equal",
  "should be equal as integers",
  "should be equal as numbers",
  "should be equal as strings",
  "should be true",
  "should contain",
  "should contain x times",
  "should end with",
  "should match",
  "should match regexp",
  "should not be empty",
  "should not be equal",
  "should not be equal as integers",
  "should not be equal as numbers",
  "should not be equal as strings",
  "should not be true",
  "should not contain",
  "should not end with",
  "should not match",
  "should not match regexp",
  "should not start with",
  "should start with",
  "sleep",
  "variable should exist",
  "variable should not exist",
  "wait until keyword succeeds",
  "skip",
  "skip if",
  // String library
  "convert to lowercase",
  "convert to uppercase",
  "decode bytes to string",
  "encode string to bytes",
  "fetch from left",
  "fetch from right",
  "generate random string",
  "get line",
  "get line count",
  "get lines containing string",
  "get lines matching pattern",
  "get lines matching regexp",
  "get regexp matches",
  "get substring",
  "remove string",
  "remove string using regexp",
  "replace string",
  "replace string using regexp",
  "should be byte string",
  "should be lowercase",
  "should be string",
  "should be titlecase",
  "should be unicode string",
  "should be uppercase",
  "should not be string",
  "split string",
  "split string from right",
  "split string to characters",
  "split to lines",
  // Collections library
  "append to list",
  "combine lists",
  "convert to dictionary",
  "convert to list",
  "copy dictionary",
  "copy list",
  "count values in list",
  "dictionaries should be equal",
  "dictionary should contain item",
  "dictionary should contain key",
  "dictionary should contain sub dictionary",
  "dictionary should contain value",
  "dictionary should not contain key",
  "dictionary should not contain value",
  "get dictionary items",
  "get dictionary keys",
  "get dictionary values",
  "get from dictionary",
  "get from list",
  "get index from list",
  "get match count",
  "get matches",
  "get slice from list",
  "insert into list",
  "keep in dictionary",
  "list should contain sub list",
  "list should contain value",
  "list should not contain duplicates",
  "list should not contain value",
  "lists should be equal",
  "log dictionary",
  "log list",
  "pop from dictionary",
  "remove duplicates",
  "remove from dictionary",
  "remove from list",
  "remove values from list",
  "reverse list",
  "set list value",
  "set to dictionary",
  "should contain match",
  "should not contain match",
  "sort list",
  // DateTime library
  "add time to date",
  "add time to time",
  "convert date",
  "convert time",
  "get current date",
  "subtract date from date",
  "subtract time from date",
  "subtract time from time",
  // OperatingSystem library
  "append to environment variable",
  "append to file",
  "copy directory",
  "copy file",
  "copy files",
  "count directories in directory",
  "count files in directory",
  "count items in directory",
  "create binary file",
  "create directory",
  "create file",
  "directory should be empty",
  "directory should exist",
  "directory should not be empty",
  "directory should not exist",
  "empty directory",
  "environment variable should be set",
  "environment variable should not be set",
  "file should be empty",
  "file should exist",
  "file should not be empty",
  "file should not exist",
  "get binary file",
  "get environment variable",
  "get environment variables",
  "get file",
  "get file size",
  "get modified time",
  "grep file",
  "join path",
  "join paths",
  "list directories in directory",
  "list directory",
  "list files in directory",
  "log environment variables",
  "log file",
  "move directory",
  "move file",
  "move files",
  "normalize path",
  "remove directory",
  "remove environment variable",
  "remove file",
  "remove files",
  "run",
  "run and return rc",
  "run and return rc and output",
  "set environment variable",
  "set modified time",
  "should exist",
  "should not exist",
  "split extension",
  "split path",
  "touch",
  "wait until created",
  "wait until removed",
  // Process library
  "get process id",
  "get process object",
  "get process result",
  "is process running",
  "join command line",
  "process should be running",
  "process should be stopped",
  "run process",
  "send signal to process",
  "split command line",
  "start process",
  "stop all processes",
  "stop process",
  "switch process",
  "terminate all processes",
  "terminate process",
  "wait for process",
  // Telnet library
  "close all connections",
  "close connection",
  "execute command",
  "login",
  "open connection",
  "read",
  "read until",
  "read until prompt",
  "read until regexp",
  "set default log level",
  "set encoding",
  "set newline",
  "set prompt",
  "set telnetlib log level",
  "set timeout",
  "switch connection",
  "write",
  "write bare",
  "write control character",
  "write until expected output",
  // XML library
  "add element",
  "clear element",
  "copy element",
  "element attribute should be",
  "element attribute should match",
  "element should exist",
  "element should not exist",
  "element should not have attribute",
  "element text should be",
  "element text should match",
  "element to string",
  "elements should be equal",
  "elements should match",
  "evaluate xpath",
  "get child elements",
  "get element",
  "get element attribute",
  "get element attributes",
  "get element count",
  "get element text",
  "get elements",
  "get elements texts",
  "log element",
  "parse xml",
  "remove element",
  "remove element attribute",
  "remove element attributes",
  "remove elements",
  "remove elements attribute",
  "remove elements attributes",
  "save xml",
  "set element attribute",
  "set element tag",
  "set element text",
  "set elements attribute",
  "set elements tag",
  "set elements text",
  // Screenshot library
  "set screenshot directory",
  "take screenshot",
  "take screenshot without embedding",
  // Dialogs library
  "execute manual step",
  "get selection from user",
  "get value from user",
  "pause execution"
]), Xf = /^(Library|Resource|Variables|Suite Setup|Suite Teardown|Test Setup|Test Teardown|Test Template|Test Timeout|Force Tags|Default Tags|Metadata|Documentation|Test Tags|Keyword Tags|Name)\b/i, Zf = /^\[(Setup|Tags|Teardown|Documentation|Arguments|Return|Template|Timeout)\]/i, Qf = /^\b(FOR|END|IF|ELSE IF|ELSE|TRY|EXCEPT|FINALLY|WHILE|BREAK|CONTINUE|RETURN|IN|IN RANGE|IN ENUMERATE|IN ZIP)\b/, Jf = /^(Given|When|Then|And|But)(?=\s)/i, em = /^\b(True|False|None|TRUE|FALSE|NONE|EMPTY|SPACE|NULL)\b/, tm = /^\\[ntr\\$"'{}@%&xu#]/, $a = /^[$@%&]\{/, om = /^([A-Za-z_][\w]*)=/;
function nm() {
  return bs.define({
    startState() {
      return { section: "", lineHasKeyword: !1, varDepth: 0, expectDotAfterNs: !1 };
    },
    token(e, a) {
      if (a.varDepth > 0)
        return e.match(/^\}/) ? (a.varDepth--, "bracket") : e.match($a) ? (a.varDepth++, "bracket") : (e.match(/^[A-Za-z_][\w ]*/) || e.next(), "variableName");
      if (e.sol()) {
        if (a.lineHasKeyword = a.section === "variables", a.expectDotAfterNs = !1, e.match(/^\*{3}\s*(Settings?|Variables?|Test Cases?|Tasks?|Keywords?|Comments?)\s*\*{0,3}/i)) {
          const r = e.current().toLowerCase();
          return r.includes("setting") ? a.section = "settings" : r.includes("variable") ? a.section = "variables" : r.includes("test") || r.includes("task") ? a.section = "testcases" : r.includes("keyword") ? a.section = "keywords" : a.section = "comments", "heading";
        }
        if (a.section === "comments")
          return e.skipToEnd(), "comment";
        if (e.match(/^\s+\.\.\.(?=\s|$)/))
          return a.lineHasKeyword = !0, "meta";
        if ((a.section === "testcases" || a.section === "keywords") && !e.match(/^\s/, !1))
          return e.match(/^[^\s][^ ]*( [^ ]+)*/), "definition";
      }
      if (a.expectDotAfterNs && (a.expectDotAfterNs = !1, e.match(/^\./)))
        return "punctuation";
      if (e.match(/^#.*/)) return "comment";
      if (e.match(/^  +/)) return "punctuation";
      if (e.match($a))
        return a.varDepth = 1, "bracket";
      if (e.match(/^\[/) || e.match(/^\]/)) return "bracket";
      if (e.match(Zf) || a.section === "settings" && e.match(Xf))
        return a.lineHasKeyword = !0, "meta";
      if (e.match(Qf))
        return a.lineHasKeyword = !0, "keyword";
      if (!a.lineHasKeyword && e.match(Jf)) return "keyword";
      if (e.match(/^\.\.\.(?=\s|$)/)) return "meta";
      if (e.match(/^[A-Za-z_]/, !1)) {
        if (!a.lineHasKeyword) {
          const r = e.string.slice(e.pos), c = r.match(/^([A-Z][\w]*)(?=\.[A-Z])/);
          if (c)
            return e.pos += c[1].length, a.expectDotAfterNs = !0, "tagName";
          const g = r.match(/^[A-Za-z_][\w]*(?: [A-Za-z_][\w]*)*/);
          if (g) {
            const s = g[0], _ = s.split(" ");
            for (let b = _.length; b >= 1; b--) {
              const k = _.slice(0, b).join(" ");
              if (Yf.has(k.toLowerCase()))
                return e.pos += k.length, a.lineHasKeyword = !0, "function";
            }
            return e.pos += s.length, a.lineHasKeyword = !0, "function";
          }
          return e.next(), null;
        }
        return e.match(om) ? (e.pos -= 1, "attributeName") : e.match(em) ? "atom" : (e.match(/^[A-Za-z_][\w]*/), null);
      }
      return e.match(tm) ? "string-2" : e.match(/^==/) || e.match(/^=/) ? "operator" : e.match(/^"(?:[^"\\]|\\.)*"/) || e.match(/^'(?:[^'\\]|\\.)*'/) ? "string" : e.match(/^-?\d+(\.\d+)?\b/) ? "number" : (e.eatSpace() || e.next(), null);
    }
  });
}
const am = ys.define([
  // Section headers (*** Settings ***) — strong navy heading.
  { tag: Re.heading, color: "#1A2D50", fontWeight: "700" },
  // Comments — muted grey, italic.
  { tag: [Re.comment, Re.lineComment, Re.blockComment], color: "#6B7A99", fontStyle: "italic" },
  // Test case / keyword DEFINITION names — brand-blue, bold (loud).
  { tag: Re.definition(Re.name), color: "#1A2D50", fontWeight: "700" },
  // Keyword CALLS (the verbs in test bodies) — brand-blue, regular.
  { tag: [Re.function(Re.name), Re.function(Re.variableName)], color: "#2D63B0" },
  // Library namespace (`Browser` in `Browser.Click`).
  { tag: Re.tagName, color: "#7B61FF" },
  // Control-flow words (FOR / IF / END / WHILE) and BDD prefixes —
  // accent orange so they read as structural, not as keywords-of-RF.
  { tag: [Re.keyword, Re.controlKeyword], color: "#D4883E", fontWeight: "600" },
  // Setting / [Tag] / continuation `...` — muted teal-ish.
  { tag: Re.meta, color: "#2C9846", fontWeight: "500" },
  // Variable braces `${ }` and index brackets `[ ]`.
  { tag: Re.bracket, color: "#7B61FF", fontWeight: "500" },
  // Variable names inside `${name}`.
  { tag: Re.variableName, color: "#1A2D50" },
  // Strings (quoted).
  { tag: Re.string, color: "#2C9846" },
  // Escape sequences inside arguments (`\n`, `\t`, `\\`).
  { tag: Re.special(Re.string), color: "#D4883E", fontWeight: "600" },
  // Numbers.
  { tag: Re.number, color: "#C0392B" },
  // Atoms (True / False / None / EMPTY / SPACE).
  { tag: Re.atom, color: "#C0392B", fontWeight: "600" },
  // Named-argument names (`force` in `force=True`).
  { tag: Re.attributeName, color: "#7B61FF", fontStyle: "italic" },
  // Operators (`=`, `==`).
  { tag: Re.operator, color: "#D4883E" },
  // Cell separators (2+ spaces) and dots — keep them faint.
  { tag: Re.punctuation, color: "#9AA5BF" }
]);
function ns(e) {
  return e.replace(/\.robot$/i, ".rbs.json");
}
async function sm(e, a) {
  const r = ns(a);
  try {
    const c = await xa(e, r), g = JSON.parse(c.content);
    return Fs(g), Array.isArray(g.commands) ? g : null;
  } catch {
    return null;
  }
}
async function lm(e, a, r) {
  const c = ns(a), g = JSON.stringify(r, null, 2) + `
`;
  await Ua(e, c, g);
}
const Ta = [
  "keyword",
  "assignment",
  "var",
  "comment",
  "for",
  "if",
  "else_if",
  "else",
  "while",
  "try",
  "except",
  "finally",
  "end",
  "break",
  "continue",
  "return"
], Ea = ["LOCAL", "TEST", "TASK", "SUITE", "GLOBAL"], ta = ["IN", "IN RANGE", "IN ENUMERATE", "IN ZIP"];
function Ho(e = "keyword") {
  return {
    type: e,
    keyword: "",
    args: [],
    returnVars: [],
    condition: "",
    loopVar: "${item}",
    loopFlavor: "IN",
    loopValues: [],
    exceptPattern: "",
    exceptVar: "",
    varScope: "",
    comment: ""
  };
}
function $n(e) {
  return e.startsWith("\\#") ? e.slice(1) : e;
}
function as(e) {
  return e && (e.startsWith("#") ? "\\" + e : e);
}
function rm(e) {
  return e && as(e).replace(/ {2,}/g, (r) => "\\ ".repeat(r.length));
}
function Sa(e) {
  return e && $n(e.replace(/\\ /g, " "));
}
const ss = /^# rbs:([a-f0-9]{8,32})$/, pe = "    ", im = /* @__PURE__ */ new Set([
  "FOR",
  "END",
  "IF",
  "ELSE",
  "ELSE IF",
  "WHILE",
  "TRY",
  "EXCEPT",
  "FINALLY",
  "BREAK",
  "CONTINUE",
  "RETURN",
  "VAR"
]);
function um(e) {
  const a = e.split(/  +|\t+/)[0];
  return im.has(a);
}
function cm(e, a) {
  for (let r = a; r < e.length; r++) {
    const c = e[r], g = c.trimEnd();
    if (g) {
      if (ls.test(g) || !/^\s/.test(c)) return !1;
      if (/^\s*\[Template\]\s+\S/i.test(c)) return !0;
    }
  }
  return !1;
}
function dm(e) {
  return e.startsWith("#") && !ss.test(e);
}
function pm(e) {
  const a = Ho(), r = e.trim();
  if (!r) return a;
  if (r.startsWith("#"))
    return a.type = "comment", a.comment = r, a;
  let c = r.split(/  +|\t+/).filter((T) => T !== "");
  if (c.length === 0) return a;
  const g = c[c.length - 1], s = ss.exec(g);
  if (s && (a.rbs_id = s[1], c.pop(), c.length === 0))
    return a;
  const _ = c.findIndex(dm);
  if (_ >= 0 && (a.trailingComment = c.slice(_).join(" "), c = c.slice(0, _), c.length === 0))
    return a.type = "comment", a.comment = a.trailingComment, a.trailingComment = void 0, a;
  const b = c[0];
  if (b === "FOR") {
    a.type = "for";
    let T = -1;
    for (let B = 1; B < c.length; B++)
      if (ta.includes(c[B])) {
        T = B;
        break;
      }
    if (T > 0) {
      const B = c.slice(1, T);
      let I = c[T], W = T + 1;
      const re = c[W];
      I === "IN" && re && ["ENUMERATE", "RANGE", "ZIP"].includes(re) && (I = `IN ${re}`, W += 1), a.loopVars = B, a.loopVar = B[0] || "${item}", a.loopFlavor = I, a.loopValues = c.slice(W);
    } else
      a.loopVar = c[1] || "${item}", a.loopFlavor = "IN", a.loopValues = c.slice(3);
    return a;
  }
  if (b === "END")
    return a.type = "end", a;
  if (b === "IF")
    return a.type = "if", a.condition = c.slice(1).join("    "), a;
  if (b === "ELSE IF")
    return a.type = "else_if", a.condition = c.slice(1).join("    "), a;
  if (b === "ELSE")
    return a.type = "else", a;
  if (b === "WHILE")
    return a.type = "while", a.condition = c.slice(1).join("    "), a;
  if (b === "TRY")
    return a.type = "try", a;
  if (b === "EXCEPT") {
    a.type = "except";
    const T = c.indexOf("AS");
    return T >= 1 ? (a.exceptPattern = c.slice(1, T).join("    "), a.exceptVar = c[T + 1] || "") : a.exceptPattern = c.slice(1).join("    "), a;
  }
  if (b === "FINALLY")
    return a.type = "finally", a;
  if (b === "BREAK")
    return a.type = "break", a;
  if (b === "CONTINUE")
    return a.type = "continue", a;
  if (b === "RETURN")
    return a.type = "return", a.args = c.slice(1), a;
  if (b === "VAR") {
    a.type = "var", a.returnVars = c[1] ? [c[1]] : ["${var}"];
    const T = c.slice(2), B = T.findIndex((I) => /^scope=/i.test(I));
    return B >= 0 ? (a.varScope = T[B].replace(/^scope=/i, ""), a.args = T.slice(0, B)) : a.args = T, a;
  }
  const k = /^[$@&%]\{[^}]+\}\s*=?$/, V = [];
  let L = 0;
  for (let T = 0; T < c.length; T++) {
    const B = c[T].trim();
    if (k.test(B)) {
      const I = B.replace(/\s*=$/, "");
      if (V.push(I), B.endsWith("=")) {
        L = T + 1;
        break;
      }
      if (T + 1 < c.length && c[T + 1].trim() === "=") {
        L = T + 2;
        break;
      }
    } else if (B === "=" && V.length > 0) {
      L = T + 1;
      break;
    } else {
      L = T;
      break;
    }
  }
  return V.length > 0 && L < c.length ? (a.type = "assignment", a.returnVars = V, a.keyword = c[L], a.args = c.slice(L + 1).map($n), a) : (a.type = "keyword", a.keyword = c[0], a.args = c.slice(1).map($n), a);
}
function fm(e) {
  const a = (g) => {
    let s = g;
    return e.rbs_id && (s += `${pe}# rbs:${e.rbs_id}`), e.trailingComment && (s += `${pe}${e.trailingComment}`), s;
  }, r = e.args.map(as);
  let c;
  switch (e.type) {
    case "keyword":
      c = [e.keyword, ...r].filter(Boolean).join(pe);
      break;
    case "assignment": {
      c = [...e.returnVars.map(
        (s, _) => _ === e.returnVars.length - 1 ? s + "=" : s
      ), e.keyword, ...r].filter(Boolean).join(pe);
      break;
    }
    case "for": {
      c = ["FOR", ...e.loopVars && e.loopVars.length > 0 ? e.loopVars : [e.loopVar], e.loopFlavor, ...e.loopValues].filter(Boolean).join(pe);
      break;
    }
    case "end":
      c = "END";
      break;
    case "if":
      c = ["IF", e.condition].filter(Boolean).join(pe);
      break;
    case "else_if":
      c = ["ELSE IF", e.condition].filter(Boolean).join(pe);
      break;
    case "else":
      c = "ELSE";
      break;
    case "while":
      c = ["WHILE", e.condition].filter(Boolean).join(pe);
      break;
    case "try":
      c = "TRY";
      break;
    case "except": {
      const g = ["EXCEPT"];
      e.exceptPattern && g.push(e.exceptPattern), e.exceptVar && g.push("AS", e.exceptVar), c = g.join(pe);
      break;
    }
    case "finally":
      c = "FINALLY";
      break;
    case "break":
      c = "BREAK";
      break;
    case "continue":
      c = "CONTINUE";
      break;
    case "return":
      c = ["RETURN", ...e.args].filter(Boolean).join(pe);
      break;
    case "var": {
      const g = ["VAR", e.returnVars[0] || "${var}", ...e.args];
      e.varScope && g.push("scope=" + e.varScope), c = g.filter(Boolean).join(pe);
      break;
    }
    case "comment":
      return e.comment || "# ";
    default:
      return "";
  }
  return a(c);
}
function mm(e) {
  return /(^|[\\/])__init__\.robot$/i.test(e ?? "");
}
function gm(e) {
  return e.split(`
`).some((a) => /^﻿?\*+[ ]*(Test Cases?|Tasks?)[ ]*\**/i.test(a.trim()));
}
const ls = /^﻿?\*+[ ]*(Settings?|Variables?|Test Cases?|Tasks?|Keywords?)[ ]*\**/i;
function vm(e) {
  var L, T;
  const a = e.split(`
`), r = {
    settings: [],
    variables: [],
    testCases: [],
    keywords: [],
    preambleLines: []
  };
  let c = "none", g = null, s = null, _ = [], b = !1, k = null;
  const V = () => {
    g && s && (s === "testcase" ? r.testCases.push(g) : r.keywords.push(g)), g = null, s = null, k = null;
  };
  for (let B = 0; B < a.length; B++) {
    const I = a[B], W = I.trimEnd(), re = W.match(ls);
    if (re) {
      V();
      const x = re[1].toLowerCase();
      x.startsWith("setting") ? c = "settings" : x.startsWith("variable") ? c = "variables" : x.startsWith("test") || x.startsWith("task") ? c = "testcases" : x.startsWith("keyword") && (c = "keywords");
      continue;
    }
    if (c === "none") {
      r.preambleLines.push(W);
      continue;
    }
    if (c === "settings") {
      if (!W || W.startsWith("#")) {
        W.startsWith("#") && r.settings.push({ key: "#", value: W, args: [] });
        continue;
      }
      if (W.startsWith("...")) {
        const Z = W.slice(3).trim(), Q = r.settings[r.settings.length - 1];
        Q && Q.key !== "#" && (Q.value = Q.value ? `${Q.value}
${Z}` : Z);
        continue;
      }
      const x = W.split(/  +|\t+/).filter((Z) => Z !== "");
      x.length >= 1 && r.settings.push({ key: x[0], value: x[1] || "", args: x.slice(2) });
      continue;
    }
    if (c === "variables") {
      if (!W || W.startsWith("#")) {
        W.startsWith("#") && r.variables.push({ name: "#", value: W });
        continue;
      }
      if (W.startsWith("...")) {
        const Z = W.slice(3).trim(), Q = r.variables[r.variables.length - 1];
        Q && Q.name !== "#" && (Q.value = Q.value ? `${Q.value}${pe}${Z}` : Z);
        continue;
      }
      const x = W.split(/  +|\t+/).filter((Z) => Z !== "");
      x.length >= 1 && r.variables.push({ name: x[0], value: x.slice(1).join(pe) });
      continue;
    }
    if (c === "testcases" || c === "keywords") {
      if (!W) continue;
      const x = /^\s/.test(I) || I.startsWith("	");
      if (!x && W.startsWith("#")) {
        _.push(W);
        continue;
      }
      if (!x && W) {
        V(), b = !1, c === "testcases" ? (g = {
          name: W,
          documentation: "",
          tags: [],
          setup: "",
          teardown: "",
          timeout: "",
          template: "",
          steps: []
        }, s = "testcase", b = cm(a, B + 1)) : (g = {
          name: W,
          documentation: "",
          arguments: [],
          tags: [],
          setup: "",
          teardown: "",
          timeout: "",
          returnValue: "",
          steps: []
        }, s = "keyword"), _.length && (g.leadingComments = _, _ = []);
        continue;
      }
      if (g && x) {
        const Z = W.trim(), Q = s === "testcase" ? g : null, G = !!Q && (!!Q.template || b);
        if (Z.startsWith("...")) {
          const j = Z.slice(3).trim().split(/  +|\t+/).filter((D) => D !== "");
          if (G && Q.templateRows && Q.templateRows.length > 0 && g.steps.length === 0) {
            Q.templateRows[Q.templateRows.length - 1].push(...j.map(Sa));
            continue;
          }
          if (k) {
            const D = j.join("    ");
            if (k === "documentation") {
              const se = j.join(" ");
              g.documentation = g.documentation ? g.documentation + `
` + se : se;
            } else if (k === "tags")
              g.tags.push(...j);
            else if (k === "arguments")
              "arguments" in g && g.arguments.push(...j);
            else if (k === "return") {
              if ("returnValue" in g) {
                const se = g;
                se.returnValue = se.returnValue ? se.returnValue + pe + D : D;
              }
            } else {
              const se = k === "setup" ? "setup" : k === "teardown" ? "teardown" : k === "timeout" ? "timeout" : k === "template" ? "template" : "";
              if (se === "template") {
                if ("template" in g) {
                  const oe = g;
                  oe.template = oe.template ? oe.template + pe + D : D;
                }
              } else if (se) {
                const oe = g;
                oe[se] = oe[se] ? oe[se] + pe + D : D;
              }
            }
            continue;
          }
          if (g.steps.length > 0) {
            const D = g.steps[g.steps.length - 1];
            switch (D.type) {
              case "keyword":
              case "assignment":
              case "return":
                D.args.push(...j.map($n));
                break;
              case "for":
                D.loopValues.push(...j);
                break;
              default:
                D.condition !== void 0 && j.length && (D.condition += pe + j.join(pe));
            }
          } else g.documentation && (g.documentation += `
` + j.join(" "));
          continue;
        }
        const R = Z.match(/^\[(Documentation|Tags|Setup|Teardown|Timeout|Template|Arguments|Return)\]\s*(.*)/i);
        if (R) {
          const j = R[1].toLowerCase(), D = ((L = R[2]) == null ? void 0 : L.trim()) || "";
          switch (j) {
            case "documentation":
              g.documentation = D;
              break;
            case "tags":
              g.tags = D ? D.split(/  +|\t+/).map((se) => se.trim()).filter(Boolean) : [];
              break;
            case "setup":
              g.setup = D;
              break;
            case "teardown":
              g.teardown = D;
              break;
            case "timeout":
              g.timeout = D;
              break;
            case "template":
              "template" in g && (g.template = D);
              break;
            case "arguments":
              "arguments" in g && (g.arguments = D ? D.split(/  +|\t+/).filter(Boolean) : []);
              break;
            case "return":
              "returnValue" in g && (g.returnValue = D);
              break;
          }
          k = j;
          continue;
        }
        if (G && !um(Z)) {
          const j = Z.split(/  +|\t+/).filter((D) => D !== "").map(Sa);
          Q.templateRows || (Q.templateRows = []), Q.templateRows.push(j), k = null;
          continue;
        }
        const P = pm(Z);
        P._lineNumber = B + 1;
        const K = (((T = I.match(/^[ \t]+/)) == null ? void 0 : T[0]) ?? "").replace(/\t/g, "    ");
        P.depth = Math.max(0, Math.floor(K.length / 4) - 1), g.steps.push(P), k = null;
      }
      continue;
    }
  }
  return V(), _.length && (r.trailingComments = _), r;
}
function La(e, a) {
  const r = a.split(`
`), c = [];
  c.push(pe + e + pe + r[0]);
  for (let g = 1; g < r.length; g++)
    c.push(pe + "..." + pe + r[g]);
  return c;
}
function hm(e, a = {}) {
  const r = a.isResource ?? !1, c = [];
  for (const b of e.preambleLines) c.push(b);
  if (e.settings.length > 0) {
    c.length > 0 && c[c.length - 1] !== "" && c.push(""), c.push("*** Settings ***");
    for (const b of e.settings) {
      if (b.key === "#") {
        c.push(b.value);
        continue;
      }
      if (b.value && b.value.includes(`
`)) {
        const V = b.value.split(`
`);
        let L = b.key + pe + V[0];
        for (const T of b.args) L += pe + T;
        c.push(L);
        for (let T = 1; T < V.length; T++) c.push("..." + pe + V[T]);
        continue;
      }
      let k = b.key;
      b.value && (k += pe + b.value);
      for (const V of b.args) k += pe + V;
      c.push(k);
    }
  }
  if (e.variables.length > 0) {
    c.length > 0 && c[c.length - 1] !== "" && c.push(""), c.push("*** Variables ***");
    for (const b of e.variables) {
      if (b.name === "#") {
        c.push(b.value);
        continue;
      }
      c.push(b.value ? b.name + pe + b.value : b.name);
    }
  }
  const g = (b) => {
    if (b.leadingComments) for (const k of b.leadingComments) c.push(k);
  }, s = (b) => {
    const k = pe.repeat((b.depth ?? 0) + 1);
    c.push(k + fm(b));
  };
  if (!r && e.testCases.length > 0) {
    c.length > 0 && c[c.length - 1] !== "" && c.push(""), c.push("*** Test Cases ***");
    for (let b = 0; b < e.testCases.length; b++) {
      const k = e.testCases[b];
      g(k);
      const V = k.name.trim() || `Test Case ${b + 1}`;
      if (c.push(V), k.documentation && c.push(...La("[Documentation]", k.documentation)), k.tags.length > 0 && c.push(pe + "[Tags]" + pe + k.tags.join(pe)), k.setup && c.push(pe + "[Setup]" + pe + k.setup), k.teardown && c.push(pe + "[Teardown]" + pe + k.teardown), k.timeout && c.push(pe + "[Timeout]" + pe + k.timeout), k.template && c.push(pe + "[Template]" + pe + k.template), k.templateRows)
        for (const L of k.templateRows)
          c.push(pe + L.map(rm).join(pe));
      for (const L of k.steps) s(L);
      c.push("");
    }
  }
  if (e.keywords.length > 0) {
    c.length > 0 && c[c.length - 1] !== "" && c.push(""), c.push("*** Keywords ***");
    for (let b = 0; b < e.keywords.length; b++) {
      const k = e.keywords[b];
      g(k);
      const V = k.name.trim() || `Keyword ${b + 1}`;
      c.push(V), k.arguments.length && c.push(pe + "[Arguments]" + pe + k.arguments.join(pe)), k.documentation && c.push(...La("[Documentation]", k.documentation)), k.tags.length > 0 && c.push(pe + "[Tags]" + pe + k.tags.join(pe)), k.setup && c.push(pe + "[Setup]" + pe + k.setup), k.teardown && c.push(pe + "[Teardown]" + pe + k.teardown), k.timeout && c.push(pe + "[Timeout]" + pe + k.timeout), k.returnValue && c.push(pe + "[Return]" + pe + k.returnValue);
      for (const L of k.steps) s(L);
      c.push("");
    }
  }
  if (e.trailingComments) for (const b of e.trailingComments) c.push(b);
  let _ = c.join(`
`);
  return _ = _.replace(/\n{3,}/g, `

`), _.endsWith(`
`) || (_ += `
`), _;
}
function ym(e) {
  const a = /* @__PURE__ */ new Set(), r = /\s+# rbs:([a-f0-9]{8,32})\s*$/gm;
  let c;
  for (; (c = r.exec(e)) !== null; ) a.add(c[1]);
  return a;
}
const bm = { class: "spec-tabs" }, wm = { class: "tab-buttons" }, _m = { class: "tab-toolbar" }, km = { class: "badge badge-robot" }, Cm = {
  key: 0,
  class: "badge badge-info"
}, $m = {
  key: 1,
  class: "badge badge-info"
}, Tm = ["title"], Em = ["title"], Sm = ["title"], Lm = ["title"], Vm = {
  key: 1,
  class: "parse-error-banner"
}, Am = { class: "editor-section section-settings" }, Nm = { class: "collapse-icon" }, Rm = { class: "section-count" }, Fm = { class: "section-body" }, Pm = {
  key: 0,
  class: "empty-hint"
}, Dm = ["onUpdate:modelValue"], Mm = ["onClick", "title"], xm = ["onUpdate:modelValue"], Um = ["value"], Bm = {
  key: 0,
  class: "keyword-autocomplete-wrapper flex-1"
}, Km = ["onUpdate:modelValue", "placeholder", "onFocus", "onKeydown", "onInput"], Hm = ["onMousedown"], Om = { class: "kw-suggestion-name" }, Im = {
  key: 1,
  class: "form-hl-wrap flex-1"
}, Wm = ["innerHTML"], jm = ["onUpdate:modelValue", "placeholder"], qm = {
  key: 2,
  class: "setting-args"
}, zm = ["onUpdate:modelValue", "placeholder"], Gm = ["onClick"], Ym = ["onClick"], Xm = ["onClick", "title"], Zm = { class: "editor-section section-variables" }, Qm = { class: "collapse-icon" }, Jm = { class: "section-count" }, eg = { class: "section-body" }, tg = {
  key: 0,
  class: "empty-hint"
}, og = ["onUpdate:modelValue"], ng = ["onClick", "title"], ag = { class: "form-hl-wrap hl-var-name" }, sg = ["innerHTML"], lg = ["onUpdate:modelValue", "placeholder", "onBlur"], rg = { class: "form-hl-wrap flex-1" }, ig = ["innerHTML"], ug = ["onUpdate:modelValue", "placeholder"], cg = ["onClick", "title"], dg = {
  key: 0,
  class: "editor-section section-testcases"
}, pg = { class: "collapse-icon" }, fg = { class: "section-count" }, mg = { class: "section-body" }, gg = {
  key: 0,
  class: "empty-hint"
}, vg = ["onClick"], hg = { class: "collapse-icon" }, yg = { class: "item-title" }, bg = { class: "item-count" }, wg = ["onClick", "title"], _g = { class: "item-body" }, kg = { class: "form-group" }, Cg = { class: "form-label" }, $g = ["onUpdate:modelValue", "placeholder"], Tg = { class: "form-group" }, Eg = { class: "form-label" }, Sg = ["onUpdate:modelValue", "placeholder"], Lg = {
  key: 0,
  class: "form-group"
}, Vg = { class: "form-label" }, Ag = ["onClick"], Ng = { class: "chips-container" }, Rg = ["onClick"], Fg = { class: "chip-input-wrapper" }, Pg = ["value", "onInput", "placeholder", "onKeydown"], Dg = ["onClick"], Mg = { class: "meta-toggles" }, xg = ["onClick"], Ug = ["onClick"], Bg = ["onClick"], Kg = ["onClick"], Hg = ["onClick"], Og = {
  key: 1,
  class: "form-group"
}, Ig = { class: "form-label" }, Wg = ["onClick"], jg = { class: "setup-teardown-row" }, qg = { class: "keyword-autocomplete-wrapper" }, zg = ["value", "onInput", "placeholder", "onFocus", "onKeydown"], Gg = ["onMousedown"], Yg = { class: "kw-suggestion-name" }, Xg = {
  key: 0,
  class: "kw-suggestion-args"
}, Zg = {
  key: 0,
  class: "step-args-group"
}, Qg = {
  key: 0,
  class: "arg-label"
}, Jg = ["innerHTML"], ev = ["value", "onInput", "placeholder"], tv = ["onClick"], ov = ["onClick", "title"], nv = {
  key: 2,
  class: "form-group"
}, av = { class: "form-label" }, sv = ["onClick"], lv = { class: "setup-teardown-row" }, rv = { class: "keyword-autocomplete-wrapper" }, iv = ["value", "onInput", "placeholder", "onFocus", "onKeydown"], uv = ["onMousedown"], cv = { class: "kw-suggestion-name" }, dv = {
  key: 0,
  class: "kw-suggestion-args"
}, pv = {
  key: 0,
  class: "step-args-group"
}, fv = {
  key: 0,
  class: "arg-label"
}, mv = ["innerHTML"], gv = ["value", "onInput", "placeholder"], vv = ["onClick"], hv = ["onClick", "title"], yv = {
  key: 3,
  class: "form-row"
}, bv = {
  key: 0,
  class: "form-group flex-1"
}, wv = { class: "form-label" }, _v = ["onClick"], kv = { class: "form-hl-wrap" }, Cv = ["innerHTML"], $v = ["onUpdate:modelValue", "placeholder"], Tv = {
  key: 1,
  class: "form-group flex-1"
}, Ev = { class: "form-label" }, Sv = ["onClick"], Lv = { class: "form-hl-wrap" }, Vv = ["innerHTML"], Av = ["onUpdate:modelValue", "placeholder"], Nv = { class: "form-group" }, Rv = { class: "form-label" }, Fv = { class: "steps-list" }, Pv = { class: "step-number" }, Dv = ["onUpdate:modelValue", "onChange"], Mv = ["value"], xv = { class: "keyword-autocomplete-wrapper" }, Uv = ["onUpdate:modelValue", "placeholder", "onFocus", "onKeydown", "onInput"], Bv = ["onMousedown"], Kv = { class: "kw-suggestion-name" }, Hv = {
  key: 0,
  class: "kw-suggestion-args"
}, Ov = {
  key: 0,
  class: "step-args-group"
}, Iv = {
  key: 0,
  class: "arg-label"
}, Wv = ["innerHTML"], jv = ["onUpdate:modelValue", "placeholder", "onInput", "onKeydown"], qv = {
  key: 1,
  class: "arg-ac-dropdown"
}, zv = ["onMousedown"], Gv = ["onClick"], Yv = ["onClick", "title"], Xv = ["onUpdate:modelValue", "onBlur"], Zv = ["onClick"], Qv = ["onClick"], Jv = { class: "keyword-autocomplete-wrapper" }, eh = ["onUpdate:modelValue", "placeholder", "onFocus", "onKeydown", "onInput"], th = ["onMousedown"], oh = { class: "kw-suggestion-name" }, nh = {
  key: 0,
  class: "kw-suggestion-args"
}, ah = {
  key: 0,
  class: "step-args-group"
}, sh = {
  key: 0,
  class: "arg-label"
}, lh = ["innerHTML"], rh = ["onUpdate:modelValue", "placeholder", "onInput", "onKeydown"], ih = {
  key: 1,
  class: "arg-ac-dropdown"
}, uh = ["onMousedown"], ch = ["onClick"], dh = ["onClick", "title"], ph = { class: "step-var-chip" }, fh = ["onUpdate:modelValue", "onBlur"], mh = ["innerHTML"], gh = ["onUpdate:modelValue", "placeholder"], vh = ["onClick"], hh = ["onClick", "title"], yh = ["onUpdate:modelValue"], bh = ["value"], wh = { class: "form-hl-wrap hl-var-inline" }, _h = ["innerHTML"], kh = ["onUpdate:modelValue"], Ch = ["onUpdate:modelValue"], $h = ["value"], Th = ["onUpdate:modelValue", "placeholder"], Eh = ["onClick"], Sh = ["onClick", "title"], Lh = {
  key: 4,
  class: "form-hl-wrap flex-1 hl-condition-wrap"
}, Vh = ["innerHTML"], Ah = ["onUpdate:modelValue", "placeholder"], Nh = ["onUpdate:modelValue", "placeholder"], Rh = {
  key: 0,
  class: "step-as-label"
}, Fh = {
  key: 1,
  class: "form-hl-wrap hl-var-inline"
}, Ph = ["innerHTML"], Dh = ["onUpdate:modelValue"], Mh = ["onUpdate:modelValue", "placeholder"], xh = ["onClick"], Uh = ["onClick", "title"], Bh = ["onUpdate:modelValue"], Kh = { class: "step-actions" }, Hh = ["onClick", "disabled"], Oh = ["onClick", "disabled"], Ih = ["onClick", "title"], Wh = { class: "add-step-bar" }, jh = ["onClick"], qh = ["onClick"], zh = ["onClick"], Gh = ["onClick"], Yh = ["onClick"], Xh = ["onClick"], Zh = { class: "editor-section section-keywords" }, Qh = { class: "collapse-icon" }, Jh = { class: "section-count" }, ey = { class: "section-body" }, ty = {
  key: 0,
  class: "empty-hint"
}, oy = ["onClick"], ny = { class: "collapse-icon" }, ay = { class: "item-title" }, sy = { class: "item-count" }, ly = ["onClick", "title"], ry = { class: "item-body" }, iy = { class: "form-group" }, uy = { class: "form-label" }, cy = ["onUpdate:modelValue", "placeholder"], dy = { class: "form-group" }, py = { class: "form-label" }, fy = { class: "kw-args-list" }, my = { class: "kw-arg-sep-label" }, gy = { class: "kw-arg-type-badge badge-varargs" }, vy = ["value", "onInput", "onBlur"], hy = { class: "kw-arg-type-badge badge-kwargs" }, yy = ["value", "onInput", "onBlur"], by = {
  key: 0,
  class: "kw-arg-type-badge badge-named"
}, wy = {
  key: 1,
  class: "kw-arg-type-badge badge-optional"
}, _y = {
  key: 2,
  class: "kw-arg-type-badge badge-required"
}, ky = ["value", "onInput", "onBlur", "placeholder"], Cy = { class: "form-hl-wrap kw-default-wrap" }, $y = ["innerHTML"], Ty = ["value", "onInput", "placeholder"], Ey = ["onClick", "title"], Sy = ["onClick"], Ly = { class: "kw-arg-add-buttons" }, Vy = ["onClick"], Ay = ["onClick"], Ny = ["disabled", "onClick"], Ry = ["disabled", "onClick"], Fy = ["onClick"], Py = { class: "form-group" }, Dy = { class: "form-label" }, My = ["onUpdate:modelValue", "placeholder"], xy = {
  key: 0,
  class: "form-group"
}, Uy = { class: "form-label" }, By = ["onClick"], Ky = { class: "chips-container" }, Hy = ["onClick"], Oy = { class: "chip-input-wrapper" }, Iy = ["value", "onInput", "placeholder", "onKeydown"], Wy = ["onClick"], jy = { class: "meta-toggles" }, qy = ["onClick"], zy = ["onClick"], Gy = ["onClick"], Yy = ["onClick"], Xy = ["onClick"], Zy = {
  key: 1,
  class: "form-group"
}, Qy = { class: "form-label" }, Jy = ["onClick"], eb = { class: "setup-teardown-row" }, tb = { class: "keyword-autocomplete-wrapper" }, ob = ["value", "onInput", "placeholder", "onFocus", "onKeydown"], nb = ["onMousedown"], ab = { class: "kw-suggestion-name" }, sb = {
  key: 0,
  class: "kw-suggestion-args"
}, lb = {
  key: 0,
  class: "step-args-group"
}, rb = {
  key: 0,
  class: "arg-label"
}, ib = ["innerHTML"], ub = ["value", "onInput", "placeholder"], cb = ["onClick"], db = ["onClick", "title"], pb = {
  key: 2,
  class: "form-group"
}, fb = { class: "form-label" }, mb = ["onClick"], gb = { class: "setup-teardown-row" }, vb = { class: "keyword-autocomplete-wrapper" }, hb = ["value", "onInput", "placeholder", "onFocus", "onKeydown"], yb = ["onMousedown"], bb = { class: "kw-suggestion-name" }, wb = {
  key: 0,
  class: "kw-suggestion-args"
}, _b = {
  key: 0,
  class: "step-args-group"
}, kb = {
  key: 0,
  class: "arg-label"
}, Cb = ["innerHTML"], $b = ["value", "onInput", "placeholder"], Tb = ["onClick"], Eb = ["onClick", "title"], Sb = {
  key: 3,
  class: "form-row"
}, Lb = {
  key: 0,
  class: "form-group flex-1"
}, Vb = { class: "form-label" }, Ab = ["onClick"], Nb = { class: "form-hl-wrap" }, Rb = ["innerHTML"], Fb = ["onUpdate:modelValue", "placeholder"], Pb = {
  key: 1,
  class: "form-group flex-1"
}, Db = { class: "form-label" }, Mb = ["onClick"], xb = { class: "form-hl-wrap" }, Ub = ["innerHTML"], Bb = ["onUpdate:modelValue", "placeholder"], Kb = { class: "form-group" }, Hb = { class: "form-label" }, Ob = { class: "steps-list" }, Ib = { class: "step-number" }, Wb = ["onUpdate:modelValue", "onChange"], jb = ["value"], qb = { class: "keyword-autocomplete-wrapper" }, zb = ["onUpdate:modelValue", "placeholder", "onFocus", "onKeydown", "onInput"], Gb = ["onMousedown"], Yb = { class: "kw-suggestion-name" }, Xb = {
  key: 0,
  class: "kw-suggestion-args"
}, Zb = {
  key: 0,
  class: "step-args-group"
}, Qb = {
  key: 0,
  class: "arg-label"
}, Jb = ["innerHTML"], ew = ["onUpdate:modelValue", "placeholder", "onInput", "onKeydown"], tw = {
  key: 1,
  class: "arg-ac-dropdown"
}, ow = ["onMousedown"], nw = ["onClick"], aw = ["onClick", "title"], sw = ["onUpdate:modelValue", "onBlur"], lw = ["onClick"], rw = ["onClick"], iw = { class: "keyword-autocomplete-wrapper" }, uw = ["onUpdate:modelValue", "placeholder", "onFocus", "onKeydown", "onInput"], cw = ["onMousedown"], dw = { class: "kw-suggestion-name" }, pw = {
  key: 0,
  class: "kw-suggestion-args"
}, fw = {
  key: 0,
  class: "step-args-group"
}, mw = {
  key: 0,
  class: "arg-label"
}, gw = ["innerHTML"], vw = ["onUpdate:modelValue", "placeholder", "onInput", "onKeydown"], hw = {
  key: 1,
  class: "arg-ac-dropdown"
}, yw = ["onMousedown"], bw = ["onClick"], ww = ["onClick", "title"], _w = { class: "step-var-chip" }, kw = ["onUpdate:modelValue", "onBlur"], Cw = ["innerHTML"], $w = ["onUpdate:modelValue", "placeholder"], Tw = ["onClick"], Ew = ["onClick", "title"], Sw = ["onUpdate:modelValue"], Lw = ["value"], Vw = { class: "form-hl-wrap hl-var-inline" }, Aw = ["innerHTML"], Nw = ["onUpdate:modelValue"], Rw = ["onUpdate:modelValue"], Fw = ["value"], Pw = ["onUpdate:modelValue", "placeholder"], Dw = ["onClick"], Mw = ["onClick", "title"], xw = {
  key: 4,
  class: "form-hl-wrap flex-1 hl-condition-wrap"
}, Uw = ["innerHTML"], Bw = ["onUpdate:modelValue", "placeholder"], Kw = ["onUpdate:modelValue", "placeholder"], Hw = {
  key: 0,
  class: "step-as-label"
}, Ow = {
  key: 1,
  class: "form-hl-wrap hl-var-inline"
}, Iw = ["innerHTML"], Ww = ["onUpdate:modelValue"], jw = ["onUpdate:modelValue", "placeholder"], qw = ["onClick"], zw = ["onClick", "title"], Gw = ["onUpdate:modelValue"], Yw = { class: "step-actions" }, Xw = ["onClick", "disabled"], Zw = ["onClick", "disabled"], Qw = ["onClick", "title"], Jw = { class: "add-step-bar" }, e_ = ["onClick"], t_ = ["onClick"], o_ = ["onClick"], n_ = ["onClick"], a_ = ["onClick"], s_ = ["onClick"], l_ = {
  key: 2,
  class: "flow-tab-wrapper"
}, r_ = { class: "lib-install-intro" }, i_ = { class: "lib-install-pkg-label" }, u_ = ["disabled"], c_ = { class: "lib-install-pkg-hint" }, d_ = {
  key: 0,
  class: "lib-install-error",
  role: "alert"
}, p_ = /* @__PURE__ */ qe({
  __name: "RobotEditor",
  props: {
    content: {},
    readonly: { type: Boolean },
    height: {}
  },
  emits: ["save", "update:content", "dirty"],
  setup(e, { expose: a, emit: r }) {
    const c = e, g = r, { t: s } = ft(), _ = Da(), b = N("code"), k = N(null), V = N(null), L = ps(null), T = new Es(), B = N(""), I = N(!1), W = N(!1), re = N(!1), x = N(!1), Z = N(/* @__PURE__ */ new Set()), Q = N(/* @__PURE__ */ new Set());
    function G() {
      I.value = !1, W.value = !1, re.value = !1, x.value = !1, Z.value.clear(), Q.value.clear();
    }
    function R() {
      I.value = !0, W.value = !0, re.value = !0, x.value = !0;
      for (let f = 0; f < ee.testCases.length; f++) Z.value.add(f);
      for (let f = 0; f < ee.keywords.length; f++) Q.value.add(f);
    }
    const P = N(/* @__PURE__ */ new Set());
    function K(f, y, d, h) {
      return !!h || P.value.has(`${f}-${y}-${d}`);
    }
    function j(f, y, d) {
      const h = `${f}-${y}-${d}`;
      P.value.has(h) ? P.value.delete(h) : P.value.add(h);
    }
    const D = N(null), se = N(""), oe = N(-1), be = N(null), J = N([]), H = vn(/* @__PURE__ */ new Map());
    let ue = null;
    const ce = N([]), ge = N(-1), Le = N(null), $e = vn(/* @__PURE__ */ new Map());
    function Be(f) {
      if (!f.trim()) return { keyword: "", args: [] };
      const y = f.split(/  +|\t+/).map((d) => d.trim()).filter(Boolean);
      return { keyword: y[0] || "", args: y.slice(1) };
    }
    function Ge(f, y) {
      return [f, ...y].filter(Boolean).join("    ");
    }
    function ie(f, y, d, h) {
      const l = `${f}-${y}-${d}`;
      if (!$e.has(l)) {
        const E = Be(h[d]);
        $e.set(l, {
          type: "keyword",
          keyword: E.keyword,
          args: E.args,
          returnVars: [],
          condition: "",
          loopVar: "",
          loopFlavor: "",
          loopValues: [],
          exceptPattern: "",
          exceptVar: "",
          varScope: "",
          comment: ""
        });
      }
      return $e.get(l);
    }
    function Qe(f, y, d, h) {
      const l = `${f}-${y}-${d}`, E = $e.get(l);
      E && (h[d] = Ge(E.keyword, E.args));
    }
    function mt(f, y, d, h) {
      Qe(f, y, d, h);
      const l = ie(f, y, d, h);
      Wt(l);
    }
    function ut(f, y, d, h) {
      const l = ie(f, y, d, h);
      It(l);
    }
    function Je(f, y, d, h, l) {
      const E = ie(f, y, d, h);
      Dt(E, l), Qe(f, y, d, h);
    }
    function gt(f, y, d, h) {
      ie(f, y, d, h).args.push(""), Qe(f, y, d, h);
    }
    function Ye(f, y, d, h, l) {
      ie(f, y, d, h).args.splice(l, 1), Qe(f, y, d, h);
    }
    function Ke(f, y, d, h) {
      Qe(f, y, d, h);
    }
    const ee = vn({
      settings: [],
      variables: [],
      testCases: [],
      keywords: [],
      preambleLines: []
    }), de = O(() => {
      var f;
      return ((f = _.filePath) == null ? void 0 : f.toLowerCase().endsWith(".resource")) ?? !1;
    }), ne = O(() => ee.testCases.length), fe = O(() => ee.keywords.length), Ae = O(
      () => mm(_.filePath) && (ee.testCases.length > 0 || gm(B.value))
    );
    function De(f) {
      try {
        $e.clear();
        const y = vm(f);
        return ee.settings = y.settings, ee.variables = y.variables, ee.testCases = y.testCases, ee.keywords = y.keywords, ee.preambleLines = y.preambleLines, ee.trailingComments = y.trailingComments, k.value = null, !0;
      } catch (y) {
        return k.value = y instanceof Error ? y.message : "Failed to parse Robot Framework file", !1;
      }
    }
    function vt(f, y) {
      for (const d of f)
        (d.type === "keyword" || d.type === "assignment") && d.keyword.trim() && y.add(d.keyword.trim());
    }
    async function Zt() {
      var h;
      const f = /* @__PURE__ */ new Set();
      for (const l of ee.testCases) vt(l.steps, f);
      for (const l of ee.keywords) vt(l.steps, f);
      const y = [];
      for (const l of f) {
        const E = l.toLowerCase().trim(), t = E.includes(".") ? E.substring(E.indexOf(".") + 1).trim() : E;
        wo.has(E) || wo.has(t) || ee.keywords.some((n) => n.name.toLowerCase() === E || n.name.toLowerCase() === t) || H.has(E) || H.has(t) || y.push(l);
      }
      if (y.length === 0) return;
      const d = /* @__PURE__ */ new Set();
      for (const l of y) {
        const E = l.toLowerCase().trim();
        if (!d.has(E)) {
          d.add(E);
          try {
            const t = await Wo(l, _.scopeId ?? void 0);
            for (const n of t.results)
              if ((h = n.args) != null && h.length) {
                const v = n.name.toLowerCase().trim();
                H.set(v, n.args), v.includes(".") && H.set(v.substring(v.indexOf(".") + 1).trim(), n.args);
              }
          } catch {
          }
        }
      }
    }
    function Tt() {
      return hm(ee, { isResource: de.value });
    }
    function nt(f) {
      if (f !== b.value)
        if (f === "code")
          B.value = Tt(), b.value = "code", k.value = null, je(() => Et());
        else {
          if (b.value === "code") {
            const y = Qt();
            De(y), Ut();
          }
          b.value = f;
        }
    }
    function Qt() {
      var f;
      return ((f = L.value) == null ? void 0 : f.state.doc.toString()) ?? B.value;
    }
    function Et() {
      if (Ut(), !V.value) return;
      const f = Wn.create({
        doc: B.value,
        extensions: [
          // EDT-05 / FR-v0.3-16：只读时 CodeMirror 本身也拒绝键入。
          T.of([
            Wn.readOnly.of(!!c.readonly),
            Ko.editable.of(!c.readonly)
          ]),
          ks(),
          Cs(),
          $s(),
          Ss(),
          Ts.of([...Ls, ...Vs]),
          // 平台品牌高亮样式（EDT-07）；未覆盖的 tag 回落到 CodeMirror 默认配色。
          la(am, { fallback: !0 }),
          la(_s, { fallback: !0 }),
          new ws(nm()),
          Ko.updateListener.of((y) => {
            if (y.docChanged) {
              const d = y.state.doc.toString();
              B.value = d, Bt(d);
            }
          }),
          Ko.theme({
            "&": { height: "100%", fontSize: "13px" },
            ".cm-content": { fontFamily: "'Fira Code', 'Consolas', 'Monaco', monospace", padding: "8px 0" },
            ".cm-gutters": { background: "#f8f9fa", borderRight: "1px solid #e0e0e0", color: "#999" },
            ".cm-activeLine": { background: "rgba(60, 181, 161, 0.06)" },
            ".cm-activeLineGutter": { background: "rgba(60, 181, 161, 0.1)" }
          })
        ]
      });
      L.value = new Ko({ state: f, parent: V.value });
    }
    function Ut() {
      L.value && (L.value.destroy(), L.value = null);
    }
    let Jt = "", St = "";
    function Bt(f) {
      c.readonly || (g("update:content", f), g("dirty", f !== St));
    }
    function ht() {
      const f = Tt();
      Jt = f, Bt(f);
    }
    const Lt = O(() => b.value === "visual" || b.value === "flow");
    Se(() => ee.settings, () => {
      Lt.value && ht();
    }, { deep: !0 }), Se(() => ee.variables, () => {
      Lt.value && ht();
    }, { deep: !0 }), Se(() => ee.testCases, () => {
      Lt.value && ht();
    }, { deep: !0 }), Se(() => ee.keywords, () => {
      Lt.value && ht();
    }, { deep: !0 });
    const A = O(() => {
      const f = es(ee), y = Zc(ee);
      return f === 0 && y === 0 || !ts(ee) && !os(ee) ? "hidden" : f > 0 ? "on" : "off";
    }), w = Jn();
    function M() {
      const f = A.value;
      if (f === "hidden") return;
      const y = f === "on" ? "disable" : "enable", { form: d, changedKeywords: h } = Qc(ee, y);
      if (ee.settings = d.settings, ee.testCases = d.testCases, ee.keywords = d.keywords, b.value === "code") {
        const l = Tt();
        if (B.value = l, L.value) {
          const E = L.value.state.doc.toString();
          E !== l && L.value.dispatch({
            changes: { from: 0, to: E.length, insert: l }
          });
        }
        Bt(l);
      }
      if (h > 0) {
        const l = y === "enable" ? "robotEditor.heal.toastEnabled" : "robotEditor.heal.toastDisabled";
        w.success(s(l, { count: h }));
      }
    }
    const q = N(null), me = N(!1), Te = N(null);
    let et = 0;
    async function Kt() {
      if (q.value = null, me.value = !1, Te.value = null, _.scopeId == null || !_.filePath || !_.filePath.toLowerCase().endsWith(".robot")) return;
      const f = ++et, y = _.filePath, d = _.scopeId, h = await sm(d, y);
      f === et && (q.value = h, h && (Te.value = y));
    }
    function Xo(f) {
      f === q.value && (me.value = !0);
    }
    async function Fe(f) {
      if (!(!q.value || Te.value == null || _.scopeId == null)) {
        if (f != null && Array.isArray(q.value.commands)) {
          const y = ym(f), d = q.value.commands.length, h = q.value.commands.filter(
            (l) => !l.id || y.has(l.id)
          );
          h.length !== d && (q.value.commands = h, me.value = !0);
        }
        me.value && (await lm(_.scopeId, Te.value, q.value), me.value = !1);
      }
    }
    a({ saveSidecarIfDirty: Fe }), Se(() => [_.scopeId, _.filePath], Kt, { immediate: !0 });
    const Ht = Yo(), Vt = Ka(), At = na(), po = Jn();
    function Zo() {
      _.scopeId != null && Ht.refreshKeywords(_.scopeId).catch(() => {
      });
    }
    const $o = {
      // Default to `-batteries` for the install suggestion: it bundles
      // Playwright + Node.js, so the user doesn't need a separate Node
      // install on the env host. The plain `robotframework-browser`
      // requires `rfbrowser init` to download Node + Playwright first
      // and tends to surprise users on freshly-built envs. Both stay
      // in the providers list so `_isLibraryProvided` recognises
      // either as already-installed.
      browser: ["robotframework-browser-batteries", "robotframework-browser"],
      seleniumlibrary: ["robotframework-seleniumlibrary"],
      requestslibrary: ["robotframework-requests"],
      databaselibrary: ["robotframework-databaselibrary"],
      sshlibrary: ["robotframework-sshlibrary"],
      ftplibrary: ["robotframework-ftplibrary"],
      excellibrary: ["robotframework-excellibrary"],
      jsonlibrary: ["robotframework-jsonlibrary"],
      appiumlibrary: ["robotframework-appiumlibrary"],
      swinglibrary: ["robotframework-swinglibrary"],
      sikulilibrary: ["robotframework-sikulilibrary"],
      imaplibrary: ["robotframework-imaplibrary"],
      archivelibrary: ["robotframework-archivelibrary"],
      cryptolibrary: ["robotframework-crypto"],
      restinstance: ["RESTinstance"]
    };
    function Sn(f) {
      const y = f.toLowerCase(), d = $o[y];
      return (d == null ? void 0 : d[0]) ?? `robotframework-${y}`;
    }
    function eo(f, y) {
      const d = f.toLowerCase(), h = $o[d];
      if (!h) return !1;
      const l = new Set(y.map((E) => E.name.toLowerCase()));
      return h.some((E) => l.has(E.toLowerCase()));
    }
    const ct = N(!1), to = N(""), Ot = N(""), To = N(null), Qo = N(""), Nt = N(!1), oo = N(null);
    function Ln(f) {
      var t;
      if (_.scopeId == null) return;
      const y = Vt.getRepo(_.scopeId), d = (y == null ? void 0 : y.environment_id) ?? null, h = At.environments.find((n) => n.is_default), l = d ?? (h == null ? void 0 : h.id) ?? ((t = At.environments[0]) == null ? void 0 : t.id) ?? null;
      if (l == null) {
        po.warning(
          s("robotEditor.libraryNotInEnvTitle", { library: f }),
          s("robotEditor.libraryNoEnvAtAllBody", { library: f })
        );
        return;
      }
      const E = At.environments.find((n) => n.id === l);
      to.value = f, Ot.value = Sn(f), To.value = l, Qo.value = (E == null ? void 0 : E.name) ?? "", oo.value = null, ct.value = !0;
    }
    async function Vn() {
      if (To.value == null) return;
      const f = Ot.value.trim();
      if (f) {
        Nt.value = !0, oo.value = null;
        try {
          await Ba(To.value, { package_name: f }), Zo(), po.success(
            s("robotEditor.libraryInstallStartedTitle"),
            s("robotEditor.libraryInstallStartedBody", { package: f })
          ), ct.value = !1;
        } catch (y) {
          oo.value = ko(
            y,
            s("robotEditor.libraryInstallFailed")
          );
        } finally {
          Nt.value = !1;
        }
      }
    }
    function yt() {
      ct.value = !1;
    }
    async function Eo(f) {
      var n, v;
      if (_.scopeId == null) return;
      try {
        await Ht.refreshKeywords(_.scopeId);
      } catch {
        return;
      }
      if (!f) return;
      const y = f.toLowerCase();
      if (Ht.keywords.some(
        (F) => (F.library || "").toLowerCase() === y
      )) return;
      if (At.environments.length === 0)
        try {
          await At.fetchEnvironments();
        } catch {
        }
      const h = Vt.getRepo(_.scopeId), l = (h == null ? void 0 : h.environment_id) ?? null, E = At.environments.find((F) => F.is_default), t = l ?? (E == null ? void 0 : E.id) ?? ((n = At.environments[0]) == null ? void 0 : n.id) ?? null;
      if (t != null)
        try {
          const F = await Sl(t);
          if (eo(f, F)) {
            const X = ((v = $o[y]) == null ? void 0 : v.find(
              (C) => F.some((S) => S.name.toLowerCase() === C.toLowerCase())
            )) ?? "?";
            po.info(
              s("robotEditor.libraryAlreadyProvidedTitle", { library: f }),
              s("robotEditor.libraryAlreadyProvidedBody", {
                library: f,
                provider: X
              })
            );
            return;
          }
        } catch {
        }
      Ln(f);
    }
    Se(
      () => _.filePath,
      (f, y) => {
        !f || f === y || _.scopeId != null && Ht.preloadKeywords(_.scopeId).catch(() => {
        });
      }
    );
    const An = [
      "BuiltIn",
      "Collections",
      "String",
      "OperatingSystem",
      "Process",
      "DateTime",
      "XML",
      "Dialogs",
      "Screenshot",
      "Telnet",
      "Remote",
      "SeleniumLibrary",
      "Browser",
      "RequestsLibrary",
      "DatabaseLibrary",
      "SSHLibrary",
      "FTPLibrary",
      "ExcelLibrary",
      "JSONLibrary",
      "AppiumLibrary",
      "SwingLibrary",
      "SikuliLibrary",
      "ImapLibrary",
      "ArchiveLibrary",
      "CryptoLibrary",
      "RESTinstance"
    ], Rt = N(null), at = N(""), Ie = N(-1), Jo = N(null), So = O(() => {
      const f = at.value.toLowerCase().trim();
      return An.filter((y) => !f || y.toLowerCase().includes(f)).slice(0, 15);
    });
    function Ne(f) {
      Rt.value = f, at.value = ee.settings[f].value, Ie.value = -1;
    }
    function Lo(f) {
      var d;
      const y = f.relatedTarget;
      y && ((d = Jo.value) != null && d.contains(y)) || setTimeout(() => {
        Rt.value = null;
      }, 150);
    }
    function Me(f) {
      at.value = ee.settings[f].value, Ie.value = -1, Rt.value = f;
    }
    function Nn(f, y) {
      const d = So.value;
      if (f.key === "Enter") {
        f.preventDefault(), Ie.value >= 0 && Ie.value < d.length && en(y, d[Ie.value]);
        return;
      }
      d.length && (f.key === "ArrowDown" ? (f.preventDefault(), Rt.value = y, Ie.value = Math.min(Ie.value + 1, d.length - 1)) : f.key === "ArrowUp" ? (f.preventDefault(), Ie.value = Math.max(Ie.value - 1, 0)) : f.key === "Escape" && (Rt.value = null, Ie.value = -1));
    }
    function en(f, y) {
      ee.settings[f].value = y, Rt.value = null, Ie.value = -1, at.value = "";
    }
    const Rn = [
      "Documentation",
      "Library",
      "Resource",
      "Variables",
      "Suite Setup",
      "Suite Teardown",
      "Test Setup",
      "Test Teardown",
      "Test Template",
      "Test Timeout",
      "Force Tags",
      "Default Tags",
      "Metadata"
    ];
    function tn() {
      ee.settings.push({ key: "Library", value: "", args: [] });
    }
    function on(f) {
      ee.settings.splice(f, 1);
    }
    function fo(f) {
      ee.settings[f].args.push("");
    }
    function Fn(f, y) {
      ee.settings[f].args.splice(y, 1);
    }
    function Pn() {
      ee.variables.push({ name: "${NEW_VAR}", value: "" });
    }
    function Vo(f) {
      ee.variables.splice(f, 1);
    }
    function mo(f, y = "keyword") {
      f.push(Ho(y));
    }
    function st(f, y) {
      const d = Ho(y);
      f.push(d), f.push(Ho("keyword")), f.push(Ho("end"));
    }
    function nn(f, y) {
      f.splice(y, 1);
    }
    function no(f, y, d) {
      const h = y + d;
      if (h < 0 || h >= f.length) return;
      const l = f[y];
      f[y] = f[h], f[h] = l;
    }
    function bt(f) {
      f.args.push("");
    }
    function tt(f) {
      const y = ke(f);
      if (y.length === 0) return "+";
      const d = f.args.length;
      if (d < y.length)
        return "+ " + y[d];
      const h = y[y.length - 1];
      return h != null && h.startsWith("*") && !h.startsWith("**") ? "+ ..." : "+";
    }
    function Ue(f, y) {
      f.args.splice(y, 1);
    }
    function an(f) {
      f.returnVars.push("${var}");
    }
    function ao(f, y) {
      f.returnVars.splice(y, 1);
    }
    function Ao(f) {
      f.loopValues.push("");
    }
    function go(f, y) {
      f.loopValues.splice(y, 1);
    }
    function sn(f) {
      f.type === "keyword" ? f.returnVars = [] : f.type === "assignment" ? f.returnVars.length === 0 && (f.returnVars = ["${result}"]) : f.type === "for" ? (f.loopVar || (f.loopVar = "${item}"), f.loopFlavor || (f.loopFlavor = "IN")) : f.type === "var" ? (f.returnVars.length === 0 && (f.returnVars = ["${var}"]), f.args.length === 0 && (f.args = [""])) : f.type === "comment" && (f.comment || (f.comment = "# "));
    }
    function Ft(f) {
      return s("robotEditor.stepType." + f);
    }
    function ln(f) {
      switch (f) {
        case "Library":
          return "stype-library";
        case "Resource":
          return "stype-resource";
        case "Variables":
          return "stype-variables";
        case "Documentation":
          return "stype-doc";
        case "Suite Setup":
        case "Test Setup":
          return "stype-setup";
        case "Suite Teardown":
        case "Test Teardown":
          return "stype-teardown";
        case "Test Template":
        case "Test Timeout":
          return "stype-config";
        case "Force Tags":
        case "Default Tags":
          return "stype-tags";
        case "Metadata":
          return "stype-meta";
        default:
          return "";
      }
    }
    function rn(f) {
      switch (f) {
        case "keyword":
          return "type-keyword";
        case "assignment":
          return "type-assign";
        case "var":
          return "type-var";
        case "for":
        case "while":
          return "type-loop";
        case "if":
        case "else_if":
        case "else":
          return "type-condition";
        case "try":
        case "except":
        case "finally":
          return "type-error";
        case "end":
        case "break":
        case "continue":
        case "return":
          return "type-flow";
        case "comment":
          return "type-comment";
        default:
          return "";
      }
    }
    function Dn(f) {
      return ["for", "if", "else_if", "else", "while", "try", "except", "finally"].includes(f);
    }
    function lt(f) {
      return f === "end";
    }
    function Pt(f, y) {
      let d = 0;
      for (let h = 0; h < y; h++)
        Dn(f[h].type) && d++, lt(f[h].type) && (d = Math.max(0, d - 1));
      return lt(f[y].type) && (d = Math.max(0, d - 1)), ["else", "else_if", "except", "finally"].includes(f[y].type) && (d = Math.max(0, d - 1)), d;
    }
    const No = O(() => {
      const f = [];
      for (const y of wo.keys()) {
        const d = y.replace(/\b\w/g, (h) => h.toUpperCase());
        f.push({ name: d, source: "builtin", args: wo.get(y) });
      }
      return f;
    }), xe = O(() => {
      const f = se.value.toLowerCase().trim(), y = /* @__PURE__ */ new Set(), d = [];
      for (const h of J.value) {
        const l = h.name.toLowerCase();
        if (!y.has(l)) {
          y.add(l);
          const E = h.library || "project";
          if (d.push({ name: h.name, source: E, args: h.args }), E !== "project" && E !== "local" && E !== "builtin" && !E.includes("Library")) {
            const t = E + "." + h.name, n = t.toLowerCase();
            y.has(n) || (y.add(n), d.push({ name: t, source: E, args: h.args }));
          }
        }
      }
      for (const h of ee.keywords) {
        const l = h.name.toLowerCase();
        h.name && !y.has(l) && (y.add(l), d.push({ name: h.name, source: "local", args: h.arguments }));
      }
      for (const h of No.value) {
        const l = h.name.toLowerCase();
        y.has(l) || (y.add(l), d.push(h));
      }
      return d.filter((h) => {
        if (!f) return !0;
        const l = h.name.toLowerCase(), E = l.includes(".") ? l.substring(l.indexOf(".") + 1) : l;
        return l.includes(f) || E.includes(f);
      }).slice(0, 15);
    });
    function It(f) {
      D.value = f, se.value = f.keyword, oe.value = -1;
    }
    function dt(f) {
      var d;
      const y = f.relatedTarget;
      y && ((d = be.value) != null && d.contains(y)) || setTimeout(() => {
        D.value = null;
      }, 150);
    }
    function Wt(f) {
      se.value = f.keyword, oe.value = -1, D.value = f, Mn(f.keyword);
    }
    function Mn(f) {
      ue && clearTimeout(ue);
      const y = f.trim();
      if (y.length < 2 && !/[^\x00-\x7F]/.test(y)) {
        J.value = [];
        return;
      }
      ue = setTimeout(async () => {
        var d;
        try {
          const h = await Wo(f.trim(), _.scopeId ?? void 0);
          J.value = h.results.slice(0, 15);
          for (const l of J.value)
            (d = l.args) != null && d.length && H.set(l.name.toLowerCase().trim(), l.args);
        } catch {
          J.value = [];
        }
      }, 300);
    }
    function wt(f, y) {
      const d = xe.value;
      if (f.key === "Enter") {
        f.preventDefault(), oe.value >= 0 && oe.value < d.length && Dt(y, d[oe.value]);
        return;
      }
      d.length && (f.key === "ArrowDown" ? (f.preventDefault(), D.value = y, oe.value = Math.min(oe.value + 1, d.length - 1)) : f.key === "ArrowUp" ? (f.preventDefault(), oe.value = Math.max(oe.value - 1, 0)) : f.key === "Escape" && (D.value = null, oe.value = -1));
    }
    function Dt(f, y) {
      var l;
      if (f.keyword = y.name, (l = y.args) != null && l.length) {
        const E = y.name.toLowerCase().trim();
        H.set(E, y.args), E.includes(".") && H.set(E.substring(E.indexOf(".") + 1).trim(), y.args);
      }
      D.value = null, oe.value = -1, se.value = "";
      const h = ke(f).filter((E) => !E.endsWith("?") && !E.startsWith("*")).length;
      for (; f.args.length < h; )
        f.args.push("");
    }
    function vo() {
      const f = [];
      for (const y of ee.variables) y.name && f.push(y.name);
      for (const y of ee.keywords)
        for (const d of y.arguments)
          d && f.push(d.replace(/=.*$/, ""));
      return f;
    }
    function so(f, y) {
      if (Le.value = y, !f.includes("$")) {
        ce.value = [];
        return;
      }
      const d = f.match(/[$@&%]\{([^}]*)$/);
      if (!d) {
        ce.value = [];
        return;
      }
      const h = d[1].toLowerCase(), l = vo();
      ce.value = l.filter((E) => E.toLowerCase().includes(h)).slice(0, 8), ge.value = -1;
    }
    function _t(f, y, d) {
      const l = (f.args[y] || "").replace(/[$@&%]\{[^}]*$/, d + "}");
      f.args[y] = l.includes("}") ? l : d, ce.value = [], Le.value = null;
    }
    function ho(f, y, d) {
      ce.value.length && (f.key === "ArrowDown" ? (f.preventDefault(), ge.value = Math.min(ge.value + 1, ce.value.length - 1)) : f.key === "ArrowUp" ? (f.preventDefault(), ge.value = Math.max(ge.value - 1, 0)) : f.key === "Enter" && ge.value >= 0 ? (f.preventDefault(), _t(y, d, ce.value[ge.value])) : f.key === "Escape" && (ce.value = [], Le.value = null));
    }
    function lo() {
      setTimeout(() => {
        ce.value = [], Le.value = null;
      }, 200);
    }
    function Ve(f) {
      return f ? f.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/([$@&])\{([^}]+)\}/g, '<span class="hl-var">$1{$2}</span>').replace(/%\{([^}]+)\}/g, '<span class="hl-env">%{$1}</span>') : "";
    }
    function jt(f) {
      var h;
      const y = f.target, d = (h = y.parentElement) == null ? void 0 : h.querySelector(".arg-highlight-overlay, .form-hl-overlay");
      d && (d.scrollLeft = y.scrollLeft);
    }
    function rt(f) {
      const y = Math.max((f || "").length, 6);
      return Math.min(y, 30) + "ch";
    }
    function Ro(f, y) {
      let d = f.arguments[y];
      if (!d || (d = d.trim(), !d) || d === "@{}" || /^[$@&]\{[^}]*\}(=.*)?$/.test(d)) return;
      const h = d.startsWith("@{") ? "@{" : d.startsWith("&{") ? "&{" : "${";
      let l = d.replace(/^[$@&%]\{/, "").replace(/\}(=.*)?$/, ""), E = "";
      const t = d.match(/\}=(.*)$/);
      if (t)
        E = "=" + t[1];
      else {
        const n = l.indexOf("=");
        n > 0 && (E = l.substring(n), l = l.substring(0, n));
      }
      l = l.replace(/[$@&%{}]/g, "").trim(), l && (f.arguments[y] = h + l + "}" + E);
    }
    function xn(f) {
      if (!f.name || f.name === "#") return;
      let y = f.name.trim();
      if (!y || /^[$@&%]\{[^}]+\}$/.test(y)) return;
      let d = "${";
      y.startsWith("@") ? d = "@{" : y.startsWith("&") ? d = "&{" : y.startsWith("%") && (d = "%{"), y = y.replace(/^[$@&%]\{?/, "").replace(/\}$/, "").replace(/[$@&%{}]/g, "").trim(), y && (f.name = d + y + "}");
    }
    function yo(f, y) {
      let d = f.returnVars[y];
      if (!d || (d = d.trim(), !d) || /^[$@&%]\{[^}]+\}$/.test(d)) return;
      let h = "${";
      d.startsWith("@") ? h = "@{" : d.startsWith("&") ? h = "&{" : d.startsWith("%") && (h = "%{"), d = d.replace(/^[$@&%]\{?/, "").replace(/\}$/, "").replace(/[$@&%{}]/g, "").trim(), d && (f.returnVars[y] = h + d + "}");
    }
    function un(f) {
      if (f.startsWith("@{")) return "*" + f.replace(/^@\{([^}]*)\}.*$/, "$1");
      if (f.startsWith("&{")) return "**" + f.replace(/^&\{([^}]*)\}.*$/, "$1");
      const y = f.replace(/^[$@&%]\{([^}]+)\}.*$/, "$1").replace(/=.*$/, "");
      return f.includes("=") ? y + "?" : y;
    }
    function ke(f) {
      var h;
      const y = f.keyword.toLowerCase().trim(), d = y.includes(".") ? y.substring(y.indexOf(".") + 1).trim() : y;
      for (const l of [y, d]) {
        const E = wo.get(l);
        if (E) return E;
        const t = ee.keywords.find((v) => v.name.toLowerCase() === l);
        if ((h = t == null ? void 0 : t.arguments) != null && h.length)
          return t.arguments.filter((v) => v !== "@{}").map(un);
        const n = H.get(l);
        if (n != null && n.length)
          return n.filter((v) => v !== "@{}").map(un);
      }
      return [];
    }
    function Fo() {
      ee.testCases.push({
        name: "",
        documentation: "",
        tags: [],
        setup: "",
        teardown: "",
        timeout: "",
        template: "",
        steps: []
      });
    }
    function Un(f) {
      ee.testCases.splice(f, 1), Z.value.delete(f);
    }
    function qt(f) {
      Z.value.has(f) ? Z.value.delete(f) : Z.value.add(f);
    }
    const ro = N(/* @__PURE__ */ new Map());
    function Bn(f, y) {
      y.trim() && !ee.testCases[f].tags.includes(y.trim()) && ee.testCases[f].tags.push(y.trim());
    }
    function cn(f, y) {
      ee.testCases[f].tags.splice(y, 1);
    }
    function zt(f) {
      Bn(f, ro.value.get(f) || ""), ro.value.set(f, "");
    }
    function Po() {
      ee.keywords.push({
        name: "",
        documentation: "",
        arguments: [],
        tags: [],
        setup: "",
        teardown: "",
        timeout: "",
        returnValue: "",
        steps: []
      });
    }
    function dn(f) {
      ee.keywords.splice(f, 1), Q.value.delete(f);
    }
    function Do(f) {
      Q.value.has(f) ? Q.value.delete(f) : Q.value.add(f);
    }
    N(/* @__PURE__ */ new Map());
    function Mo(f, y) {
      ee.keywords[f].arguments.splice(y, 1);
    }
    function io(f, y) {
      const d = ee.keywords[f];
      switch (y) {
        case "positional":
          d.arguments.push("${arg}");
          break;
        case "optional":
          d.arguments.push("${arg}=");
          break;
        case "varargs":
          d.arguments.some((h) => h.startsWith("@{")) || d.arguments.push("@{args}");
          break;
        case "kwargs":
          d.arguments.some((h) => h.startsWith("&{")) || d.arguments.push("&{kwargs}");
          break;
        case "named-only":
          d.arguments.some((h) => h.startsWith("@{")) || d.arguments.push("@{}"), d.arguments.push("${arg}=");
          break;
      }
    }
    function Kn(f) {
      return f === "@{}" ? "named-only-sep" : f.startsWith("@{") ? "varargs" : f.startsWith("&{") ? "kwargs" : f.includes("=") ? "optional" : "positional";
    }
    function xo(f) {
      return f.replace(/^[$@&%]\{([^}]*)\}.*$/, "$1");
    }
    function Uo(f) {
      const y = f.match(/\}=(.*)$/);
      return y ? y[1] : "";
    }
    function bo(f, y, d) {
      var n, v;
      const h = f.arguments[y], l = ((n = h.match(/^([$@&%]\{)/)) == null ? void 0 : n[1]) || "${", E = ((v = h.match(/(\}=?.*)$/)) == null ? void 0 : v[1]) || "}", t = d.replace(/[$@&%{}]/g, "");
      f.arguments[y] = l + t + E;
    }
    function pn(f, y, d) {
      const l = f.arguments[y].replace(/\}=.*$/, "}");
      f.arguments[y] = l + "=" + d;
    }
    function fn(f, y) {
      for (let d = 0; d < y; d++)
        if (f.arguments[d].startsWith("@{")) return !0;
      return !1;
    }
    const uo = N(/* @__PURE__ */ new Map());
    function Gt(f, y) {
      y.trim() && !ee.keywords[f].tags.includes(y.trim()) && ee.keywords[f].tags.push(y.trim());
    }
    function Hn(f, y) {
      ee.keywords[f].tags.splice(y, 1);
    }
    function Xe(f) {
      Gt(f, uo.value.get(f) || ""), uo.value.set(f, "");
    }
    const Yt = N(null);
    let pt = null, He = null;
    function Mt() {
      if (!Yt.value) return;
      const f = Yt.value.querySelectorAll(".step-row");
      for (const y of f) {
        const d = y, h = d.scrollHeight > 40;
        d.classList.toggle("args-vertical", h);
      }
    }
    function On() {
      Yt.value && (pt = new MutationObserver(() => je(Mt)), pt.observe(Yt.value, { childList: !0, subtree: !0 }), He = new ResizeObserver(() => Mt()), He.observe(Yt.value), je(Mt));
    }
    function In() {
      pt == null || pt.disconnect(), He == null || He.disconnect(), pt = null, He = null;
    }
    return Go(() => {
      De(c.content), B.value = c.content, St = c.content, Zt(), je(On), b.value === "code" && je(Et);
    }), Se(() => c.readonly, (f) => {
      var y;
      (y = L.value) == null || y.dispatch({
        effects: T.reconfigure([
          Wn.readOnly.of(!!f),
          Ko.editable.of(!f)
        ])
      });
    }), Io(() => {
      Ut(), In();
    }), Se(() => c.content, (f) => {
      if (f !== Jt) {
        if (St = f, g("dirty", !1), b.value === "visual" || b.value === "flow")
          De(f), Zt();
        else if (B.value = f, L.value) {
          const y = L.value.state.doc.toString();
          y !== f && L.value.dispatch({ changes: { from: 0, to: y.length, insert: f } });
        }
      }
    }), (f, y) => (i(), u("div", {
      class: te(["robot-editor", { "robot-editor--readonly": e.readonly }]),
      style: Ee(e.height ? { height: e.height } : void 0)
    }, [
      e.readonly ? (i(), xt(Gf, {
        key: 0,
        role: "viewer"
      })) : $("", !0),
      o("div", bm, [
        o("div", wm, [
          o("button", {
            class: te(["tab-btn", { active: b.value === "code" }]),
            onClick: y[0] || (y[0] = (d) => nt("code"))
          }, m(p(s)("robotEditor.codeTab")), 3),
          o("button", {
            class: te(["tab-btn", { active: b.value === "visual" }]),
            onClick: y[1] || (y[1] = (d) => nt("visual"))
          }, m(p(s)("robotEditor.visualTab")), 3),
          o("button", {
            class: te(["tab-btn", { active: b.value === "flow" }]),
            onClick: y[2] || (y[2] = (d) => nt("flow"))
          }, m(p(s)("robotEditor.flowTab")), 3)
        ]),
        o("div", _m, [
          o("span", km, m(de.value ? ".resource" : ".robot"), 1),
          !de.value && ne.value > 0 ? (i(), u("span", Cm, m(ne.value) + " " + m(p(s)("robotEditor.tests")), 1)) : $("", !0),
          fe.value > 0 ? (i(), u("span", $m, m(fe.value) + " " + m(p(s)("robotEditor.keywordsCount")), 1)) : $("", !0),
          Ae.value ? (i(), u("span", {
            key: 2,
            class: "badge badge-warning",
            "data-testid": "init-file-warning",
            title: p(s)("robotEditor.initFileWarning")
          }, m(p(s)("robotEditor.initFileWarning")), 9, Tm)) : $("", !0),
          A.value !== "hidden" ? (i(), u("button", {
            key: 3,
            class: te(["heal-toggle-btn", { "heal-toggle-btn--on": A.value === "on" }]),
            "data-testid": "suite-heal-toggle",
            title: A.value === "on" ? p(s)("robotEditor.heal.toggleOnHint") : p(s)("robotEditor.heal.toggleOffHint"),
            onClick: M
          }, [
            y[9] || (y[9] = o("span", { "aria-hidden": "true" }, "🩹", -1)),
            we(" " + m(A.value === "on" ? p(s)("robotEditor.heal.toggleOn") : p(s)("robotEditor.heal.toggleOff")), 1)
          ], 10, Em)) : $("", !0),
          b.value === "visual" ? (i(), u("button", {
            key: 4,
            class: "icon-btn",
            onClick: G,
            title: p(s)("robotEditor.expandAll")
          }, "⊞", 8, Sm)) : $("", !0),
          b.value === "visual" ? (i(), u("button", {
            key: 5,
            class: "icon-btn",
            onClick: R,
            title: p(s)("robotEditor.collapseAll")
          }, "⊟", 8, Lm)) : $("", !0)
        ])
      ]),
      k.value ? (i(), u("div", Vm, [
        o("span", null, m(p(s)("robotEditor.parseError")) + ":", 1),
        we(" " + m(k.value), 1)
      ])) : $("", !0),
      z(o("div", {
        ref_key: "visualEditorRef",
        ref: Yt,
        class: "visual-editor"
      }, [
        o("div", Am, [
          o("div", {
            class: "section-header",
            onClick: y[3] || (y[3] = (d) => I.value = !I.value)
          }, [
            o("span", Nm, m(I.value ? "▶" : "▼"), 1),
            o("h3", null, m(p(s)("robotEditor.settingsSection")), 1),
            o("span", Rm, m(ee.settings.filter((d) => d.key !== "#").length), 1),
            ve(Oe, {
              size: "sm",
              variant: "secondary",
              onClick: le(tn, ["stop"])
            }, {
              default: _e(() => [
                we("+ " + m(p(s)("robotEditor.addSetting")), 1)
              ]),
              _: 1
            })
          ]),
          z(o("div", Fm, [
            ee.settings.length === 0 ? (i(), u("div", Pm, m(p(s)("robotEditor.noSettings")), 1)) : $("", !0),
            (i(!0), u(U, null, Y(ee.settings, (d, h) => (i(), u("div", {
              key: h,
              class: "setting-row"
            }, [
              d.key === "#" ? (i(), u(U, { key: 0 }, [
                z(o("input", {
                  "onUpdate:modelValue": (l) => d.value = l,
                  class: "form-input flex-1",
                  placeholder: "# Comment"
                }, null, 8, Dm), [
                  [ae, d.value]
                ]),
                o("button", {
                  class: "step-btn danger",
                  onClick: (l) => on(h),
                  title: p(s)("editor.common.delete")
                }, "×", 8, Mm)
              ], 64)) : (i(), u(U, { key: 1 }, [
                z(o("select", {
                  "onUpdate:modelValue": (l) => d.key = l,
                  class: te(["form-input setting-type-select", ln(d.key)])
                }, [
                  (i(), u(U, null, Y(Rn, (l) => o("option", {
                    key: l,
                    value: l
                  }, m(p(s)("robotEditor.settingType." + l.replace(/ /g, ""))), 9, Um)), 64))
                ], 10, xm), [
                  [it, d.key]
                ]),
                d.key === "Library" ? (i(), u("div", Bm, [
                  z(o("input", {
                    "onUpdate:modelValue": (l) => d.value = l,
                    class: "form-input setting-library-input",
                    placeholder: p(s)("robotEditor.settingValuePlaceholder"),
                    onFocus: (l) => Ne(h),
                    onBlur: Lo,
                    onKeydown: (l) => Nn(l, h),
                    onInput: (l) => Me(h)
                  }, null, 40, Km), [
                    [ae, d.value]
                  ]),
                  Rt.value === h && So.value.length > 0 ? (i(), u("div", {
                    key: 0,
                    ref_for: !0,
                    ref_key: "librarySettingDropdownRef",
                    ref: Jo,
                    class: "keyword-dropdown"
                  }, [
                    (i(!0), u(U, null, Y(So.value, (l, E) => (i(), u("div", {
                      key: l,
                      class: te(["keyword-dropdown-item", { active: E === Ie.value }]),
                      onMousedown: le((t) => en(h, l), ["prevent"])
                    }, [
                      o("span", Om, m(l), 1)
                    ], 42, Hm))), 128))
                  ], 512)) : $("", !0)
                ])) : (i(), u("span", Im, [
                  o("span", {
                    class: "form-hl-overlay",
                    innerHTML: Ve(d.value)
                  }, null, 8, Wm),
                  z(o("input", {
                    "onUpdate:modelValue": (l) => d.value = l,
                    class: "form-input",
                    style: { width: "100%" },
                    placeholder: p(s)("robotEditor.settingValuePlaceholder"),
                    spellcheck: "false"
                  }, null, 8, jm), [
                    [ae, d.value]
                  ])
                ])),
                d.args.length > 0 ? (i(), u("div", qm, [
                  (i(!0), u(U, null, Y(d.args, (l, E) => (i(), u("span", {
                    key: E,
                    class: "chip"
                  }, [
                    z(o("input", {
                      "onUpdate:modelValue": (t) => d.args[E] = t,
                      class: "chip-edit-input",
                      placeholder: p(s)("robotEditor.argPlaceholder")
                    }, null, 8, zm), [
                      [ae, d.args[E]]
                    ]),
                    o("button", {
                      class: "chip-remove",
                      onClick: (t) => Fn(h, E)
                    }, "×", 8, Gm)
                  ]))), 128))
                ])) : $("", !0),
                o("button", {
                  class: "step-btn",
                  onClick: (l) => fo(h),
                  title: "Add argument"
                }, "+", 8, Ym),
                o("button", {
                  class: "step-btn danger",
                  onClick: (l) => on(h),
                  title: p(s)("editor.common.delete")
                }, "×", 8, Xm)
              ], 64))
            ]))), 128))
          ], 512), [
            [Xt, !I.value]
          ])
        ]),
        o("div", Zm, [
          o("div", {
            class: "section-header",
            onClick: y[4] || (y[4] = (d) => W.value = !W.value)
          }, [
            o("span", Qm, m(W.value ? "▶" : "▼"), 1),
            o("h3", null, m(p(s)("robotEditor.variablesSection")), 1),
            o("span", Jm, m(ee.variables.filter((d) => d.name !== "#").length), 1),
            ve(Oe, {
              size: "sm",
              variant: "secondary",
              onClick: le(Pn, ["stop"])
            }, {
              default: _e(() => [
                we("+ " + m(p(s)("robotEditor.addVariable")), 1)
              ]),
              _: 1
            })
          ]),
          z(o("div", eg, [
            ee.variables.length === 0 ? (i(), u("div", tg, m(p(s)("robotEditor.noVariables")), 1)) : $("", !0),
            (i(!0), u(U, null, Y(ee.variables, (d, h) => (i(), u("div", {
              key: h,
              class: "variable-row"
            }, [
              d.name === "#" ? (i(), u(U, { key: 0 }, [
                z(o("input", {
                  "onUpdate:modelValue": (l) => d.value = l,
                  class: "form-input flex-1",
                  placeholder: "# Comment"
                }, null, 8, og), [
                  [ae, d.value]
                ]),
                o("button", {
                  class: "step-btn danger",
                  onClick: (l) => Vo(h),
                  title: p(s)("editor.common.delete")
                }, "×", 8, ng)
              ], 64)) : (i(), u(U, { key: 1 }, [
                o("span", ag, [
                  o("span", {
                    class: "form-hl-overlay hl-mono",
                    innerHTML: Ve(d.name)
                  }, null, 8, sg),
                  z(o("input", {
                    "onUpdate:modelValue": (l) => d.name = l,
                    class: "form-input var-name-input",
                    placeholder: p(s)("robotEditor.variableNamePlaceholder"),
                    onBlur: (l) => xn(d),
                    spellcheck: "false"
                  }, null, 40, lg), [
                    [ae, d.name]
                  ])
                ]),
                o("span", rg, [
                  o("span", {
                    class: "form-hl-overlay",
                    innerHTML: Ve(d.value)
                  }, null, 8, ig),
                  z(o("input", {
                    "onUpdate:modelValue": (l) => d.value = l,
                    class: "form-input",
                    style: { width: "100%" },
                    placeholder: p(s)("robotEditor.variableValuePlaceholder"),
                    spellcheck: "false"
                  }, null, 8, ug), [
                    [ae, d.value]
                  ])
                ]),
                o("button", {
                  class: "step-btn danger",
                  onClick: (l) => Vo(h),
                  title: p(s)("editor.common.delete")
                }, "×", 8, cg)
              ], 64))
            ]))), 128))
          ], 512), [
            [Xt, !W.value]
          ])
        ]),
        de.value ? $("", !0) : (i(), u("div", dg, [
          o("div", {
            class: "section-header",
            onClick: y[5] || (y[5] = (d) => re.value = !re.value)
          }, [
            o("span", pg, m(re.value ? "▶" : "▼"), 1),
            o("h3", null, m(p(s)("robotEditor.testCasesSection")), 1),
            o("span", fg, m(ee.testCases.length), 1),
            ve(Oe, {
              size: "sm",
              variant: "secondary",
              onClick: le(Fo, ["stop"])
            }, {
              default: _e(() => [
                we("+ " + m(p(s)("robotEditor.addTestCase")), 1)
              ]),
              _: 1
            })
          ]),
          z(o("div", mg, [
            ee.testCases.length === 0 ? (i(), u("div", gg, m(p(s)("robotEditor.noTestCases")), 1)) : $("", !0),
            (i(!0), u(U, null, Y(ee.testCases, (d, h) => (i(), u("div", {
              key: h,
              class: "item-card"
            }, [
              o("div", {
                class: "item-header",
                onClick: (l) => qt(h)
              }, [
                o("span", hg, m(Z.value.has(h) ? "▶" : "▼"), 1),
                o("span", yg, m(d.name || p(s)("robotEditor.unnamedTestCase")), 1),
                o("span", bg, m(d.steps.length) + " " + m(p(s)("robotEditor.steps")), 1),
                o("button", {
                  class: "remove-btn",
                  onClick: le((l) => Un(h), ["stop"]),
                  title: p(s)("editor.common.delete")
                }, "🗑", 8, wg)
              ], 8, vg),
              z(o("div", _g, [
                o("div", kg, [
                  o("label", Cg, [
                    we(m(p(s)("robotEditor.testCaseName")) + " ", 1),
                    y[10] || (y[10] = o("span", { class: "required" }, "*", -1))
                  ]),
                  z(o("input", {
                    "onUpdate:modelValue": (l) => d.name = l,
                    class: "form-input",
                    placeholder: p(s)("robotEditor.testCaseNamePlaceholder")
                  }, null, 8, $g), [
                    [ae, d.name]
                  ])
                ]),
                o("div", Tg, [
                  o("label", Eg, m(p(s)("robotEditor.documentation")), 1),
                  z(o("textarea", {
                    "onUpdate:modelValue": (l) => d.documentation = l,
                    class: "form-input form-textarea",
                    rows: "2",
                    placeholder: p(s)("robotEditor.documentationPlaceholder")
                  }, null, 8, Sg), [
                    [ae, d.documentation]
                  ])
                ]),
                K("tc", h, "tags", d.tags.length ? "x" : "") ? (i(), u("div", Lg, [
                  o("label", Vg, [
                    we(m(p(s)("robotEditor.tags")) + " ", 1),
                    d.tags.length ? $("", !0) : (i(), u("button", {
                      key: 0,
                      class: "meta-close-btn",
                      onClick: (l) => j("tc", h, "tags")
                    }, "×", 8, Ag))
                  ]),
                  o("div", Ng, [
                    (i(!0), u(U, null, Y(d.tags, (l, E) => (i(), u("span", {
                      key: E,
                      class: "chip chip-tag"
                    }, [
                      we(m(l) + " ", 1),
                      o("button", {
                        class: "chip-remove",
                        onClick: (t) => cn(h, E)
                      }, "×", 8, Rg)
                    ]))), 128)),
                    o("div", Fg, [
                      o("input", {
                        value: ro.value.get(h) || "",
                        onInput: (l) => ro.value.set(h, l.target.value),
                        class: "chip-input",
                        placeholder: p(s)("robotEditor.addTag"),
                        onKeydown: Ze(le((l) => zt(h), ["prevent"]), ["enter"])
                      }, null, 40, Pg),
                      (ro.value.get(h) || "").trim() ? (i(), u("button", {
                        key: 0,
                        class: "chip-add-btn",
                        onClick: (l) => zt(h)
                      }, "+", 8, Dg)) : $("", !0)
                    ])
                  ])
                ])) : $("", !0),
                o("div", Mg, [
                  K("tc", h, "tags", d.tags.length ? "x" : "") ? $("", !0) : (i(), u("button", {
                    key: 0,
                    class: "meta-toggle-btn mt-tags",
                    onClick: (l) => j("tc", h, "tags")
                  }, "+ Tags", 8, xg)),
                  K("tc", h, "setup", d.setup) ? $("", !0) : (i(), u("button", {
                    key: 1,
                    class: "meta-toggle-btn mt-setup",
                    onClick: (l) => j("tc", h, "setup")
                  }, "+ Setup", 8, Ug)),
                  K("tc", h, "teardown", d.teardown) ? $("", !0) : (i(), u("button", {
                    key: 2,
                    class: "meta-toggle-btn mt-teardown",
                    onClick: (l) => j("tc", h, "teardown")
                  }, "+ Teardown", 8, Bg)),
                  K("tc", h, "timeout", d.timeout) ? $("", !0) : (i(), u("button", {
                    key: 3,
                    class: "meta-toggle-btn mt-config",
                    onClick: (l) => j("tc", h, "timeout")
                  }, "+ Timeout", 8, Kg)),
                  K("tc", h, "template", d.template) ? $("", !0) : (i(), u("button", {
                    key: 4,
                    class: "meta-toggle-btn mt-config",
                    onClick: (l) => j("tc", h, "template")
                  }, "+ Template", 8, Hg))
                ]),
                K("tc", h, "setup", d.setup) ? (i(), u("div", Og, [
                  o("label", Ig, [
                    we(m(p(s)("robotEditor.setup")) + " ", 1),
                    d.setup ? $("", !0) : (i(), u("button", {
                      key: 0,
                      class: "meta-close-btn",
                      onClick: (l) => j("tc", h, "setup")
                    }, "×", 8, Wg))
                  ]),
                  o("div", jg, [
                    o("div", qg, [
                      o("input", {
                        value: ie("tc", h, "setup", d).keyword,
                        onInput: (l) => {
                          ie("tc", h, "setup", d).keyword = l.target.value, mt("tc", h, "setup", d);
                        },
                        class: "form-input step-keyword-input",
                        spellcheck: "false",
                        style: Ee({ width: Math.max(18, Math.min((ie("tc", h, "setup", d).keyword || "").length + 5, 40)) + "ch" }),
                        placeholder: p(s)("robotEditor.setupPlaceholder"),
                        onFocus: (l) => ut("tc", h, "setup", d),
                        onBlur: dt,
                        onKeydown: (l) => wt(l, ie("tc", h, "setup", d))
                      }, null, 44, zg),
                      D.value === ie("tc", h, "setup", d) && xe.value.length > 0 ? (i(), u("div", {
                        key: 0,
                        ref_for: !0,
                        ref_key: "keywordDropdownRef",
                        ref: be,
                        class: "keyword-dropdown"
                      }, [
                        (i(!0), u(U, null, Y(xe.value, (l, E) => {
                          var t;
                          return i(), u("div", {
                            key: l.name,
                            class: te(["keyword-dropdown-item", { active: E === oe.value }]),
                            onMousedown: le((n) => Je("tc", h, "setup", d, l), ["prevent"])
                          }, [
                            o("span", Yg, m(l.name), 1),
                            (t = l.args) != null && t.length ? (i(), u("span", Xg, m(l.args.map((n) => n.replace(/^[$@&%]\{([^}]+)\}.*$/, "$1")).join(", ")), 1)) : $("", !0)
                          ], 42, Gg);
                        }), 128))
                      ], 512)) : $("", !0)
                    ]),
                    ie("tc", h, "setup", d).args.length ? (i(), u("div", Zg, [
                      (i(!0), u(U, null, Y(ie("tc", h, "setup", d).args, (l, E) => (i(), u("span", {
                        key: E,
                        class: "step-arg-chip"
                      }, [
                        ke(ie("tc", h, "setup", d))[E] ? (i(), u("span", Qg, m(ke(ie("tc", h, "setup", d))[E]), 1)) : $("", !0),
                        o("span", {
                          class: "arg-highlight-wrap",
                          style: Ee({ width: rt(l) })
                        }, [
                          o("span", {
                            class: "arg-highlight-overlay",
                            innerHTML: Ve(l)
                          }, null, 8, Jg),
                          o("input", {
                            value: l,
                            onInput: (t) => {
                              ie("tc", h, "setup", d).args[E] = t.target.value, Ke("tc", h, "setup", d);
                            },
                            class: "step-arg-input",
                            spellcheck: "false",
                            style: { width: "100%" },
                            placeholder: ke(ie("tc", h, "setup", d))[E] || p(s)("robotEditor.argPlaceholder")
                          }, null, 40, ev)
                        ], 4),
                        o("button", {
                          class: "chip-remove",
                          onClick: (t) => Ye("tc", h, "setup", d, E)
                        }, "×", 8, tv)
                      ]))), 128))
                    ])) : $("", !0),
                    o("button", {
                      class: "step-btn step-add-arg",
                      onClick: (l) => gt("tc", h, "setup", d),
                      title: p(s)("robotEditor.addArg")
                    }, "+", 8, ov)
                  ])
                ])) : $("", !0),
                K("tc", h, "teardown", d.teardown) ? (i(), u("div", nv, [
                  o("label", av, [
                    we(m(p(s)("robotEditor.teardown")) + " ", 1),
                    d.teardown ? $("", !0) : (i(), u("button", {
                      key: 0,
                      class: "meta-close-btn",
                      onClick: (l) => j("tc", h, "teardown")
                    }, "×", 8, sv))
                  ]),
                  o("div", lv, [
                    o("div", rv, [
                      o("input", {
                        value: ie("tc", h, "teardown", d).keyword,
                        onInput: (l) => {
                          ie("tc", h, "teardown", d).keyword = l.target.value, mt("tc", h, "teardown", d);
                        },
                        class: "form-input step-keyword-input",
                        spellcheck: "false",
                        style: Ee({ width: Math.max(18, Math.min((ie("tc", h, "teardown", d).keyword || "").length + 5, 40)) + "ch" }),
                        placeholder: p(s)("robotEditor.teardownPlaceholder"),
                        onFocus: (l) => ut("tc", h, "teardown", d),
                        onBlur: dt,
                        onKeydown: (l) => wt(l, ie("tc", h, "teardown", d))
                      }, null, 44, iv),
                      D.value === ie("tc", h, "teardown", d) && xe.value.length > 0 ? (i(), u("div", {
                        key: 0,
                        ref_for: !0,
                        ref_key: "keywordDropdownRef",
                        ref: be,
                        class: "keyword-dropdown"
                      }, [
                        (i(!0), u(U, null, Y(xe.value, (l, E) => {
                          var t;
                          return i(), u("div", {
                            key: l.name,
                            class: te(["keyword-dropdown-item", { active: E === oe.value }]),
                            onMousedown: le((n) => Je("tc", h, "teardown", d, l), ["prevent"])
                          }, [
                            o("span", cv, m(l.name), 1),
                            (t = l.args) != null && t.length ? (i(), u("span", dv, m(l.args.map((n) => n.replace(/^[$@&%]\{([^}]+)\}.*$/, "$1")).join(", ")), 1)) : $("", !0)
                          ], 42, uv);
                        }), 128))
                      ], 512)) : $("", !0)
                    ]),
                    ie("tc", h, "teardown", d).args.length ? (i(), u("div", pv, [
                      (i(!0), u(U, null, Y(ie("tc", h, "teardown", d).args, (l, E) => (i(), u("span", {
                        key: E,
                        class: "step-arg-chip"
                      }, [
                        ke(ie("tc", h, "teardown", d))[E] ? (i(), u("span", fv, m(ke(ie("tc", h, "teardown", d))[E]), 1)) : $("", !0),
                        o("span", {
                          class: "arg-highlight-wrap",
                          style: Ee({ width: rt(l) })
                        }, [
                          o("span", {
                            class: "arg-highlight-overlay",
                            innerHTML: Ve(l)
                          }, null, 8, mv),
                          o("input", {
                            value: l,
                            onInput: (t) => {
                              ie("tc", h, "teardown", d).args[E] = t.target.value, Ke("tc", h, "teardown", d);
                            },
                            class: "step-arg-input",
                            spellcheck: "false",
                            style: { width: "100%" },
                            placeholder: ke(ie("tc", h, "teardown", d))[E] || p(s)("robotEditor.argPlaceholder")
                          }, null, 40, gv)
                        ], 4),
                        o("button", {
                          class: "chip-remove",
                          onClick: (t) => Ye("tc", h, "teardown", d, E)
                        }, "×", 8, vv)
                      ]))), 128))
                    ])) : $("", !0),
                    o("button", {
                      class: "step-btn step-add-arg",
                      onClick: (l) => gt("tc", h, "teardown", d),
                      title: p(s)("robotEditor.addArg")
                    }, "+", 8, hv)
                  ])
                ])) : $("", !0),
                K("tc", h, "timeout", d.timeout) || K("tc", h, "template", d.template) ? (i(), u("div", yv, [
                  K("tc", h, "timeout", d.timeout) ? (i(), u("div", bv, [
                    o("label", wv, [
                      we(m(p(s)("robotEditor.timeout")) + " ", 1),
                      d.timeout ? $("", !0) : (i(), u("button", {
                        key: 0,
                        class: "meta-close-btn",
                        onClick: (l) => j("tc", h, "timeout")
                      }, "×", 8, _v))
                    ]),
                    o("span", kv, [
                      o("span", {
                        class: "form-hl-overlay",
                        innerHTML: Ve(d.timeout)
                      }, null, 8, Cv),
                      z(o("input", {
                        "onUpdate:modelValue": (l) => d.timeout = l,
                        class: "form-input",
                        style: { width: "100%" },
                        placeholder: p(s)("robotEditor.timeoutPlaceholder"),
                        spellcheck: "false"
                      }, null, 8, $v), [
                        [ae, d.timeout]
                      ])
                    ])
                  ])) : $("", !0),
                  K("tc", h, "template", d.template) ? (i(), u("div", Tv, [
                    o("label", Ev, [
                      we(m(p(s)("robotEditor.template")) + " ", 1),
                      d.template ? $("", !0) : (i(), u("button", {
                        key: 0,
                        class: "meta-close-btn",
                        onClick: (l) => j("tc", h, "template")
                      }, "×", 8, Sv))
                    ]),
                    o("span", Lv, [
                      o("span", {
                        class: "form-hl-overlay",
                        innerHTML: Ve(d.template)
                      }, null, 8, Vv),
                      z(o("input", {
                        "onUpdate:modelValue": (l) => d.template = l,
                        class: "form-input",
                        style: { width: "100%" },
                        placeholder: p(s)("robotEditor.templatePlaceholder"),
                        spellcheck: "false"
                      }, null, 8, Av), [
                        [ae, d.template]
                      ])
                    ])
                  ])) : $("", !0)
                ])) : $("", !0),
                o("div", Nv, [
                  o("label", Rv, m(p(s)("robotEditor.steps")), 1),
                  o("div", Fv, [
                    (i(!0), u(U, null, Y(d.steps, (l, E) => (i(), u("div", {
                      key: E,
                      class: "step-row",
                      style: Ee({ paddingLeft: Pt(d.steps, E) * 24 + "px" })
                    }, [
                      o("span", Pv, m(E + 1) + ".", 1),
                      z(o("select", {
                        "onUpdate:modelValue": (t) => l.type = t,
                        class: te(["step-type-select", rn(l.type)]),
                        onChange: (t) => sn(l)
                      }, [
                        (i(!0), u(U, null, Y(p(Ta), (t) => (i(), u("option", {
                          key: t,
                          value: t
                        }, m(Ft(t)), 9, Mv))), 128))
                      ], 42, Dv), [
                        [it, l.type]
                      ]),
                      l.type === "keyword" ? (i(), u(U, { key: 0 }, [
                        o("div", xv, [
                          z(o("input", {
                            "onUpdate:modelValue": (t) => l.keyword = t,
                            class: "form-input step-keyword-input",
                            spellcheck: "false",
                            style: Ee({ width: Math.max(18, Math.min((l.keyword || "").length + 5, 60)) + "ch" }),
                            placeholder: p(s)("robotEditor.keywordPlaceholder"),
                            onFocus: (t) => It(l),
                            onBlur: dt,
                            onKeydown: (t) => wt(t, l),
                            onInput: (t) => Wt(l)
                          }, null, 44, Uv), [
                            [ae, l.keyword]
                          ]),
                          D.value === l && xe.value.length > 0 ? (i(), u("div", {
                            key: 0,
                            ref_for: !0,
                            ref_key: "keywordDropdownRef",
                            ref: be,
                            class: "keyword-dropdown"
                          }, [
                            (i(!0), u(U, null, Y(xe.value, (t, n) => {
                              var v;
                              return i(), u("div", {
                                key: t.name,
                                class: te(["keyword-dropdown-item", { active: n === oe.value }]),
                                onMousedown: le((F) => Dt(l, t), ["prevent"])
                              }, [
                                o("span", Kv, m(t.name), 1),
                                (v = t.args) != null && v.length ? (i(), u("span", Hv, m(t.args.map((F) => F.replace(/^[$@&%]\{([^}]+)\}.*$/, "$1")).join(", ")), 1)) : $("", !0),
                                o("span", {
                                  class: te(["kw-suggestion-source", "source-" + (t.source === "builtin" ? "builtin" : t.source === "local" || t.source === "project" ? "local" : "library")])
                                }, m(t.source === "builtin" ? p(s)("robotEditor.builtinKeyword") : t.source === "local" || t.source === "project" ? p(s)("robotEditor.localKeyword") : t.source), 3)
                              ], 42, Bv);
                            }), 128))
                          ], 512)) : $("", !0)
                        ]),
                        l.args.length ? (i(), u("div", Ov, [
                          (i(!0), u(U, null, Y(l.args, (t, n) => (i(), u("span", {
                            key: n,
                            class: "step-arg-chip arg-ac-wrapper"
                          }, [
                            ke(l)[n] ? (i(), u("span", Iv, m(ke(l)[n]), 1)) : $("", !0),
                            o("span", {
                              class: "arg-highlight-wrap",
                              style: Ee({ width: rt(l.args[n]) })
                            }, [
                              o("span", {
                                class: "arg-highlight-overlay",
                                innerHTML: Ve(l.args[n])
                              }, null, 8, Wv),
                              z(o("input", {
                                "onUpdate:modelValue": (v) => l.args[n] = v,
                                class: "step-arg-input",
                                spellcheck: "false",
                                onScroll: jt,
                                style: { width: "100%" },
                                placeholder: ke(l)[n] || p(s)("robotEditor.argPlaceholder"),
                                onInput: (v) => so(v.target.value, `${E}-${n}`),
                                onKeydown: (v) => ho(v, l, n),
                                onBlur: lo,
                                autocomplete: "off"
                              }, null, 40, jv), [
                                [ae, l.args[n]]
                              ])
                            ], 4),
                            Le.value === `${E}-${n}` && ce.value.length ? (i(), u("div", qv, [
                              (i(!0), u(U, null, Y(ce.value, (v, F) => (i(), u("div", {
                                key: v,
                                class: te(["arg-ac-item", { active: F === ge.value }]),
                                onMousedown: le((X) => _t(l, n, v), ["prevent"])
                              }, m(v), 43, zv))), 128))
                            ])) : $("", !0),
                            o("button", {
                              class: "chip-remove",
                              onClick: (v) => Ue(l, n)
                            }, "×", 8, Gv)
                          ]))), 128))
                        ])) : $("", !0),
                        o("button", {
                          class: te(["step-btn step-add-arg", { "has-hint": tt(l) !== "+" }]),
                          onClick: (t) => bt(l),
                          title: p(s)("robotEditor.addArg")
                        }, m(tt(l)), 11, Yv)
                      ], 64)) : l.type === "assignment" ? (i(), u(U, { key: 1 }, [
                        (i(!0), u(U, null, Y(l.returnVars, (t, n) => (i(), u("span", {
                          key: n,
                          class: "step-var-chip"
                        }, [
                          z(o("input", {
                            "onUpdate:modelValue": (v) => l.returnVars[n] = v,
                            class: "step-var-input",
                            spellcheck: "false",
                            placeholder: "${var}",
                            onBlur: (v) => yo(l, n)
                          }, null, 40, Xv), [
                            [ae, l.returnVars[n]]
                          ]),
                          o("button", {
                            class: "chip-remove",
                            onClick: (v) => ao(l, n)
                          }, "×", 8, Zv)
                        ]))), 128)),
                        o("button", {
                          class: "step-btn step-add-var",
                          onClick: (t) => an(l),
                          title: "+var"
                        }, "+v", 8, Qv),
                        y[11] || (y[11] = o("span", { class: "step-assign-eq" }, "=", -1)),
                        o("div", Jv, [
                          z(o("input", {
                            "onUpdate:modelValue": (t) => l.keyword = t,
                            class: "form-input step-keyword-input",
                            spellcheck: "false",
                            style: Ee({ width: Math.max(18, Math.min((l.keyword || "").length + 5, 60)) + "ch" }),
                            placeholder: p(s)("robotEditor.keywordPlaceholder"),
                            onFocus: (t) => It(l),
                            onBlur: dt,
                            onKeydown: (t) => wt(t, l),
                            onInput: (t) => Wt(l)
                          }, null, 44, eh), [
                            [ae, l.keyword]
                          ]),
                          D.value === l && xe.value.length > 0 ? (i(), u("div", {
                            key: 0,
                            ref_for: !0,
                            ref_key: "keywordDropdownRef",
                            ref: be,
                            class: "keyword-dropdown"
                          }, [
                            (i(!0), u(U, null, Y(xe.value, (t, n) => {
                              var v;
                              return i(), u("div", {
                                key: t.name,
                                class: te(["keyword-dropdown-item", { active: n === oe.value }]),
                                onMousedown: le((F) => Dt(l, t), ["prevent"])
                              }, [
                                o("span", oh, m(t.name), 1),
                                (v = t.args) != null && v.length ? (i(), u("span", nh, m(t.args.map((F) => F.replace(/^[$@&%]\{([^}]+)\}.*$/, "$1")).join(", ")), 1)) : $("", !0),
                                o("span", {
                                  class: te(["kw-suggestion-source", "source-" + (t.source === "builtin" ? "builtin" : t.source === "local" || t.source === "project" ? "local" : "library")])
                                }, m(t.source === "builtin" ? p(s)("robotEditor.builtinKeyword") : t.source === "local" || t.source === "project" ? p(s)("robotEditor.localKeyword") : t.source), 3)
                              ], 42, th);
                            }), 128))
                          ], 512)) : $("", !0)
                        ]),
                        l.args.length ? (i(), u("div", ah, [
                          (i(!0), u(U, null, Y(l.args, (t, n) => (i(), u("span", {
                            key: n,
                            class: "step-arg-chip arg-ac-wrapper"
                          }, [
                            ke(l)[n] ? (i(), u("span", sh, m(ke(l)[n]), 1)) : $("", !0),
                            o("span", {
                              class: "arg-highlight-wrap",
                              style: Ee({ width: rt(l.args[n]) })
                            }, [
                              o("span", {
                                class: "arg-highlight-overlay",
                                innerHTML: Ve(l.args[n])
                              }, null, 8, lh),
                              z(o("input", {
                                "onUpdate:modelValue": (v) => l.args[n] = v,
                                class: "step-arg-input",
                                spellcheck: "false",
                                onScroll: jt,
                                style: { width: "100%" },
                                placeholder: ke(l)[n] || p(s)("robotEditor.argPlaceholder"),
                                onInput: (v) => so(v.target.value, `${E}-${n}`),
                                onKeydown: (v) => ho(v, l, n),
                                onBlur: lo,
                                autocomplete: "off"
                              }, null, 40, rh), [
                                [ae, l.args[n]]
                              ])
                            ], 4),
                            Le.value === `${E}-${n}` && ce.value.length ? (i(), u("div", ih, [
                              (i(!0), u(U, null, Y(ce.value, (v, F) => (i(), u("div", {
                                key: v,
                                class: te(["arg-ac-item", { active: F === ge.value }]),
                                onMousedown: le((X) => _t(l, n, v), ["prevent"])
                              }, m(v), 43, uh))), 128))
                            ])) : $("", !0),
                            o("button", {
                              class: "chip-remove",
                              onClick: (v) => Ue(l, n)
                            }, "×", 8, ch)
                          ]))), 128))
                        ])) : $("", !0),
                        o("button", {
                          class: te(["step-btn step-add-arg", { "has-hint": tt(l) !== "+" }]),
                          onClick: (t) => bt(l),
                          title: p(s)("robotEditor.addArg")
                        }, m(tt(l)), 11, dh)
                      ], 64)) : l.type === "var" ? (i(), u(U, { key: 2 }, [
                        o("span", ph, [
                          z(o("input", {
                            "onUpdate:modelValue": (t) => l.returnVars[0] = t,
                            class: "step-var-input",
                            spellcheck: "false",
                            placeholder: "${var}",
                            onBlur: (t) => yo(l, 0)
                          }, null, 40, fh), [
                            [ae, l.returnVars[0]]
                          ])
                        ]),
                        y[13] || (y[13] = o("span", { class: "step-assign-eq" }, "=", -1)),
                        (i(!0), u(U, null, Y(l.args, (t, n) => (i(), u("span", {
                          key: n,
                          class: "step-arg-chip"
                        }, [
                          o("span", {
                            class: "arg-highlight-wrap",
                            style: Ee({ width: rt(l.args[n]) })
                          }, [
                            o("span", {
                              class: "arg-highlight-overlay",
                              innerHTML: Ve(l.args[n])
                            }, null, 8, mh),
                            z(o("input", {
                              "onUpdate:modelValue": (v) => l.args[n] = v,
                              class: "step-arg-input",
                              spellcheck: "false",
                              onScroll: jt,
                              style: { width: "100%" },
                              placeholder: p(s)("robotEditor.valuePlaceholder")
                            }, null, 40, gh), [
                              [ae, l.args[n]]
                            ])
                          ], 4),
                          o("button", {
                            class: "chip-remove",
                            onClick: (v) => Ue(l, n)
                          }, "×", 8, vh)
                        ]))), 128)),
                        o("button", {
                          class: "step-btn step-add-arg",
                          onClick: (t) => bt(l),
                          title: p(s)("robotEditor.addValue")
                        }, "+", 8, hh),
                        z(o("select", {
                          "onUpdate:modelValue": (t) => l.varScope = t,
                          class: "step-scope-select"
                        }, [
                          y[12] || (y[12] = o("option", { value: "" }, "scope", -1)),
                          (i(!0), u(U, null, Y(p(Ea), (t) => (i(), u("option", {
                            key: t,
                            value: t
                          }, m(t), 9, bh))), 128))
                        ], 8, yh), [
                          [it, l.varScope]
                        ])
                      ], 64)) : l.type === "for" ? (i(), u(U, { key: 3 }, [
                        o("span", wh, [
                          o("span", {
                            class: "form-hl-overlay hl-mono",
                            innerHTML: Ve(l.loopVar)
                          }, null, 8, _h),
                          z(o("input", {
                            "onUpdate:modelValue": (t) => l.loopVar = t,
                            class: "form-input step-var-input-inline",
                            spellcheck: "false",
                            style: { width: "100%" },
                            placeholder: "${item}"
                          }, null, 8, kh), [
                            [ae, l.loopVar]
                          ])
                        ]),
                        z(o("select", {
                          "onUpdate:modelValue": (t) => l.loopFlavor = t,
                          class: "step-flavor-select"
                        }, [
                          (i(!0), u(U, null, Y(p(ta), (t) => (i(), u("option", {
                            key: t,
                            value: t
                          }, m(t), 9, $h))), 128))
                        ], 8, Ch), [
                          [it, l.loopFlavor]
                        ]),
                        (i(!0), u(U, null, Y(l.loopValues, (t, n) => (i(), u("span", {
                          key: n,
                          class: "step-arg-chip"
                        }, [
                          z(o("input", {
                            "onUpdate:modelValue": (v) => l.loopValues[n] = v,
                            class: "step-arg-input",
                            placeholder: p(s)("robotEditor.valuePlaceholder")
                          }, null, 8, Th), [
                            [ae, l.loopValues[n]]
                          ]),
                          o("button", {
                            class: "chip-remove",
                            onClick: (v) => go(l, n)
                          }, "×", 8, Eh)
                        ]))), 128)),
                        o("button", {
                          class: "step-btn step-add-arg",
                          onClick: (t) => Ao(l),
                          title: p(s)("robotEditor.addValue")
                        }, "+", 8, Sh)
                      ], 64)) : l.type === "if" || l.type === "else_if" || l.type === "while" ? (i(), u("span", Lh, [
                        o("span", {
                          class: "form-hl-overlay hl-condition",
                          innerHTML: Ve(l.condition)
                        }, null, 8, Vh),
                        z(o("input", {
                          "onUpdate:modelValue": (t) => l.condition = t,
                          class: "form-input step-condition-input",
                          style: { width: "100%" },
                          placeholder: p(s)("robotEditor.conditionPlaceholder"),
                          spellcheck: "false"
                        }, null, 8, Ah), [
                          [ae, l.condition]
                        ])
                      ])) : l.type === "except" ? (i(), u(U, { key: 5 }, [
                        z(o("input", {
                          "onUpdate:modelValue": (t) => l.exceptPattern = t,
                          class: "form-input step-keyword-input",
                          spellcheck: "false",
                          placeholder: p(s)("robotEditor.exceptPatternPlaceholder")
                        }, null, 8, Nh), [
                          [ae, l.exceptPattern]
                        ]),
                        l.exceptVar || l.exceptPattern ? (i(), u("span", Rh, "AS")) : $("", !0),
                        l.exceptVar || l.exceptPattern ? (i(), u("span", Fh, [
                          o("span", {
                            class: "form-hl-overlay hl-mono",
                            innerHTML: Ve(l.exceptVar)
                          }, null, 8, Ph),
                          z(o("input", {
                            "onUpdate:modelValue": (t) => l.exceptVar = t,
                            class: "form-input step-var-input-inline",
                            spellcheck: "false",
                            style: { width: "100%" },
                            placeholder: "${error}"
                          }, null, 8, Dh), [
                            [ae, l.exceptVar]
                          ])
                        ])) : $("", !0)
                      ], 64)) : l.type === "return" ? (i(), u(U, { key: 6 }, [
                        (i(!0), u(U, null, Y(l.args, (t, n) => (i(), u("span", {
                          key: n,
                          class: "step-arg-chip"
                        }, [
                          z(o("input", {
                            "onUpdate:modelValue": (v) => l.args[n] = v,
                            class: "step-arg-input",
                            placeholder: p(s)("robotEditor.valuePlaceholder")
                          }, null, 8, Mh), [
                            [ae, l.args[n]]
                          ]),
                          o("button", {
                            class: "chip-remove",
                            onClick: (v) => Ue(l, n)
                          }, "×", 8, xh)
                        ]))), 128)),
                        o("button", {
                          class: "step-btn step-add-arg",
                          onClick: (t) => bt(l),
                          title: p(s)("robotEditor.addValue")
                        }, "+", 8, Uh)
                      ], 64)) : l.type === "comment" ? z((i(), u("input", {
                        key: 7,
                        "onUpdate:modelValue": (t) => l.comment = t,
                        class: "form-input flex-1 step-comment-input",
                        spellcheck: "false",
                        placeholder: "# ..."
                      }, null, 8, Bh)), [
                        [ae, l.comment]
                      ]) : $("", !0),
                      o("div", Kh, [
                        o("button", {
                          class: "step-btn",
                          onClick: (t) => no(d.steps, E, -1),
                          disabled: E === 0
                        }, "↑", 8, Hh),
                        o("button", {
                          class: "step-btn",
                          onClick: (t) => no(d.steps, E, 1),
                          disabled: E === d.steps.length - 1
                        }, "↓", 8, Oh),
                        o("button", {
                          class: "step-btn danger",
                          onClick: (t) => nn(d.steps, E),
                          title: p(s)("editor.common.delete")
                        }, "×", 8, Ih)
                      ])
                    ], 4))), 128)),
                    o("div", Wh, [
                      o("button", {
                        class: "add-step-btn add-keyword",
                        onClick: (l) => mo(d.steps)
                      }, "+ " + m(p(s)("robotEditor.addStep")), 9, jh),
                      o("button", {
                        class: "add-step-btn add-block add-var",
                        onClick: (l) => mo(d.steps, "var")
                      }, "+ VAR", 8, qh),
                      o("button", {
                        class: "add-step-btn add-block add-loop",
                        onClick: (l) => st(d.steps, "for")
                      }, "+ FOR", 8, zh),
                      o("button", {
                        class: "add-step-btn add-block add-condition",
                        onClick: (l) => st(d.steps, "if")
                      }, "+ IF", 8, Gh),
                      o("button", {
                        class: "add-step-btn add-block add-loop",
                        onClick: (l) => st(d.steps, "while")
                      }, "+ WHILE", 8, Yh),
                      o("button", {
                        class: "add-step-btn add-block add-error",
                        onClick: (l) => st(d.steps, "try")
                      }, "+ TRY", 8, Xh)
                    ])
                  ])
                ])
              ], 512), [
                [Xt, !Z.value.has(h)]
              ])
            ]))), 128))
          ], 512), [
            [Xt, !re.value]
          ])
        ])),
        o("div", Zh, [
          o("div", {
            class: "section-header",
            onClick: y[6] || (y[6] = (d) => x.value = !x.value)
          }, [
            o("span", Qh, m(x.value ? "▶" : "▼"), 1),
            o("h3", null, m(p(s)("robotEditor.keywordsSection")), 1),
            o("span", Jh, m(ee.keywords.length), 1),
            ve(Oe, {
              size: "sm",
              variant: "secondary",
              onClick: le(Po, ["stop"])
            }, {
              default: _e(() => [
                we("+ " + m(p(s)("robotEditor.addKeyword")), 1)
              ]),
              _: 1
            })
          ]),
          z(o("div", ey, [
            ee.keywords.length === 0 ? (i(), u("div", ty, m(p(s)("robotEditor.noKeywords")), 1)) : $("", !0),
            (i(!0), u(U, null, Y(ee.keywords, (d, h) => (i(), u("div", {
              key: h,
              class: "item-card"
            }, [
              o("div", {
                class: "item-header",
                onClick: (l) => Do(h)
              }, [
                o("span", ny, m(Q.value.has(h) ? "▶" : "▼"), 1),
                o("span", ay, m(d.name || p(s)("robotEditor.unnamedKeyword")), 1),
                o("span", sy, m(d.steps.length) + " " + m(p(s)("robotEditor.steps")), 1),
                o("button", {
                  class: "remove-btn",
                  onClick: le((l) => dn(h), ["stop"]),
                  title: p(s)("editor.common.delete")
                }, "🗑", 8, ly)
              ], 8, oy),
              z(o("div", ry, [
                o("div", iy, [
                  o("label", uy, [
                    we(m(p(s)("robotEditor.keywordName")) + " ", 1),
                    y[14] || (y[14] = o("span", { class: "required" }, "*", -1))
                  ]),
                  z(o("input", {
                    "onUpdate:modelValue": (l) => d.name = l,
                    class: "form-input",
                    placeholder: p(s)("robotEditor.keywordNamePlaceholder")
                  }, null, 8, cy), [
                    [ae, d.name]
                  ])
                ]),
                o("div", dy, [
                  o("label", py, m(p(s)("robotEditor.arguments")), 1),
                  o("div", fy, [
                    (i(!0), u(U, null, Y(d.arguments, (l, E) => (i(), u("div", {
                      key: E,
                      class: te(["kw-arg-row", "kw-arg-" + (l === "@{}" ? "sep" : fn(d, E) ? "named" : Kn(l))])
                    }, [
                      l === "@{}" ? (i(), u(U, { key: 0 }, [
                        y[15] || (y[15] = o("span", { class: "kw-arg-sep-line" }, null, -1)),
                        o("span", my, m(p(s)("robotEditor.argTypes.namedOnlyBelow")), 1),
                        y[16] || (y[16] = o("span", { class: "kw-arg-sep-line" }, null, -1))
                      ], 64)) : l.startsWith("@{") ? (i(), u(U, { key: 1 }, [
                        o("span", gy, "*" + m(p(s)("robotEditor.argTypes.varargs")), 1),
                        o("input", {
                          class: "kw-arg-name-input",
                          spellcheck: "false",
                          value: xo(l),
                          onInput: (t) => bo(d, E, t.target.value),
                          onBlur: (t) => Ro(d, E),
                          placeholder: "args"
                        }, null, 40, vy)
                      ], 64)) : l.startsWith("&{") ? (i(), u(U, { key: 2 }, [
                        o("span", hy, "**" + m(p(s)("robotEditor.argTypes.kwargs")), 1),
                        o("input", {
                          class: "kw-arg-name-input",
                          spellcheck: "false",
                          value: xo(l),
                          onInput: (t) => bo(d, E, t.target.value),
                          onBlur: (t) => Ro(d, E),
                          placeholder: "kwargs"
                        }, null, 40, yy)
                      ], 64)) : (i(), u(U, { key: 3 }, [
                        fn(d, E) ? (i(), u("span", by, m(p(s)("robotEditor.argTypes.namedOnly")), 1)) : l.includes("=") ? (i(), u("span", wy, m(p(s)("robotEditor.argTypes.optional")), 1)) : (i(), u("span", _y, m(p(s)("robotEditor.argTypes.required")), 1)),
                        o("input", {
                          class: "kw-arg-name-input",
                          spellcheck: "false",
                          value: xo(l),
                          onInput: (t) => bo(d, E, t.target.value),
                          onBlur: (t) => Ro(d, E),
                          placeholder: p(s)("robotEditor.argTypes.namePlaceholder")
                        }, null, 40, ky),
                        l.includes("=") ? (i(), u(U, { key: 3 }, [
                          y[17] || (y[17] = o("span", { class: "kw-arg-eq" }, "=", -1)),
                          o("span", Cy, [
                            o("span", {
                              class: "form-hl-overlay hl-kw-default",
                              innerHTML: Ve(Uo(l))
                            }, null, 8, $y),
                            o("input", {
                              class: "kw-arg-default-input",
                              spellcheck: "false",
                              value: Uo(l),
                              onInput: (t) => pn(d, E, t.target.value),
                              placeholder: p(s)("robotEditor.argTypes.defaultPlaceholder")
                            }, null, 40, Ty)
                          ])
                        ], 64)) : $("", !0),
                        l.includes("=") ? $("", !0) : (i(), u("button", {
                          key: 4,
                          class: "kw-arg-opt-btn",
                          onClick: (t) => d.arguments[E] = l + "=",
                          title: p(s)("robotEditor.argTypes.addDefault")
                        }, "= ?", 8, Ey))
                      ], 64)),
                      o("button", {
                        class: "chip-remove",
                        onClick: (t) => Mo(h, E)
                      }, "×", 8, Sy)
                    ], 2))), 128))
                  ]),
                  o("div", Ly, [
                    o("button", {
                      class: "kw-arg-add-btn",
                      onClick: (l) => io(h, "positional")
                    }, "+ " + m(p(s)("robotEditor.argTypes.required")), 9, Vy),
                    o("button", {
                      class: "kw-arg-add-btn",
                      onClick: (l) => io(h, "optional")
                    }, "+ " + m(p(s)("robotEditor.argTypes.optional")), 9, Ay),
                    o("button", {
                      class: "kw-arg-add-btn",
                      disabled: d.arguments.some((l) => l.startsWith("@{")),
                      onClick: (l) => io(h, "varargs")
                    }, "+ " + m(p(s)("robotEditor.argTypes.varargs")), 9, Ny),
                    o("button", {
                      class: "kw-arg-add-btn",
                      disabled: d.arguments.some((l) => l.startsWith("&{")),
                      onClick: (l) => io(h, "kwargs")
                    }, "+ " + m(p(s)("robotEditor.argTypes.kwargs")), 9, Ry),
                    o("button", {
                      class: "kw-arg-add-btn",
                      onClick: (l) => io(h, "named-only")
                    }, "+ " + m(p(s)("robotEditor.argTypes.namedOnly")), 9, Fy)
                  ])
                ]),
                o("div", Py, [
                  o("label", Dy, m(p(s)("robotEditor.documentation")), 1),
                  z(o("textarea", {
                    "onUpdate:modelValue": (l) => d.documentation = l,
                    class: "form-input form-textarea",
                    rows: "2",
                    placeholder: p(s)("robotEditor.documentationPlaceholder")
                  }, null, 8, My), [
                    [ae, d.documentation]
                  ])
                ]),
                K("kw", h, "tags", d.tags.length ? "x" : "") ? (i(), u("div", xy, [
                  o("label", Uy, [
                    we(m(p(s)("robotEditor.tags")) + " ", 1),
                    d.tags.length ? $("", !0) : (i(), u("button", {
                      key: 0,
                      class: "meta-close-btn",
                      onClick: (l) => j("kw", h, "tags")
                    }, "×", 8, By))
                  ]),
                  o("div", Ky, [
                    (i(!0), u(U, null, Y(d.tags, (l, E) => (i(), u("span", {
                      key: E,
                      class: "chip chip-tag"
                    }, [
                      we(m(l) + " ", 1),
                      o("button", {
                        class: "chip-remove",
                        onClick: (t) => Hn(h, E)
                      }, "×", 8, Hy)
                    ]))), 128)),
                    o("div", Oy, [
                      o("input", {
                        value: uo.value.get(h) || "",
                        onInput: (l) => uo.value.set(h, l.target.value),
                        class: "chip-input",
                        placeholder: p(s)("robotEditor.addTag"),
                        onKeydown: Ze(le((l) => Xe(h), ["prevent"]), ["enter"])
                      }, null, 40, Iy),
                      (uo.value.get(h) || "").trim() ? (i(), u("button", {
                        key: 0,
                        class: "chip-add-btn",
                        onClick: (l) => Xe(h)
                      }, "+", 8, Wy)) : $("", !0)
                    ])
                  ])
                ])) : $("", !0),
                o("div", jy, [
                  K("kw", h, "tags", d.tags.length ? "x" : "") ? $("", !0) : (i(), u("button", {
                    key: 0,
                    class: "meta-toggle-btn mt-tags",
                    onClick: (l) => j("kw", h, "tags")
                  }, "+ Tags", 8, qy)),
                  K("kw", h, "setup", d.setup) ? $("", !0) : (i(), u("button", {
                    key: 1,
                    class: "meta-toggle-btn mt-setup",
                    onClick: (l) => j("kw", h, "setup")
                  }, "+ Setup", 8, zy)),
                  K("kw", h, "teardown", d.teardown) ? $("", !0) : (i(), u("button", {
                    key: 2,
                    class: "meta-toggle-btn mt-teardown",
                    onClick: (l) => j("kw", h, "teardown")
                  }, "+ Teardown", 8, Gy)),
                  K("kw", h, "timeout", d.timeout) ? $("", !0) : (i(), u("button", {
                    key: 3,
                    class: "meta-toggle-btn mt-config",
                    onClick: (l) => j("kw", h, "timeout")
                  }, "+ Timeout", 8, Yy)),
                  K("kw", h, "return", d.returnValue) ? $("", !0) : (i(), u("button", {
                    key: 4,
                    class: "meta-toggle-btn mt-return",
                    onClick: (l) => j("kw", h, "return")
                  }, "+ Return", 8, Xy))
                ]),
                K("kw", h, "setup", d.setup) ? (i(), u("div", Zy, [
                  o("label", Qy, [
                    we(m(p(s)("robotEditor.setup")) + " ", 1),
                    d.setup ? $("", !0) : (i(), u("button", {
                      key: 0,
                      class: "meta-close-btn",
                      onClick: (l) => j("kw", h, "setup")
                    }, "×", 8, Jy))
                  ]),
                  o("div", eb, [
                    o("div", tb, [
                      o("input", {
                        value: ie("kw", h, "setup", d).keyword,
                        onInput: (l) => {
                          ie("kw", h, "setup", d).keyword = l.target.value, mt("kw", h, "setup", d);
                        },
                        class: "form-input step-keyword-input",
                        spellcheck: "false",
                        style: Ee({ width: Math.max(18, Math.min((ie("kw", h, "setup", d).keyword || "").length + 5, 40)) + "ch" }),
                        placeholder: p(s)("robotEditor.setupPlaceholder"),
                        onFocus: (l) => ut("kw", h, "setup", d),
                        onBlur: dt,
                        onKeydown: (l) => wt(l, ie("kw", h, "setup", d))
                      }, null, 44, ob),
                      D.value === ie("kw", h, "setup", d) && xe.value.length > 0 ? (i(), u("div", {
                        key: 0,
                        ref_for: !0,
                        ref_key: "keywordDropdownRef",
                        ref: be,
                        class: "keyword-dropdown"
                      }, [
                        (i(!0), u(U, null, Y(xe.value, (l, E) => {
                          var t;
                          return i(), u("div", {
                            key: l.name,
                            class: te(["keyword-dropdown-item", { active: E === oe.value }]),
                            onMousedown: le((n) => Je("kw", h, "setup", d, l), ["prevent"])
                          }, [
                            o("span", ab, m(l.name), 1),
                            (t = l.args) != null && t.length ? (i(), u("span", sb, m(l.args.map((n) => n.replace(/^[$@&%]\{([^}]+)\}.*$/, "$1")).join(", ")), 1)) : $("", !0)
                          ], 42, nb);
                        }), 128))
                      ], 512)) : $("", !0)
                    ]),
                    ie("kw", h, "setup", d).args.length ? (i(), u("div", lb, [
                      (i(!0), u(U, null, Y(ie("kw", h, "setup", d).args, (l, E) => (i(), u("span", {
                        key: E,
                        class: "step-arg-chip"
                      }, [
                        ke(ie("kw", h, "setup", d))[E] ? (i(), u("span", rb, m(ke(ie("kw", h, "setup", d))[E]), 1)) : $("", !0),
                        o("span", {
                          class: "arg-highlight-wrap",
                          style: Ee({ width: rt(l) })
                        }, [
                          o("span", {
                            class: "arg-highlight-overlay",
                            innerHTML: Ve(l)
                          }, null, 8, ib),
                          o("input", {
                            value: l,
                            onInput: (t) => {
                              ie("kw", h, "setup", d).args[E] = t.target.value, Ke("kw", h, "setup", d);
                            },
                            class: "step-arg-input",
                            spellcheck: "false",
                            style: { width: "100%" },
                            placeholder: ke(ie("kw", h, "setup", d))[E] || p(s)("robotEditor.argPlaceholder")
                          }, null, 40, ub)
                        ], 4),
                        o("button", {
                          class: "chip-remove",
                          onClick: (t) => Ye("kw", h, "setup", d, E)
                        }, "×", 8, cb)
                      ]))), 128))
                    ])) : $("", !0),
                    o("button", {
                      class: "step-btn step-add-arg",
                      onClick: (l) => gt("kw", h, "setup", d),
                      title: p(s)("robotEditor.addArg")
                    }, "+", 8, db)
                  ])
                ])) : $("", !0),
                K("kw", h, "teardown", d.teardown) ? (i(), u("div", pb, [
                  o("label", fb, [
                    we(m(p(s)("robotEditor.teardown")) + " ", 1),
                    d.teardown ? $("", !0) : (i(), u("button", {
                      key: 0,
                      class: "meta-close-btn",
                      onClick: (l) => j("kw", h, "teardown")
                    }, "×", 8, mb))
                  ]),
                  o("div", gb, [
                    o("div", vb, [
                      o("input", {
                        value: ie("kw", h, "teardown", d).keyword,
                        onInput: (l) => {
                          ie("kw", h, "teardown", d).keyword = l.target.value, mt("kw", h, "teardown", d);
                        },
                        class: "form-input step-keyword-input",
                        spellcheck: "false",
                        style: Ee({ width: Math.max(18, Math.min((ie("kw", h, "teardown", d).keyword || "").length + 5, 40)) + "ch" }),
                        placeholder: p(s)("robotEditor.teardownPlaceholder"),
                        onFocus: (l) => ut("kw", h, "teardown", d),
                        onBlur: dt,
                        onKeydown: (l) => wt(l, ie("kw", h, "teardown", d))
                      }, null, 44, hb),
                      D.value === ie("kw", h, "teardown", d) && xe.value.length > 0 ? (i(), u("div", {
                        key: 0,
                        ref_for: !0,
                        ref_key: "keywordDropdownRef",
                        ref: be,
                        class: "keyword-dropdown"
                      }, [
                        (i(!0), u(U, null, Y(xe.value, (l, E) => {
                          var t;
                          return i(), u("div", {
                            key: l.name,
                            class: te(["keyword-dropdown-item", { active: E === oe.value }]),
                            onMousedown: le((n) => Je("kw", h, "teardown", d, l), ["prevent"])
                          }, [
                            o("span", bb, m(l.name), 1),
                            (t = l.args) != null && t.length ? (i(), u("span", wb, m(l.args.map((n) => n.replace(/^[$@&%]\{([^}]+)\}.*$/, "$1")).join(", ")), 1)) : $("", !0)
                          ], 42, yb);
                        }), 128))
                      ], 512)) : $("", !0)
                    ]),
                    ie("kw", h, "teardown", d).args.length ? (i(), u("div", _b, [
                      (i(!0), u(U, null, Y(ie("kw", h, "teardown", d).args, (l, E) => (i(), u("span", {
                        key: E,
                        class: "step-arg-chip"
                      }, [
                        ke(ie("kw", h, "teardown", d))[E] ? (i(), u("span", kb, m(ke(ie("kw", h, "teardown", d))[E]), 1)) : $("", !0),
                        o("span", {
                          class: "arg-highlight-wrap",
                          style: Ee({ width: rt(l) })
                        }, [
                          o("span", {
                            class: "arg-highlight-overlay",
                            innerHTML: Ve(l)
                          }, null, 8, Cb),
                          o("input", {
                            value: l,
                            onInput: (t) => {
                              ie("kw", h, "teardown", d).args[E] = t.target.value, Ke("kw", h, "teardown", d);
                            },
                            class: "step-arg-input",
                            spellcheck: "false",
                            style: { width: "100%" },
                            placeholder: ke(ie("kw", h, "teardown", d))[E] || p(s)("robotEditor.argPlaceholder")
                          }, null, 40, $b)
                        ], 4),
                        o("button", {
                          class: "chip-remove",
                          onClick: (t) => Ye("kw", h, "teardown", d, E)
                        }, "×", 8, Tb)
                      ]))), 128))
                    ])) : $("", !0),
                    o("button", {
                      class: "step-btn step-add-arg",
                      onClick: (l) => gt("kw", h, "teardown", d),
                      title: p(s)("robotEditor.addArg")
                    }, "+", 8, Eb)
                  ])
                ])) : $("", !0),
                K("kw", h, "timeout", d.timeout) || K("kw", h, "return", d.returnValue) ? (i(), u("div", Sb, [
                  K("kw", h, "timeout", d.timeout) ? (i(), u("div", Lb, [
                    o("label", Vb, [
                      we(m(p(s)("robotEditor.timeout")) + " ", 1),
                      d.timeout ? $("", !0) : (i(), u("button", {
                        key: 0,
                        class: "meta-close-btn",
                        onClick: (l) => j("kw", h, "timeout")
                      }, "×", 8, Ab))
                    ]),
                    o("span", Nb, [
                      o("span", {
                        class: "form-hl-overlay",
                        innerHTML: Ve(d.timeout)
                      }, null, 8, Rb),
                      z(o("input", {
                        "onUpdate:modelValue": (l) => d.timeout = l,
                        class: "form-input",
                        style: { width: "100%" },
                        placeholder: p(s)("robotEditor.timeoutPlaceholder"),
                        spellcheck: "false"
                      }, null, 8, Fb), [
                        [ae, d.timeout]
                      ])
                    ])
                  ])) : $("", !0),
                  K("kw", h, "return", d.returnValue) ? (i(), u("div", Pb, [
                    o("label", Db, [
                      we(m(p(s)("robotEditor.returnValue")) + " ", 1),
                      d.returnValue ? $("", !0) : (i(), u("button", {
                        key: 0,
                        class: "meta-close-btn",
                        onClick: (l) => j("kw", h, "return")
                      }, "×", 8, Mb))
                    ]),
                    o("span", xb, [
                      o("span", {
                        class: "form-hl-overlay",
                        innerHTML: Ve(d.returnValue)
                      }, null, 8, Ub),
                      z(o("input", {
                        "onUpdate:modelValue": (l) => d.returnValue = l,
                        class: "form-input",
                        style: { width: "100%" },
                        placeholder: p(s)("robotEditor.returnValuePlaceholder"),
                        spellcheck: "false"
                      }, null, 8, Bb), [
                        [ae, d.returnValue]
                      ])
                    ])
                  ])) : $("", !0)
                ])) : $("", !0),
                o("div", Kb, [
                  o("label", Hb, m(p(s)("robotEditor.steps")), 1),
                  o("div", Ob, [
                    (i(!0), u(U, null, Y(d.steps, (l, E) => (i(), u("div", {
                      key: E,
                      class: "step-row",
                      style: Ee({ paddingLeft: Pt(d.steps, E) * 24 + "px" })
                    }, [
                      o("span", Ib, m(E + 1) + ".", 1),
                      z(o("select", {
                        "onUpdate:modelValue": (t) => l.type = t,
                        class: te(["step-type-select", rn(l.type)]),
                        onChange: (t) => sn(l)
                      }, [
                        (i(!0), u(U, null, Y(p(Ta), (t) => (i(), u("option", {
                          key: t,
                          value: t
                        }, m(Ft(t)), 9, jb))), 128))
                      ], 42, Wb), [
                        [it, l.type]
                      ]),
                      l.type === "keyword" ? (i(), u(U, { key: 0 }, [
                        o("div", qb, [
                          z(o("input", {
                            "onUpdate:modelValue": (t) => l.keyword = t,
                            class: "form-input step-keyword-input",
                            spellcheck: "false",
                            style: Ee({ width: Math.max(18, Math.min((l.keyword || "").length + 5, 60)) + "ch" }),
                            placeholder: p(s)("robotEditor.keywordPlaceholder"),
                            onFocus: (t) => It(l),
                            onBlur: dt,
                            onKeydown: (t) => wt(t, l),
                            onInput: (t) => Wt(l)
                          }, null, 44, zb), [
                            [ae, l.keyword]
                          ]),
                          D.value === l && xe.value.length > 0 ? (i(), u("div", {
                            key: 0,
                            ref_for: !0,
                            ref_key: "keywordDropdownRef",
                            ref: be,
                            class: "keyword-dropdown"
                          }, [
                            (i(!0), u(U, null, Y(xe.value, (t, n) => {
                              var v;
                              return i(), u("div", {
                                key: t.name,
                                class: te(["keyword-dropdown-item", { active: n === oe.value }]),
                                onMousedown: le((F) => Dt(l, t), ["prevent"])
                              }, [
                                o("span", Yb, m(t.name), 1),
                                (v = t.args) != null && v.length ? (i(), u("span", Xb, m(t.args.map((F) => F.replace(/^[$@&%]\{([^}]+)\}.*$/, "$1")).join(", ")), 1)) : $("", !0),
                                o("span", {
                                  class: te(["kw-suggestion-source", "source-" + (t.source === "builtin" ? "builtin" : t.source === "local" || t.source === "project" ? "local" : "library")])
                                }, m(t.source === "builtin" ? p(s)("robotEditor.builtinKeyword") : t.source === "local" || t.source === "project" ? p(s)("robotEditor.localKeyword") : t.source), 3)
                              ], 42, Gb);
                            }), 128))
                          ], 512)) : $("", !0)
                        ]),
                        l.args.length ? (i(), u("div", Zb, [
                          (i(!0), u(U, null, Y(l.args, (t, n) => (i(), u("span", {
                            key: n,
                            class: "step-arg-chip arg-ac-wrapper"
                          }, [
                            ke(l)[n] ? (i(), u("span", Qb, m(ke(l)[n]), 1)) : $("", !0),
                            o("span", {
                              class: "arg-highlight-wrap",
                              style: Ee({ width: rt(l.args[n]) })
                            }, [
                              o("span", {
                                class: "arg-highlight-overlay",
                                innerHTML: Ve(l.args[n])
                              }, null, 8, Jb),
                              z(o("input", {
                                "onUpdate:modelValue": (v) => l.args[n] = v,
                                class: "step-arg-input",
                                spellcheck: "false",
                                onScroll: jt,
                                style: { width: "100%" },
                                placeholder: ke(l)[n] || p(s)("robotEditor.argPlaceholder"),
                                onInput: (v) => so(v.target.value, `${E}-${n}`),
                                onKeydown: (v) => ho(v, l, n),
                                onBlur: lo,
                                autocomplete: "off"
                              }, null, 40, ew), [
                                [ae, l.args[n]]
                              ])
                            ], 4),
                            Le.value === `${E}-${n}` && ce.value.length ? (i(), u("div", tw, [
                              (i(!0), u(U, null, Y(ce.value, (v, F) => (i(), u("div", {
                                key: v,
                                class: te(["arg-ac-item", { active: F === ge.value }]),
                                onMousedown: le((X) => _t(l, n, v), ["prevent"])
                              }, m(v), 43, ow))), 128))
                            ])) : $("", !0),
                            o("button", {
                              class: "chip-remove",
                              onClick: (v) => Ue(l, n)
                            }, "×", 8, nw)
                          ]))), 128))
                        ])) : $("", !0),
                        o("button", {
                          class: te(["step-btn step-add-arg", { "has-hint": tt(l) !== "+" }]),
                          onClick: (t) => bt(l),
                          title: p(s)("robotEditor.addArg")
                        }, m(tt(l)), 11, aw)
                      ], 64)) : l.type === "assignment" ? (i(), u(U, { key: 1 }, [
                        (i(!0), u(U, null, Y(l.returnVars, (t, n) => (i(), u("span", {
                          key: n,
                          class: "step-var-chip"
                        }, [
                          z(o("input", {
                            "onUpdate:modelValue": (v) => l.returnVars[n] = v,
                            class: "step-var-input",
                            spellcheck: "false",
                            placeholder: "${var}",
                            onBlur: (v) => yo(l, n)
                          }, null, 40, sw), [
                            [ae, l.returnVars[n]]
                          ]),
                          o("button", {
                            class: "chip-remove",
                            onClick: (v) => ao(l, n)
                          }, "×", 8, lw)
                        ]))), 128)),
                        o("button", {
                          class: "step-btn step-add-var",
                          onClick: (t) => an(l),
                          title: "+var"
                        }, "+v", 8, rw),
                        y[18] || (y[18] = o("span", { class: "step-assign-eq" }, "=", -1)),
                        o("div", iw, [
                          z(o("input", {
                            "onUpdate:modelValue": (t) => l.keyword = t,
                            class: "form-input step-keyword-input",
                            spellcheck: "false",
                            style: Ee({ width: Math.max(18, Math.min((l.keyword || "").length + 5, 60)) + "ch" }),
                            placeholder: p(s)("robotEditor.keywordPlaceholder"),
                            onFocus: (t) => It(l),
                            onBlur: dt,
                            onKeydown: (t) => wt(t, l),
                            onInput: (t) => Wt(l)
                          }, null, 44, uw), [
                            [ae, l.keyword]
                          ]),
                          D.value === l && xe.value.length > 0 ? (i(), u("div", {
                            key: 0,
                            ref_for: !0,
                            ref_key: "keywordDropdownRef",
                            ref: be,
                            class: "keyword-dropdown"
                          }, [
                            (i(!0), u(U, null, Y(xe.value, (t, n) => {
                              var v;
                              return i(), u("div", {
                                key: t.name,
                                class: te(["keyword-dropdown-item", { active: n === oe.value }]),
                                onMousedown: le((F) => Dt(l, t), ["prevent"])
                              }, [
                                o("span", dw, m(t.name), 1),
                                (v = t.args) != null && v.length ? (i(), u("span", pw, m(t.args.map((F) => F.replace(/^[$@&%]\{([^}]+)\}.*$/, "$1")).join(", ")), 1)) : $("", !0),
                                o("span", {
                                  class: te(["kw-suggestion-source", "source-" + (t.source === "builtin" ? "builtin" : t.source === "local" || t.source === "project" ? "local" : "library")])
                                }, m(t.source === "builtin" ? p(s)("robotEditor.builtinKeyword") : t.source === "local" || t.source === "project" ? p(s)("robotEditor.localKeyword") : t.source), 3)
                              ], 42, cw);
                            }), 128))
                          ], 512)) : $("", !0)
                        ]),
                        l.args.length ? (i(), u("div", fw, [
                          (i(!0), u(U, null, Y(l.args, (t, n) => (i(), u("span", {
                            key: n,
                            class: "step-arg-chip arg-ac-wrapper"
                          }, [
                            ke(l)[n] ? (i(), u("span", mw, m(ke(l)[n]), 1)) : $("", !0),
                            o("span", {
                              class: "arg-highlight-wrap",
                              style: Ee({ width: rt(l.args[n]) })
                            }, [
                              o("span", {
                                class: "arg-highlight-overlay",
                                innerHTML: Ve(l.args[n])
                              }, null, 8, gw),
                              z(o("input", {
                                "onUpdate:modelValue": (v) => l.args[n] = v,
                                class: "step-arg-input",
                                spellcheck: "false",
                                onScroll: jt,
                                style: { width: "100%" },
                                placeholder: ke(l)[n] || p(s)("robotEditor.argPlaceholder"),
                                onInput: (v) => so(v.target.value, `${E}-${n}`),
                                onKeydown: (v) => ho(v, l, n),
                                onBlur: lo,
                                autocomplete: "off"
                              }, null, 40, vw), [
                                [ae, l.args[n]]
                              ])
                            ], 4),
                            Le.value === `${E}-${n}` && ce.value.length ? (i(), u("div", hw, [
                              (i(!0), u(U, null, Y(ce.value, (v, F) => (i(), u("div", {
                                key: v,
                                class: te(["arg-ac-item", { active: F === ge.value }]),
                                onMousedown: le((X) => _t(l, n, v), ["prevent"])
                              }, m(v), 43, yw))), 128))
                            ])) : $("", !0),
                            o("button", {
                              class: "chip-remove",
                              onClick: (v) => Ue(l, n)
                            }, "×", 8, bw)
                          ]))), 128))
                        ])) : $("", !0),
                        o("button", {
                          class: te(["step-btn step-add-arg", { "has-hint": tt(l) !== "+" }]),
                          onClick: (t) => bt(l),
                          title: p(s)("robotEditor.addArg")
                        }, m(tt(l)), 11, ww)
                      ], 64)) : l.type === "var" ? (i(), u(U, { key: 2 }, [
                        o("span", _w, [
                          z(o("input", {
                            "onUpdate:modelValue": (t) => l.returnVars[0] = t,
                            class: "step-var-input",
                            spellcheck: "false",
                            placeholder: "${var}",
                            onBlur: (t) => yo(l, 0)
                          }, null, 40, kw), [
                            [ae, l.returnVars[0]]
                          ])
                        ]),
                        y[20] || (y[20] = o("span", { class: "step-assign-eq" }, "=", -1)),
                        (i(!0), u(U, null, Y(l.args, (t, n) => (i(), u("span", {
                          key: n,
                          class: "step-arg-chip"
                        }, [
                          o("span", {
                            class: "arg-highlight-wrap",
                            style: Ee({ width: rt(l.args[n]) })
                          }, [
                            o("span", {
                              class: "arg-highlight-overlay",
                              innerHTML: Ve(l.args[n])
                            }, null, 8, Cw),
                            z(o("input", {
                              "onUpdate:modelValue": (v) => l.args[n] = v,
                              class: "step-arg-input",
                              spellcheck: "false",
                              onScroll: jt,
                              style: { width: "100%" },
                              placeholder: p(s)("robotEditor.valuePlaceholder")
                            }, null, 40, $w), [
                              [ae, l.args[n]]
                            ])
                          ], 4),
                          o("button", {
                            class: "chip-remove",
                            onClick: (v) => Ue(l, n)
                          }, "×", 8, Tw)
                        ]))), 128)),
                        o("button", {
                          class: "step-btn step-add-arg",
                          onClick: (t) => bt(l),
                          title: p(s)("robotEditor.addValue")
                        }, "+", 8, Ew),
                        z(o("select", {
                          "onUpdate:modelValue": (t) => l.varScope = t,
                          class: "step-scope-select"
                        }, [
                          y[19] || (y[19] = o("option", { value: "" }, "scope", -1)),
                          (i(!0), u(U, null, Y(p(Ea), (t) => (i(), u("option", {
                            key: t,
                            value: t
                          }, m(t), 9, Lw))), 128))
                        ], 8, Sw), [
                          [it, l.varScope]
                        ])
                      ], 64)) : l.type === "for" ? (i(), u(U, { key: 3 }, [
                        o("span", Vw, [
                          o("span", {
                            class: "form-hl-overlay hl-mono",
                            innerHTML: Ve(l.loopVar)
                          }, null, 8, Aw),
                          z(o("input", {
                            "onUpdate:modelValue": (t) => l.loopVar = t,
                            class: "form-input step-var-input-inline",
                            spellcheck: "false",
                            style: { width: "100%" },
                            placeholder: "${item}"
                          }, null, 8, Nw), [
                            [ae, l.loopVar]
                          ])
                        ]),
                        z(o("select", {
                          "onUpdate:modelValue": (t) => l.loopFlavor = t,
                          class: "step-flavor-select"
                        }, [
                          (i(!0), u(U, null, Y(p(ta), (t) => (i(), u("option", {
                            key: t,
                            value: t
                          }, m(t), 9, Fw))), 128))
                        ], 8, Rw), [
                          [it, l.loopFlavor]
                        ]),
                        (i(!0), u(U, null, Y(l.loopValues, (t, n) => (i(), u("span", {
                          key: n,
                          class: "step-arg-chip"
                        }, [
                          z(o("input", {
                            "onUpdate:modelValue": (v) => l.loopValues[n] = v,
                            class: "step-arg-input",
                            placeholder: p(s)("robotEditor.valuePlaceholder")
                          }, null, 8, Pw), [
                            [ae, l.loopValues[n]]
                          ]),
                          o("button", {
                            class: "chip-remove",
                            onClick: (v) => go(l, n)
                          }, "×", 8, Dw)
                        ]))), 128)),
                        o("button", {
                          class: "step-btn step-add-arg",
                          onClick: (t) => Ao(l),
                          title: p(s)("robotEditor.addValue")
                        }, "+", 8, Mw)
                      ], 64)) : l.type === "if" || l.type === "else_if" || l.type === "while" ? (i(), u("span", xw, [
                        o("span", {
                          class: "form-hl-overlay hl-condition",
                          innerHTML: Ve(l.condition)
                        }, null, 8, Uw),
                        z(o("input", {
                          "onUpdate:modelValue": (t) => l.condition = t,
                          class: "form-input step-condition-input",
                          style: { width: "100%" },
                          placeholder: p(s)("robotEditor.conditionPlaceholder"),
                          spellcheck: "false"
                        }, null, 8, Bw), [
                          [ae, l.condition]
                        ])
                      ])) : l.type === "except" ? (i(), u(U, { key: 5 }, [
                        z(o("input", {
                          "onUpdate:modelValue": (t) => l.exceptPattern = t,
                          class: "form-input step-keyword-input",
                          spellcheck: "false",
                          placeholder: p(s)("robotEditor.exceptPatternPlaceholder")
                        }, null, 8, Kw), [
                          [ae, l.exceptPattern]
                        ]),
                        l.exceptVar || l.exceptPattern ? (i(), u("span", Hw, "AS")) : $("", !0),
                        l.exceptVar || l.exceptPattern ? (i(), u("span", Ow, [
                          o("span", {
                            class: "form-hl-overlay hl-mono",
                            innerHTML: Ve(l.exceptVar)
                          }, null, 8, Iw),
                          z(o("input", {
                            "onUpdate:modelValue": (t) => l.exceptVar = t,
                            class: "form-input step-var-input-inline",
                            spellcheck: "false",
                            style: { width: "100%" },
                            placeholder: "${error}"
                          }, null, 8, Ww), [
                            [ae, l.exceptVar]
                          ])
                        ])) : $("", !0)
                      ], 64)) : l.type === "return" ? (i(), u(U, { key: 6 }, [
                        (i(!0), u(U, null, Y(l.args, (t, n) => (i(), u("span", {
                          key: n,
                          class: "step-arg-chip"
                        }, [
                          z(o("input", {
                            "onUpdate:modelValue": (v) => l.args[n] = v,
                            class: "step-arg-input",
                            placeholder: p(s)("robotEditor.valuePlaceholder")
                          }, null, 8, jw), [
                            [ae, l.args[n]]
                          ]),
                          o("button", {
                            class: "chip-remove",
                            onClick: (v) => Ue(l, n)
                          }, "×", 8, qw)
                        ]))), 128)),
                        o("button", {
                          class: "step-btn step-add-arg",
                          onClick: (t) => bt(l),
                          title: p(s)("robotEditor.addValue")
                        }, "+", 8, zw)
                      ], 64)) : l.type === "comment" ? z((i(), u("input", {
                        key: 7,
                        "onUpdate:modelValue": (t) => l.comment = t,
                        class: "form-input flex-1 step-comment-input",
                        spellcheck: "false",
                        placeholder: "# ..."
                      }, null, 8, Gw)), [
                        [ae, l.comment]
                      ]) : $("", !0),
                      o("div", Yw, [
                        o("button", {
                          class: "step-btn",
                          onClick: (t) => no(d.steps, E, -1),
                          disabled: E === 0
                        }, "↑", 8, Xw),
                        o("button", {
                          class: "step-btn",
                          onClick: (t) => no(d.steps, E, 1),
                          disabled: E === d.steps.length - 1
                        }, "↓", 8, Zw),
                        o("button", {
                          class: "step-btn danger",
                          onClick: (t) => nn(d.steps, E),
                          title: p(s)("editor.common.delete")
                        }, "×", 8, Qw)
                      ])
                    ], 4))), 128)),
                    o("div", Jw, [
                      o("button", {
                        class: "add-step-btn",
                        onClick: (l) => mo(d.steps)
                      }, "+ " + m(p(s)("robotEditor.addStep")), 9, e_),
                      o("button", {
                        class: "add-step-btn add-block",
                        onClick: (l) => mo(d.steps, "var")
                      }, "+ VAR", 8, t_),
                      o("button", {
                        class: "add-step-btn add-block",
                        onClick: (l) => st(d.steps, "for")
                      }, "+ FOR", 8, o_),
                      o("button", {
                        class: "add-step-btn add-block",
                        onClick: (l) => st(d.steps, "if")
                      }, "+ IF", 8, n_),
                      o("button", {
                        class: "add-step-btn add-block",
                        onClick: (l) => st(d.steps, "while")
                      }, "+ WHILE", 8, a_),
                      o("button", {
                        class: "add-step-btn add-block",
                        onClick: (l) => st(d.steps, "try")
                      }, "+ TRY", 8, s_)
                    ])
                  ])
                ])
              ], 512), [
                [Xt, !Q.value.has(h)]
              ])
            ]))), 128))
          ], 512), [
            [Xt, !x.value]
          ])
        ])
      ], 512), [
        [Xt, b.value === "visual"]
      ]),
      b.value === "flow" ? (i(), u("div", l_, [
        ve(Wf, {
          form: ee,
          sidecar: q.value,
          "onUpdate:sidecar": Xo,
          onAddTestCase: Fo,
          onAddKeyword: Po,
          onLibrariesChanged: Eo
        }, null, 8, ["form", "sidecar"])
      ])) : $("", !0),
      z(o("div", {
        class: "code-editor",
        ref_key: "codeEditorContainer",
        ref: V
      }, null, 512), [
        [Xt, b.value === "code"]
      ]),
      ve(jo, {
        modelValue: ct.value,
        "onUpdate:modelValue": y[8] || (y[8] = (d) => ct.value = d),
        title: p(s)("robotEditor.libraryInstallDialogTitle", { library: to.value }),
        size: "sm"
      }, {
        footer: _e(() => [
          ve(Oe, {
            variant: "secondary",
            disabled: Nt.value,
            onClick: yt
          }, {
            default: _e(() => [
              we(m(p(s)("editor.common.cancel")), 1)
            ]),
            _: 1
          }, 8, ["disabled"]),
          ve(Oe, {
            variant: "primary",
            disabled: Nt.value || !Ot.value.trim(),
            "data-testid": "lib-install-confirm",
            onClick: Vn
          }, {
            default: _e(() => [
              we(m(Nt.value ? p(s)("robotEditor.libraryInstallDialogInstalling") : p(s)("robotEditor.libraryInstallDialogConfirm")), 1)
            ]),
            _: 1
          }, 8, ["disabled"])
        ]),
        default: _e(() => [
          o("p", r_, m(p(s)("robotEditor.libraryInstallDialogIntro", {
            library: to.value,
            env: Qo.value
          })), 1),
          o("label", i_, [
            we(m(p(s)("robotEditor.libraryInstallDialogPipName")) + " ", 1),
            z(o("input", {
              "onUpdate:modelValue": y[7] || (y[7] = (d) => Ot.value = d),
              type: "text",
              class: "lib-install-pkg-input",
              autocomplete: "off",
              spellcheck: "false",
              "data-testid": "lib-install-pkg-input",
              disabled: Nt.value
            }, null, 8, u_), [
              [ae, Ot.value]
            ]),
            o("small", c_, m(p(s)("robotEditor.libraryInstallDialogPipHint")), 1)
          ]),
          oo.value ? (i(), u("div", d_, m(oo.value), 1)) : $("", !0)
        ]),
        _: 1
      }, 8, ["modelValue", "title"])
    ], 6));
  }
}), R_ = /* @__PURE__ */ ze(p_, [["__scopeId", "data-v-18099ffe"]]), f_ = {
  robotEditor: {
    visualTab: "可视化编辑器",
    flowTab: "流程",
    codeTab: "代码",
    heal: {
      toggleOn: "自愈：开",
      toggleOff: "自愈：关",
      toggleOnHint: "单击可将此文件中的每个 Heal* 关键字还原为其原始的 Browser 库形式，并移除 RoboScopeHeal 导入。",
      toggleOffHint: "单击可将每个 Browser 关键字（Click、Fill Text 等）提升为其 Heal 变体，并添加 RoboScopeHeal 库导入。",
      toastEnabled: "已将 {count} 个关键字切换为自愈。",
      toastDisabled: "已将 {count} 个 Heal 关键字还原为其原始形式。"
    },
    libraryNotInEnvTitle: '库 "{library}" 未安装在环境中',
    libraryNotInEnvBody: "库导入已添加到文件，但 {library} 未安装在此仓库的环境中。在你安装该软件包之前，仅显示示例关键字——请打开“环境”视图进行添加。",
    libraryNoEnvAtAllBody: "库导入已添加到 {library}，但此仓库未配置环境。请在“环境”视图中配置一个以安装库。",
    libraryInstallDialogTitle: '安装 "{library}"？',
    libraryInstallDialogIntro: '库 "{library}" 已导入，但未安装在环境 "{env}" 中。是否立即通过 pip 安装它，以便其完整的关键字列表显示在调色板中？',
    libraryInstallDialogPipName: "Pip 软件包名称",
    libraryInstallDialogPipHint: "如果自动检测的名称有误，你可以编辑此处。",
    libraryInstallDialogConfirm: "安装",
    libraryInstallDialogInstalling: "正在安装…",
    libraryInstallStartedTitle: "已开始安装",
    libraryInstallStartedBody: "{package} 正在安装。安装完成后调色板将刷新。",
    libraryInstallFailed: "安装失败。",
    libraryAlreadyProvidedTitle: '库 "{library}" 已可用',
    libraryAlreadyProvidedBody: '{library} 由 "{provider}" 提供，且其已安装在环境中。关键字缓存可能已过时——请尝试重启 rf-mcp 服务器（设置 → AI → RF MCP），或稍候片刻并切回此文件以刷新。',
    tests: "测试",
    keywordsCount: "关键字",
    initFileWarning: "套件初始化文件（__init__.robot）不能包含测试用例部分。",
    parseError: "解析错误",
    expandAll: "全部展开",
    collapseAll: "全部折叠",
    settingsSection: "设置",
    variablesSection: "变量",
    testCasesSection: "测试用例",
    keywordsSection: "关键字",
    addSetting: "添加设置",
    addVariable: "添加变量",
    addTestCase: "添加测试用例",
    addKeyword: "添加关键字",
    addStep: "添加步骤",
    addTag: "添加标签...",
    noSettings: "尚未定义设置。单击“添加设置”以添加一个。",
    noVariables: "尚未定义变量。单击“添加变量”以添加一个。",
    noTestCases: "尚无测试用例。单击“添加测试用例”以创建一个。",
    noKeywords: "尚未定义关键字。单击“添加关键字”以创建一个。",
    unnamedTestCase: "未命名的测试用例",
    unnamedKeyword: "未命名的关键字",
    steps: "步骤",
    testCaseName: "测试用例名称",
    testCaseNamePlaceholder: "我的测试用例",
    keywordName: "关键字名称",
    keywordNamePlaceholder: "我的关键字",
    documentation: "文档",
    documentationPlaceholder: "可选文档...",
    tags: "标签",
    setup: "Setup",
    setupPlaceholder: "Setup 关键字（可选）",
    teardown: "Teardown",
    teardownPlaceholder: "Teardown 关键字（可选）",
    timeout: "超时",
    timeoutPlaceholder: "例如 30s",
    template: "模板",
    templatePlaceholder: "模板关键字（可选）",
    arguments: "参数",
    argumentsPlaceholder: "${arg1}    ${arg2}",
    addArgument: "添加参数，例如 ${name}",
    argTypes: {
      required: "必需",
      optional: "可选",
      varargs: "可变参数",
      kwargs: "键值",
      namedOnly: "仅命名",
      namedOnlyBelow: "以下为仅命名参数",
      namePlaceholder: "name",
      defaultPlaceholder: "默认值",
      addDefault: "添加默认值"
    },
    returnValue: "返回值",
    returnValuePlaceholder: "${result}",
    stepPlaceholder: "Log    Hello World",
    settingValuePlaceholder: "值",
    argPlaceholder: "arg",
    variableNamePlaceholder: "${VARIABLE_NAME}",
    variableValuePlaceholder: "值",
    keywordPlaceholder: "关键字名称",
    conditionPlaceholder: "${x} > 5",
    valuePlaceholder: "值",
    exceptPatternPlaceholder: "错误模式",
    addArg: "添加参数",
    addValue: "添加值",
    settingType: {
      Documentation: "Documentation",
      Library: "Library",
      Resource: "Resource",
      Variables: "Variables",
      SuiteSetup: "Suite Setup",
      SuiteTeardown: "Suite Teardown",
      TestSetup: "Test Setup",
      TestTeardown: "Test Teardown",
      TestTemplate: "Test Template",
      TestTimeout: "Test Timeout",
      ForceTags: "Force Tags",
      DefaultTags: "Default Tags",
      Metadata: "Metadata"
    },
    stepType: {
      keyword: "关键字",
      assignment: "赋值",
      var: "VAR",
      comment: "注释",
      for: "FOR",
      if: "IF",
      else_if: "ELSE IF",
      else: "ELSE",
      while: "WHILE",
      try: "TRY",
      except: "EXCEPT",
      finally: "FINALLY",
      end: "END",
      break: "BREAK",
      continue: "CONTINUE",
      return: "RETURN"
    },
    builtinKeyword: "内置",
    localKeyword: "本地",
    noSuggestions: "没有匹配的关键字"
  },
  flowEditor: {
    noTestCasesYet: "此文件中尚无测试用例。",
    noKeywordsYet: "此文件中尚无关键字。",
    addTestCase: "测试用例",
    addTestCaseTitle: "添加一个新的空测试用例",
    addKeyword: "关键字",
    addKeywordTitle: "添加一个新的空关键字（用户自定义关键字）",
    libraries: "库",
    librariesTitle: "管理此文件的库和资源导入",
    librariesNone: "尚无库或资源导入。",
    libraryAdd: "添加",
    libraryInputPlaceholder: "库名称（例如 Browser）或 resource/path.resource",
    libraryRemoveTitle: "移除 {name}",
    variables: "变量",
    variablesTitle: "为此文件定义套件变量（*** Variables ***）",
    variablesNone: "尚未定义变量。",
    variableNamePlaceholder: "${NAME}",
    variableValuePlaceholder: "值",
    variableAdd: "添加",
    variableRemoveTitle: "移除 {name}",
    envVarsUsed: "已使用的环境变量：",
    envVarDefined: "已在环境 {env} 中定义",
    envVarDefaultUsed: "环境 {env} 中未定义，已使用行内默认值",
    envVarMissing: "环境 {env} 中未定义；除非执行端自己的环境提供该变量，否则运行会失败。",
    envVarCheckedAgainst: "对照环境 {env} 校验",
    envVarNotDefined: "（未定义）",
    templateAddRow: "添加行",
    templateAddColumn: "添加列",
    templateRemoveRow: "移除行",
    suiteSettings: "设置",
    suiteSettingsTitle: "套件设置（Setup/Teardown、Tags、Documentation、Metadata）",
    suiteSettingsNone: "尚无套件设置。",
    suiteSettingRemoveTitle: "移除 {name}",
    keywordNotImportedHint: '库 "{library}" 未在此文件中导入。选择此关键字将自动添加该导入。',
    keywordNotImportedBadgeTitle: "添加此关键字时将自动导入该库。",
    examplesCategoryBadge: "（示例）",
    examplesCategoryHint: "由于动态库自省未返回任何数据，因此显示精选子集——通常是因为该仓库未配置环境，或该库未安装在环境中。配置环境以查看完整的关键字列表。",
    currentFileCategoryBadge: "此文件",
    currentFileCategoryHint: "在你当前正在编辑的文件中定义的关键字。",
    resourcesSectionLabel: "你的资源",
    resourcesSectionHint: "来自本仓库 .robot / .resource 文件的关键词。插入时会自动补上对应的 Resource 导入。",
    resourcePanelLabel: "资源",
    librariesPanelLabel: "库",
    importAdded: {
      resourceTitle: "已导入资源",
      libraryTitle: "已导入库",
      message: "已将 {name} 加入 *** Settings ***，运行时即可解析该关键词。"
    },
    sort: {
      title: "关键词排序",
      mostUsed: "最常用优先",
      alpha: "A–Z",
      importedFirst: "已导入优先"
    },
    filter: {
      title: "筛选显示范围",
      resources: "你的资源",
      importedLibs: "已导入的库",
      exampleLibs: "示例库（未安装）",
      builtin: "内置库（BuiltIn）",
      hidingCategories: "已隐藏 {count} 类",
      clear: "全部显示"
    },
    resizePanelHint: "拖动以调整详情面板大小",
    startNodeLabel: "开始",
    endNodeLabel: "结束",
    keyword: "关键字",
    arguments: "参数",
    condition: "条件",
    returnVars: "返回变量",
    returnValues: "返回值",
    returnValuePlaceholder: '${result}、${count}、"literal"…',
    returnNoValue: "（无值）",
    docMeta: {
      label: "文档",
      placeholder: "描述此测试用例（或关键字）的作用——将显示在 HTML 报告中。",
      hint: "多行文本在保存的 .robot 文件中以 `...` 续行的形式保留。",
      addTitle: "为此测试用例/关键字添加 [Documentation] 旁注",
      removeTitle: "移除文档",
      alreadyPresent: "已附加 [Documentation]——单击旁注进行编辑。"
    },
    debug: {
      btnLabel: "运行到此处",
      btnTitle: "运行此测试直至所选步骤，并在调试模式下暂停",
      unresolvedLine: "无法解析此步骤的源代码行——请保存文件后重试。",
      startFailed: "无法启动调试会话。",
      unsavedHintTitle: "调试前请先保存",
      unsavedHint: "你有未保存的更改。请先保存文件，以使调试断点与磁盘上的源代码匹配。",
      confirmReplaceTitle: "替换活动的调试会话？",
      confirmReplace: "此文件的调试会话已在第 {lineFrom} 行暂停。是否断开它并在第 {lineTo} 行启动新会话？",
      confirmReplaceConfirm: "断开并启动"
    },
    heal: {
      toggleLabel: "自愈",
      toggleHint: "将此 Browser 关键字替换为其 Heal 变体（页面布局漂移时定位符会自动恢复）。"
    },
    settingMeta: {
      allPresent: "每个 [...] 设置均已附加——单击旁注进行编辑。",
      emptyHint: "单击以编辑",
      documentation: {
        label: "文档",
        placeholder: "描述此测试用例（或关键字）的作用——将显示在 HTML 报告中。",
        hint: "多行文本在保存的 .robot 文件中以 `...` 续行的形式保留。",
        addTitle: "添加 [Documentation] 旁注",
        removeTitle: "移除文档"
      },
      tags: {
        label: "标签",
        placeholder: "smoke, regression, slow",
        hint: "以逗号分隔。标签可在运行时通过 --include / --exclude 进行选择/排除。",
        addTitle: "为此测试用例/关键字添加 [Tags]",
        removeTitle: "移除标签"
      },
      arguments: {
        label: "参数",
        placeholder: "${name}, ${greeting}=Hello",
        hint: "以逗号分隔。可选参数使用 ${name}=default，可变参数使用 @{name}。",
        addTitle: "为此关键字添加 [Arguments]",
        removeTitle: "移除参数"
      },
      setup: {
        label: "Setup",
        placeholder: "Setup 关键字名称",
        hint: "在此测试用例/关键字主体之前执行的 Robot 关键字。会覆盖 Test Setup。",
        addTitle: "添加 [Setup] 钩子",
        removeTitle: "移除 setup"
      },
      teardown: {
        label: "Teardown",
        placeholder: "Teardown 关键字名称",
        hint: "在主体之后执行的 Robot 关键字，即使失败也会执行。会覆盖 Test Teardown。",
        addTitle: "添加 [Teardown] 钩子",
        removeTitle: "移除 teardown"
      },
      template: {
        label: "模板",
        placeholder: "模板关键字名称",
        hint: "测试主体中的每一行都会成为以该行为参数对此关键字的一次调用。",
        addTitle: "添加 [Template]（数据驱动测试）",
        removeTitle: "移除模板"
      },
      timeout: {
        label: "超时",
        placeholder: "30s、5 minutes",
        hint: "此测试用例/关键字被强制中止前的最大运行时间。",
        addTitle: "添加 [Timeout]",
        removeTitle: "移除超时"
      }
    },
    testCaseSettings: "测试用例设置",
    keywordSettings: "关键字设置",
    comment: "注释",
    palette: "关键字",
    searchKeywords: "搜索关键字...",
    noResults: "没有匹配的关键字。",
    addArg: "添加参数",
    addVar: "添加变量",
    addValue: "添加值",
    loopVar: "循环变量",
    loopFlavor: "循环类型",
    loopValues: "值",
    exceptPattern: "错误模式",
    exceptVar: "错误变量",
    varName: "变量名称",
    varScope: "作用域",
    selector: {
      tooltipHasCandidates: "{count} 个已录制的候选项——单击以切换",
      customValueHint: "（自定义值，非来自录制）",
      candidatesBadge: "× {count}",
      replaceCustomConfirm: `是否将你的自定义定位符

  {current}

替换为已录制的候选项

  {next}
？`
    },
    argLabels: {
      fallback: "参数 {n}",
      extraPositional: "额外的位置参数",
      extraNamed: "额外的命名参数",
      defaultPlaceholder: "默认值：{default}"
    },
    addArgPicker: {
      custom: "自定义值 …",
      customLabel: "自定义值",
      customPlaceholder: "value 或 name=value",
      customAdd: "添加",
      noUnused: "所有参数均已添加。",
      namedHint: "命名"
    },
    docModal: {
      title: "关键字文档",
      argsHeader: "参数",
      noDoc: "无可用文档。",
      button: "显示文档",
      loading: "正在加载文档…"
    },
    argTypes: {
      text: "文本",
      integer: "整数",
      number: "数字",
      yesNo: "开 / 关",
      duration: "持续时间",
      durationHint: "例如 5s、1min、00:00:05",
      path: "文件路径",
      any: "任意",
      choice: "选项",
      collection: "列表 / 映射",
      unknown: "值",
      optionalSuffix: "（可选）",
      customValue: "自定义",
      toggleToText: "切换到文本输入（例如用于变量）",
      toggleBackToTyped: "切换回类型化输入"
    }
  },
  debug: {
    btn: {
      label: "调试",
      tooltip: "重新运行此测试并在失败行暂停"
    },
    panel: {
      paused: "已暂停",
      terminated: "已终止",
      notPaused: "等待首次停止…",
      lineLabel: "行",
      callStack: "调用栈",
      callStackEmpty: "尚无调用栈 — 执行尚未暂停。",
      scopes: "变量",
      scopesEmpty: "无可用作用域。",
      scopeEmpty: "此作用域中无变量。",
      output: "输出",
      outputEmpty: "尚无输出。",
      toolbar: {
        continue: "继续",
        stepOver: "单步跳过",
        stepIn: "单步进入",
        stepOut: "单步跳出",
        stop: "停止"
      }
    },
    error: {
      title: "调试会话出错",
      startFailed: "无法启动调试会话。",
      commandFailed: "调试命令失败。"
    },
    prereq: {
      title: "调试器未安装",
      body: "{package} 未安装在本项目的 Python 环境中。本平台需要它来运行交互式调试。现在安装吗？",
      install: "安装",
      cancel: "取消",
      installing: "正在安装 {package}…",
      installFailed: "安装失败。详情请查看日志。",
      installSuccess: "{package} 安装成功。"
    }
  },
  recorder: {
    title: "录制",
    record: "录制",
    startRecording: "录制浏览器交互",
    stop: "停止录制",
    events: "个事件",
    generating: "正在生成 .robot 文件…",
    newRecording: "新建录制",
    tryAgain: "重试",
    cancelled: "录制已取消",
    waitingForEvents: "正在等待浏览器交互…",
    generatedFile: "已生成的 .robot 文件",
    completed: "录制完成",
    completedMsg: "Robot Framework 测试文件已生成。",
    failed: "录制失败",
    failedMsg: "录制过程中发生错误。",
    selector: {
      swapAriaLabel: "切换选择器策略",
      editOrAddAriaLabel: "编辑选择器或添加自定义选择器",
      editTitle: "编辑此选择器",
      addCustom: "添加自定义选择器",
      valuePlaceholder: "css=.btn-primary, //button[…], text=Click me, …",
      strategyLabel: "策略",
      verifiedUniqueTitle: "在录制时已在实时页面上验证唯一",
      effectiveLabel: "生效值",
      effectivePlaceholder: "iframe[…] >>> selector >> nth=0",
      effectiveTitle: "逐字写入 .robot 文件的内容。默认值：由策略 + 值 + iframe 链自动组合而成。此处的自定义值将覆盖该组合。",
      effectiveResetTitle: "重置为自动组合的值",
      effectiveOverrideTitle: "覆盖已生效——替换 iframe 包裹、xpath= / text= 前缀和 nth=0",
      effectiveOverrideBadge: "覆盖已生效",
      strategy: {
        testid: "Test ID",
        aria: "ARIA",
        text: "文本",
        css: "CSS",
        xpath: "XPath",
        pw_locator: "Playwright",
        automation_id: "AutomationId",
        uia_name: "UIA Name",
        uia_class_name: "UIA Class"
      }
    },
    launcher: {
      heading: "开始录制",
      hint: "录制一个用户流程并将其转换为 Robot Framework 测试。浏览器将在受控窗口中打开——无需安装扩展。",
      repoLabel: "保存到仓库",
      targetUrlLabel: "打开 URL（可选）",
      targetUrlPlaceholder: "https://example.com",
      targetUrlHint: "留空则从 about:blank 开始——浏览器打开后您可以手动导航。",
      targetUrlInvalid: "URL 必须以 http:// 或 https:// 开头",
      transportLabel: "您想录制什么？",
      transport: {
        web: "Web——基于浏览器的流程",
        windows: "桌面（Windows）",
        macos: "桌面（macOS）"
      },
      comingSoon: "即将推出",
      record: "开始录制",
      starting: "正在启动…",
      startFailed: "无法开始录制。请重试。",
      remote: {
        heading: "此服务器上无法进行浏览器录制",
        body: "本平台后端没有可视化浏览器（远程服务器部署的典型情况），因此无法为您打开 Chromium 窗口进行录制。请改为在您自己的机器上安装浏览器录制扩展——它会在您的浏览器中录制并将事件流式传输到此后端。",
        override: "后端管理员可通过服务端环境变量强制启用浏览器录制。"
      },
      reset: {
        hint: "录制卡住或浏览器窗口无法打开？点击重置以清理任何残留的会话和孤立的浏览器进程。",
        label: "重置卡住的录制",
        busy: "正在重置…",
        noneFound: "未找到卡住的录制。",
        done: "重置完成",
        doneDetail: "已清理 {count} 个卡住的录制会话。",
        failed: "重置失败。请重试。"
      }
    },
    live: {
      heading: "录制",
      status: {
        connecting: "连接中",
        live: "实时",
        done: "完成",
        error: "错误"
      },
      lifecycle: {
        connecting: "连接中…",
        browser_starting: "正在启动 Chromium…",
        browser_ready: "浏览器就绪",
        browser_restarting: "正在重启浏览器…",
        browser_crashed: "浏览器崩溃",
        done: "完成",
        error: "错误"
      },
      uptimeTitle: "自浏览器就绪以来的时间",
      effectiveTitle: "在 .robot 文件中作为关键字第一个参数的确切文本。",
      restartBrowser: "重启浏览器",
      restartingBrowser: "正在重启…",
      restartBrowserHint: "终止当前的 Chromium 并启动一个新的。已捕获的命令会被保留；您可能需要重新导航到您的测试页面。",
      restartFailed: "无法重启浏览器。",
      crashTitle: "浏览器崩溃：",
      waiting: "正在等待您的第一次交互…",
      pathLabel: "另存为",
      pathPlaceholder: "flows/recording",
      save: "保存",
      stopAndSave: "停止并保存",
      deleteStep: "删除此步骤",
      streamError: "与录制器的连接已断开。",
      startFailed: "无法启动浏览器。",
      saveFailed: "无法保存录制。",
      repoMissing: "仓库引用已丢失——请重新打开启动器。",
      restart: "开始新的录制"
    }
  },
  editor: {
    readOnly: {
      viewer: "只读——请让具有 EDITOR 角色的人员进行修改。",
      runner: "您可以运行测试，但无法编辑此文件。",
      contactAdmin: "联系您的 QA 负责人"
    },
    common: {
      save: "保存",
      cancel: "取消",
      delete: "删除",
      close: "关闭",
      back: "返回",
      add: "添加",
      edit: "编辑",
      create: "创建",
      error: "错误",
      loading: "加载中…",
      noData: "暂无数据。",
      confirm: "确认",
      yes: "是",
      no: "否",
      search: "搜索",
      install: "安装",
      installed: "已安装",
      upgrade: "升级",
      remove: "移除",
      clone: "克隆",
      retry: "重试",
      dismiss: "忽略",
      start: "开始",
      id: "ID",
      status: "状态",
      duration: "时长",
      created: "创建时间",
      actions: "操作",
      name: "名称",
      date: "日期",
      type: "类型",
      prevPage: "← 上一页",
      nextPage: "下一页 →",
      pageOf: "第 {current} 页，共 {total} 页",
      logout: "退出登录",
      menu: "菜单"
    }
  }
}, m_ = {
  robotEditor: {
    visualTab: "Visual Editor",
    flowTab: "Flow",
    codeTab: "Code",
    heal: {
      toggleOn: "Self-Healing: On",
      toggleOff: "Self-Healing: Off",
      toggleOnHint: "Click to revert every Heal* keyword in this file to its bare Browser-library form and remove the RoboScopeHeal import.",
      toggleOffHint: "Click to promote every Browser keyword (Click, Fill Text, …) to its Heal variant and add the RoboScopeHeal library import.",
      toastEnabled: "{count} keywords switched to Self-Healing.",
      toastDisabled: "{count} Heal keywords reverted to their bare form."
    },
    libraryNotInEnvTitle: 'Library "{library}" not installed in environment',
    libraryNotInEnvBody: "The Library import was added to the file, but {library} is not installed in this repo's environment. Only example keywords are shown until you install the package — open the Environments view to add it.",
    libraryNoEnvAtAllBody: "The Library import was added to {library}, but this repo has no environment configured. Configure one in the Environments view to install libraries.",
    libraryInstallDialogTitle: 'Install "{library}"?',
    libraryInstallDialogIntro: 'Library "{library}" is imported but not installed in environment "{env}". Pip-install it now so its full keyword list appears in the palette?',
    libraryInstallDialogPipName: "Pip package name",
    libraryInstallDialogPipHint: "You can edit this if the auto-detected name is wrong.",
    libraryInstallDialogConfirm: "Install",
    libraryInstallDialogInstalling: "Installing…",
    libraryInstallStartedTitle: "Install started",
    libraryInstallStartedBody: "{package} is installing. The palette will refresh once it completes.",
    libraryInstallFailed: "Install failed.",
    libraryAlreadyProvidedTitle: 'Library "{library}" already available',
    libraryAlreadyProvidedBody: '{library} is provided by "{provider}", which is already installed in the environment. The keyword cache may be stale — try restarting the rf-mcp server (Settings → AI → RF MCP) or wait a moment and switch back to this file to refresh.',
    tests: "tests",
    keywordsCount: "keywords",
    initFileWarning: "A suite init file (__init__.robot) cannot contain a Test Cases section.",
    parseError: "Parse Error",
    expandAll: "Expand All",
    collapseAll: "Collapse All",
    settingsSection: "Settings",
    variablesSection: "Variables",
    testCasesSection: "Test Cases",
    keywordsSection: "Keywords",
    addSetting: "Add Setting",
    addVariable: "Add Variable",
    addTestCase: "Add Test Case",
    addKeyword: "Add Keyword",
    addStep: "Add Step",
    addTag: "Add tag...",
    noSettings: 'No settings defined. Click "Add Setting" to add one.',
    noVariables: 'No variables defined. Click "Add Variable" to add one.',
    noTestCases: 'No test cases yet. Click "Add Test Case" to create one.',
    noKeywords: 'No keywords defined. Click "Add Keyword" to create one.',
    unnamedTestCase: "Unnamed Test Case",
    unnamedKeyword: "Unnamed Keyword",
    steps: "steps",
    testCaseName: "Test Case Name",
    testCaseNamePlaceholder: "My Test Case",
    keywordName: "Keyword Name",
    keywordNamePlaceholder: "My Keyword",
    documentation: "Documentation",
    documentationPlaceholder: "Optional documentation...",
    tags: "Tags",
    setup: "Setup",
    setupPlaceholder: "Setup keyword (optional)",
    teardown: "Teardown",
    teardownPlaceholder: "Teardown keyword (optional)",
    timeout: "Timeout",
    timeoutPlaceholder: "e.g. 30s",
    template: "Template",
    templatePlaceholder: "Template keyword (optional)",
    arguments: "Arguments",
    argumentsPlaceholder: "${arg1}    ${arg2}",
    addArgument: "Add argument, e.g. ${name}",
    argTypes: {
      required: "Required",
      optional: "Optional",
      varargs: "Variable",
      kwargs: "Key-Value",
      namedOnly: "Named-only",
      namedOnlyBelow: "Named-only arguments below",
      namePlaceholder: "name",
      defaultPlaceholder: "default",
      addDefault: "Add default value"
    },
    returnValue: "Return Value",
    returnValuePlaceholder: "${result}",
    stepPlaceholder: "Log    Hello World",
    settingValuePlaceholder: "Value",
    argPlaceholder: "arg",
    variableNamePlaceholder: "${VARIABLE_NAME}",
    variableValuePlaceholder: "value",
    keywordPlaceholder: "Keyword Name",
    conditionPlaceholder: "${x} > 5",
    valuePlaceholder: "value",
    exceptPatternPlaceholder: "Error pattern",
    addArg: "Add argument",
    addValue: "Add value",
    settingType: {
      Documentation: "Documentation",
      Library: "Library",
      Resource: "Resource",
      Variables: "Variables",
      SuiteSetup: "Suite Setup",
      SuiteTeardown: "Suite Teardown",
      TestSetup: "Test Setup",
      TestTeardown: "Test Teardown",
      TestTemplate: "Test Template",
      TestTimeout: "Test Timeout",
      ForceTags: "Force Tags",
      DefaultTags: "Default Tags",
      Metadata: "Metadata"
    },
    stepType: {
      keyword: "Keyword",
      assignment: "Assign",
      var: "VAR",
      comment: "Comment",
      for: "FOR",
      if: "IF",
      else_if: "ELSE IF",
      else: "ELSE",
      while: "WHILE",
      try: "TRY",
      except: "EXCEPT",
      finally: "FINALLY",
      end: "END",
      break: "BREAK",
      continue: "CONTINUE",
      return: "RETURN"
    },
    builtinKeyword: "Built-in",
    localKeyword: "Local",
    noSuggestions: "No matching keywords"
  },
  flowEditor: {
    noTestCasesYet: "No test cases in this file yet.",
    noKeywordsYet: "No keywords in this file yet.",
    addTestCase: "Test Case",
    addTestCaseTitle: "Add a new empty test case",
    addKeyword: "Keyword",
    addKeywordTitle: "Add a new empty keyword (user-defined keyword)",
    libraries: "Libraries",
    librariesTitle: "Manage Library and Resource imports for this file",
    librariesNone: "No Library or Resource imports yet.",
    libraryAdd: "Add",
    libraryInputPlaceholder: "Library name (e.g. Browser) or resource/path.resource",
    libraryRemoveTitle: "Remove {name}",
    variables: "Variables",
    variablesTitle: "Define suite variables (*** Variables ***) for this file",
    variablesNone: "No variables defined yet.",
    variableNamePlaceholder: "${NAME}",
    variableValuePlaceholder: "value",
    variableAdd: "Add",
    variableRemoveTitle: "Remove {name}",
    envVarsUsed: "Environment variables used:",
    envVarDefined: "Defined in {env}",
    envVarDefaultUsed: "Not in {env}. Inline default used",
    envVarMissing: "Not defined in environment {env}. The run fails unless the server's own environment provides it.",
    envVarCheckedAgainst: "Checked against environment {env}",
    envVarNotDefined: "(not defined)",
    templateAddRow: "Add row",
    templateAddColumn: "Add column",
    templateRemoveRow: "Remove row",
    suiteSettings: "Settings",
    suiteSettingsTitle: "Suite settings (Setup/Teardown, Tags, Documentation, Metadata)",
    suiteSettingsNone: "No suite settings yet.",
    suiteSettingRemoveTitle: "Remove {name}",
    keywordNotImportedHint: 'Library "{library}" is not imported in this file. Picking this keyword will add the import automatically.',
    keywordNotImportedBadgeTitle: "Library will be imported automatically when this keyword is added.",
    examplesCategoryBadge: "(examples)",
    examplesCategoryHint: "Curated subset shown because the dynamic library introspection returned no data — typically the repo has no environment configured, or the library is not installed in the environment. Configure an environment to see the full keyword list.",
    currentFileCategoryBadge: "this file",
    currentFileCategoryHint: "Keywords defined in the file you are currently editing.",
    resourcesSectionLabel: "Your resources",
    resourcesSectionHint: "Keywords from .robot / .resource files in this repository. Inserting one auto-adds the matching Resource import.",
    resourcePanelLabel: "Resources",
    librariesPanelLabel: "Libraries",
    importAdded: {
      resourceTitle: "Resource imported",
      libraryTitle: "Library imported",
      message: "Added {name} to *** Settings *** so the keyword resolves at runtime."
    },
    sort: {
      title: "Sort keywords",
      mostUsed: "Most used",
      alpha: "A–Z",
      importedFirst: "Imported first"
    },
    filter: {
      title: "Filter what's shown",
      resources: "Your resources",
      importedLibs: "Imported libraries",
      exampleLibs: "Example libraries (not installed)",
      builtin: "BuiltIn",
      hidingCategories: "{count} hidden",
      clear: "Show all"
    },
    resizePanelHint: "Drag to resize the detail panel",
    startNodeLabel: "Start",
    endNodeLabel: "End",
    keyword: "Keyword",
    arguments: "Arguments",
    condition: "Condition",
    returnVars: "Return Variables",
    returnValues: "Return Values",
    returnValuePlaceholder: '${result}, ${count}, "literal"…',
    returnNoValue: "(no value)",
    docMeta: {
      label: "Documentation",
      placeholder: "Describe what this test case (or keyword) does — visible in the HTML report.",
      hint: "Multi-line text is preserved as `...` continuation rows in the saved .robot file.",
      addTitle: "Add a [Documentation] side note to this test case / keyword",
      removeTitle: "Remove the documentation",
      alreadyPresent: "A [Documentation] is already attached — click the side note to edit it."
    },
    debug: {
      btnLabel: "Run up to here",
      btnTitle: "Run this test until the selected step and pause in debug mode",
      unresolvedLine: "Couldn't resolve this step's source line — save the file and try again.",
      startFailed: "Could not start the debug session.",
      unsavedHintTitle: "Save before debugging",
      unsavedHint: "You have unsaved changes. Save the file first so the debug breakpoint matches the source on disk.",
      confirmReplaceTitle: "Replace active debug session?",
      confirmReplace: "A debug session for this file is paused at line {lineFrom}. Disconnect it and start a new one at line {lineTo}?",
      confirmReplaceConfirm: "Disconnect and start"
    },
    heal: {
      toggleLabel: "Self-Healing",
      toggleHint: "Replace this Browser keyword with its Heal variant (locator auto-recovers if the page layout drifts)."
    },
    settingMeta: {
      allPresent: "Every [...] setting is attached — click a side note to edit it.",
      emptyHint: "click to edit",
      documentation: {
        label: "Documentation",
        placeholder: "Describe what this test case (or keyword) does — visible in the HTML report.",
        hint: "Multi-line text is preserved as `...` continuation rows in the saved .robot file.",
        addTitle: "Add a [Documentation] side note",
        removeTitle: "Remove the documentation"
      },
      tags: {
        label: "Tags",
        placeholder: "smoke, regression, slow",
        hint: "Comma-separated. Tags can be selected/excluded at run time via --include / --exclude.",
        addTitle: "Add [Tags] to this test case / keyword",
        removeTitle: "Remove the tags"
      },
      arguments: {
        label: "Arguments",
        placeholder: "${name}, ${greeting}=Hello",
        hint: "Comma-separated. Use ${name}=default for optional arguments, @{name} for varargs.",
        addTitle: "Add [Arguments] to this keyword",
        removeTitle: "Remove the arguments"
      },
      setup: {
        label: "Setup",
        placeholder: "Setup keyword name",
        hint: "Robot keyword executed before this test case / keyword body. Overrides Test Setup.",
        addTitle: "Add a [Setup] hook",
        removeTitle: "Remove the setup"
      },
      teardown: {
        label: "Teardown",
        placeholder: "Teardown keyword name",
        hint: "Robot keyword executed after the body, even on failure. Overrides Test Teardown.",
        addTitle: "Add a [Teardown] hook",
        removeTitle: "Remove the teardown"
      },
      template: {
        label: "Template",
        placeholder: "Template keyword name",
        hint: "Each row in the test body becomes a call to this keyword with the row as arguments.",
        addTitle: "Add a [Template] (data-driven test)",
        removeTitle: "Remove the template"
      },
      timeout: {
        label: "Timeout",
        placeholder: "30s, 5 minutes",
        hint: "Maximum runtime before this test case / keyword is forcefully aborted.",
        addTitle: "Add a [Timeout]",
        removeTitle: "Remove the timeout"
      }
    },
    testCaseSettings: "Test case settings",
    keywordSettings: "Keyword settings",
    comment: "Comment",
    palette: "Keywords",
    searchKeywords: "Search keywords...",
    noResults: "No matching keywords.",
    addArg: "Add argument",
    addVar: "Add variable",
    addValue: "Add value",
    loopVar: "Loop Variable",
    loopFlavor: "Loop Type",
    loopValues: "Values",
    exceptPattern: "Error Pattern",
    exceptVar: "Error Variable",
    varName: "Variable Name",
    varScope: "Scope",
    selector: {
      tooltipHasCandidates: "{count} recorded candidates — click to switch",
      customValueHint: "(custom value, not from recording)",
      candidatesBadge: "× {count}",
      replaceCustomConfirm: `Replace your custom selector

  {current}

with the recorded candidate

  {next}
?`
    },
    argLabels: {
      fallback: "arg {n}",
      extraPositional: "extra positional",
      extraNamed: "extra named",
      defaultPlaceholder: "default: {default}"
    },
    addArgPicker: {
      custom: "Custom value …",
      customLabel: "Custom value",
      customPlaceholder: "value or name=value",
      customAdd: "Add",
      noUnused: "All parameters already added.",
      namedHint: "named"
    },
    docModal: {
      title: "Keyword documentation",
      argsHeader: "Arguments",
      noDoc: "No documentation available.",
      button: "Show documentation",
      loading: "Loading documentation…"
    },
    argTypes: {
      text: "Text",
      integer: "Integer",
      number: "Number",
      yesNo: "On / Off",
      duration: "Duration",
      durationHint: "e.g. 5s, 1min, 00:00:05",
      path: "File path",
      any: "Any",
      choice: "Choice",
      collection: "List / map",
      unknown: "Value",
      optionalSuffix: " (optional)",
      customValue: "custom",
      toggleToText: "Switch to text input (e.g. for a variable)",
      toggleBackToTyped: "Switch back to typed input"
    }
  },
  debug: {
    btn: {
      label: "Debug",
      tooltip: "Re-run this test and pause at the failing line"
    },
    panel: {
      paused: "Paused",
      terminated: "Terminated",
      notPaused: "Waiting for first stop…",
      lineLabel: "Line",
      callStack: "Call stack",
      callStackEmpty: "No call stack yet — execution has not paused.",
      scopes: "Variables",
      scopesEmpty: "No scopes available.",
      scopeEmpty: "No variables in this scope.",
      output: "Output",
      outputEmpty: "No output yet.",
      toolbar: {
        continue: "Continue",
        stepOver: "Step Over",
        stepIn: "Step Into",
        stepOut: "Step Out",
        stop: "Stop"
      }
    },
    error: {
      title: "Debug session error",
      startFailed: "Could not start debug session.",
      commandFailed: "Debug command failed."
    },
    prereq: {
      title: "Debugger not installed",
      body: "{package} is not installed in this project's Python environment. Robot Autotest needs it to run interactive debugging. Install it now?",
      install: "Install",
      cancel: "Cancel",
      installing: "Installing {package}…",
      installFailed: "Install failed. See log for details.",
      installSuccess: "{package} installed successfully."
    }
  },
  recorder: {
    title: "Recording",
    record: "Record",
    startRecording: "Record browser interactions",
    stop: "Stop Recording",
    events: "events",
    generating: "Generating .robot file...",
    newRecording: "New Recording",
    tryAgain: "Try Again",
    cancelled: "Recording cancelled",
    waitingForEvents: "Waiting for browser interactions...",
    generatedFile: "Generated .robot File",
    completed: "Recording Complete",
    completedMsg: "Robot Framework test file has been generated.",
    failed: "Recording Failed",
    failedMsg: "An error occurred during recording.",
    selector: {
      swapAriaLabel: "Swap selector strategy",
      editOrAddAriaLabel: "Edit selector or add a custom one",
      editTitle: "Edit this selector",
      addCustom: "Add a custom selector",
      valuePlaceholder: "css=.btn-primary, //button[…], text=Click me, …",
      strategyLabel: "Strategy",
      verifiedUniqueTitle: "Verified unique on the live page at recording time",
      effectiveLabel: "Effective",
      effectivePlaceholder: "iframe[…] >>> selector >> nth=0",
      effectiveTitle: "What gets written verbatim to the .robot file. Default: auto-composed from strategy + value + iframe chain. A custom value here overrides the composition.",
      effectiveResetTitle: "Reset to auto-composed value",
      effectiveOverrideTitle: "Override active — replaces iframe wrap, xpath= / text= prefix and nth=0",
      effectiveOverrideBadge: "override active",
      strategy: {
        testid: "Test ID",
        aria: "ARIA",
        text: "Text",
        css: "CSS",
        xpath: "XPath",
        pw_locator: "Playwright",
        automation_id: "AutomationId",
        uia_name: "UIA Name",
        uia_class_name: "UIA Class"
      }
    },
    launcher: {
      heading: "Start a recording",
      hint: "Record a user flow and turn it into a Robot Framework test. The browser opens in a controlled window — no extension install required.",
      repoLabel: "Save to repository",
      targetUrlLabel: "Open URL (optional)",
      targetUrlPlaceholder: "https://example.com",
      targetUrlHint: "Leave blank to start at about:blank — you can navigate manually once the browser opens.",
      targetUrlInvalid: "URL must start with http:// or https://",
      transportLabel: "What do you want to record?",
      transport: {
        web: "Web — browser-based flow",
        windows: "Desktop (Windows)",
        macos: "Desktop (macOS)"
      },
      comingSoon: "coming soon",
      record: "Start recording",
      starting: "Starting…",
      startFailed: "Could not start the recording. Please try again.",
      remote: {
        heading: "Browser recording not available on this server",
        body: "The Robot Autotest backend has no viewable browser (typical for a remote server deployment), so it cannot open a Chromium window for you to record in. Install the browser recording extension on your own machine instead — it records in your browser and streams the events to this backend.",
        override: "Admins can force-enable browser recording through a backend environment variable."
      },
      reset: {
        hint: "Recording stuck or browser window won't open? Click Reset to clean up any leftover sessions and orphan browser processes.",
        label: "Reset stuck recordings",
        busy: "Resetting…",
        noneFound: "No stuck recordings found.",
        done: "Reset done",
        doneDetail: "Cleaned up {count} stuck recording session(s).",
        failed: "Reset failed. Please try again."
      }
    },
    live: {
      heading: "Recording",
      status: {
        connecting: "Connecting",
        live: "Live",
        done: "Done",
        error: "Error"
      },
      lifecycle: {
        connecting: "Connecting…",
        browser_starting: "Launching Chromium…",
        browser_ready: "Browser ready",
        browser_restarting: "Restarting browser…",
        browser_crashed: "Browser crashed",
        done: "Done",
        error: "Error"
      },
      uptimeTitle: "Time since the browser became ready",
      effectiveTitle: "The exact text that lands as the keyword's first argument in the .robot file.",
      restartBrowser: "Restart browser",
      restartingBrowser: "Restarting…",
      restartBrowserHint: "Kills the current Chromium and spawns a fresh one. Captured commands are preserved; you may need to re-navigate to your test page.",
      restartFailed: "Could not restart the browser.",
      crashTitle: "Browser crashed:",
      waiting: "Waiting for your first interaction…",
      pathLabel: "Save as",
      pathPlaceholder: "flows/recording",
      save: "Save",
      stopAndSave: "Stop and save",
      deleteStep: "Delete this step",
      streamError: "Lost connection to the recorder.",
      startFailed: "Could not launch the browser.",
      saveFailed: "Could not save the recording.",
      repoMissing: "Repository reference lost — open the launcher again.",
      restart: "Start a new recording"
    }
  },
  editor: {
    readOnly: {
      viewer: "Read-only — ask an EDITOR to change this.",
      runner: "You can run tests but not edit this file.",
      contactAdmin: "Contact your QA lead"
    },
    common: {
      save: "Save",
      cancel: "Cancel",
      delete: "Delete",
      close: "Close",
      back: "Back",
      add: "Add",
      edit: "Edit",
      create: "Create",
      error: "Error",
      loading: "Loading...",
      noData: "No data available.",
      confirm: "Confirm",
      yes: "Yes",
      no: "No",
      search: "Search",
      install: "Install",
      installed: "Installed",
      upgrade: "Upgrade",
      remove: "Remove",
      clone: "Clone",
      retry: "Retry",
      dismiss: "Dismiss",
      start: "Start",
      id: "ID",
      status: "Status",
      duration: "Duration",
      created: "Created",
      actions: "Actions",
      name: "Name",
      date: "Date",
      type: "Type",
      prevPage: "← Back",
      nextPage: "Next →",
      pageOf: "Page {current} of {total}",
      logout: "Logout",
      menu: "Menu"
    }
  }
}, F_ = {
  "zh-CN": f_,
  "en-US": m_
};
export {
  Wf as FlowEditor,
  ta as LOOP_FLAVORS,
  Gf as ReadOnlyBanner,
  R_ as RobotEditor,
  Ta as STEP_TYPES,
  Ea as VAR_SCOPES,
  ym as collectRbsIdsFromContent,
  A_ as configureEditorAdapter,
  ye as editorAdapter,
  F_ as editorMessages,
  Da as editorScope,
  La as emitMultilineSetting,
  rm as escapeRfCell,
  as as escapeRfToken,
  gm as initFileHasTestCases,
  mm as isInitFile,
  Ho as makeStep,
  vm as parseRobotText,
  pm as parseStepLine,
  N_ as resetEditorAdapter,
  V_ as resetEditorScope,
  hm as serializeRobotForm,
  fm as serializeStep,
  L_ as setEditorScope,
  Sa as unescapeRfCell,
  $n as unescapeRfToken
};
//# sourceMappingURL=editor.js.map
