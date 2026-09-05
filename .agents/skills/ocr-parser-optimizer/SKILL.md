---
name: "ocr-parser-optimizer"
description: "Optimizes mica-ppocr Java parsers via batch run, failure diagnosis, and LabelMatcher/regex fixes. Invoke when user provides image/PDF batches to improve a parser's accuracy or fix OCR-corrupted labels."
---

# OCR Parser Optimizer

Iterative, batch-driven optimization loop for the 10 built-in `mica-ppocr-structured` parsers. The
skill is **not** a metric calculator (use `ocr-accuracy-checker` for that) and **not** a parser
authoring kit (use `mica-ppocr-custom-parser` for new types). It sits between the two: given a
real batch of samples, it diagnoses why a field-level check fails, proposes a **minimal,
localized** fix, and re-verifies until the target is met.

## When to invoke

- User provides an image directory or PDF directory and asks to "调优 / 优化 / 修一下 / 看哪些样本解析不对"
  a specific parser (e.g. 行驶证 / 身份证 / 增值税发票 / 出租车票).
- User reports a regression ("升级后 X 字段错了") and wants to reproduce + diagnose on a batch.
- User wants to extend a parser's tolerance to a new document variant (e.g. 横向打印的营业执照,
  异形发票) by feeding a few real samples.

Do **not** invoke for: one-off single-image debugging (use `BaseTest` directly), pure metric
calculation on labeled data (`ocr-accuracy-checker`), or building a brand-new parser from scratch
(`mica-ppocr-custom-parser`).

## Inputs the agent should ask for up front

| Slot          | Required | Default                                                       |
| ------------- | -------- | ------------------------------------------------------------- |
| parser key    | yes      | one of `idcard vehicle driver bankcard business invoice train taxi household pdd` |
| input kind    | yes      | `image-dir` (png/jpg/jpeg/bmp/webp) or `pdf-dir` (recursively walked) |
| input path    | yes      | absolute path to the directory                                |
| ground truth  | no       | path to a `*.json` / `*.jsonl` map `{filename: {field: value}}`; if absent, run in **diagnosis-only** mode (visual diff only) |
| model tier    | no       | `tiny` (default) / `small` / `medium`                         |
| target field  | no       | if set, optimize only this field; otherwise optimize all fields |

Always confirm these slots in one round with `AskUserQuestion` before doing any work.

## Workflow (the optimization loop)

```
1.  prepare   --  collect input, ground truth, model tier
2.  baseline  --  run BatchOcrMain-style batch, compute per-field pass-rate
3.  diagnose  --  classify each failure into a Failure Pattern (see taxonomy)
4.  propose   --  emit minimal fix proposal (LabelMatcher / regex / heuristic)
5.  apply     --  edit the parser file in-place; preserve Java 8 + Lombok @Value constraints
6.  re-verify --  re-run baseline; compare; record metrics
7.  iterate   --  loop 3-6 until target hit or no fix improves metrics
8.  report    --  write optimization-reports/<parser>-<timestamp>.md
```

### 1. Prepare

- Walk the input directory:
  - image-dir: `CollUtil.listOf("png","jpg","jpeg","bmp","webp")`
  - pdf-dir:  recursive, every `*.pdf`; remind the user that PDF is a dual channel
    (text layer + OCR fallback) and tuning the parser only affects the OCR fallback path.
- Copy a few samples (3-5) into `test_images/<parser>/` if not already present so
  `BaseTest<Parser, Result>` single-image debug is available.
- If ground truth provided, load it once into a `Map<String, Map<String,String>>`.

### 2. Baseline

- For images, leverage the existing
  [`BatchOcrMain`](file:///e:/codes/gitee/mica-ppocr/mica-ppocr-structured/src/test/java/net/dreamlu/mica/ai/ppocr/structured/parser/batch/BatchOcrMain.java)
  entry. Either invoke it as a subprocess (Maven `exec:java`) or, in agent mode, replicate its
  core loop using `PPOcrV6Engine.run(Path)` / `run(byte[])` and a
  [`ParserSpec`](file:///e:/codes/gitee/mica-ppocr/mica-ppocr-structured/src/test/java/net/dreamlu/mica/ai/ppocr/structured/parser/batch/ParserSpec.java)
  instance.
- For PDFs, use `PPOcrV6Engine.run(Path)` directly (it auto-sniffs `%PDF-` and flattens all
  pages). One file may produce N per-page results; the diagnosis needs to be page-aware.
- Persist raw outputs to `optimization-reports/<parser>-<timestamp>/raw/<file>.txt` so each
  iteration diffs cleanly.
- If ground truth exists, compute field-level precision / recall / F1 with the
  `ocr-accuracy-checker` conventions (string equality + whitespace-trimmed compare).

### 3. Diagnose (Failure Pattern taxonomy)

Always classify every failure into exactly one of these buckets before proposing a fix. The
mapping is the heart of this skill.

| #   | Pattern                | Symptom                                              | Typical cause                                              |
| --- | ---------------------- | ---------------------------------------------------- | ---------------------------------------------------------- |
| F1  | label-not-found        | `matchValue` returns null for a field that exists    | OCR corrupted the label; need `findLabelBox` fallback or label variants |
| F2  | label-value-row-split  | value is on a different row / far below              | wrong y-overlap threshold; multi-line label handling        |
| F3  | value-truncated        | regex matched only a prefix, e.g. `1\*\*\*0`         | regex too strict; missing optional chars / spacing          |
| F4  | wrong-candidate-picked | value is from a similar label nearby (e.g. 登记 vs 抵押) | missing disambiguation; need nearest-right + y-overlap combo |
| F5  | value-merged-in-label  | label and value are in a single OCR box              | use `matchValueFromPrefix` instead of `matchValue`          |
| F6  | value-absent-in-ocr    | the field is genuinely missing from OCR output       | detection failure; try `small`/`medium` tier, or add a region pre-crop |
| F7  | garbled-value          | value present but unreadable (`¥` -> `羊`, `1` -> `l`) | model tier too low; switch to `small` / `medium`             |
| F8  | side-mis-routed        | e.g. idcard 正面字段跑到反面                         | `IdCardSide` logic bug; usually cross-line bleed            |
| F9  | field-box-wrong        | field extracted correctly but `getFieldBoxes()` points to a nearby box | re-use of `LabeledMatch.box` after position math              |

When in doubt, render the failure sample with `BaseTest.saveVis` (green boxes for OCR regions;
overlay the field's matched box) and look at the picture. Most F1/F4/F5 cases are obvious
once visualized.

### 4. Propose a fix

Always emit a **minimal, localized** fix proposal. Match the pattern to one of these templates:

- **F1** → add the OCR-mangled form to the label variant list:
  ```java
  // was: matchValue(results, "号牌号码")
  // after:
  findLabelBox(results, "号牌号码", "号牌编码", "号牌号吗")  // if such helper exists
  ```
  or relax the matcher. If the parser uses a literal string, list all observed OCR variants
  seen in the raw output of step 2 and feed them as alternates.

- **F2** → widen the right-overlap tolerance:
  ```java
  matchValue(results, label, LabelMatcher.DEFAULT_RIGHT_OVERLAP_TOLERANCE) // 5 -> 8
  ```
  or add a manual y-bias when the label and value rows are visually adjacent.

- **F3** → loosen the regex: `[\\d\\s-]{6,}` instead of `\\d{6,8}`; add optional spaces /
  dashes that OCR inserts; allow `·` for `*` masking.

- **F4** → prefer the value box with the **largest y-overlap** to the label box, not the
  leftmost. If two labels are visually adjacent, anchor on the label's y-center and pick the
  value box whose y-center is closest within ±N px.

- **F5** → swap `matchValue` → `matchValueFromPrefix`.

- **F6** → not a parser bug; recommend tier bump or region pre-crop. Do not change the
  parser for this.

- **F7** → recommend tier bump. Do not change the parser for this.

- **F8** → re-think `IdCardSide` / `InvoiceVersion` heuristics. Common fix: tighten
  top-region keyword list (e.g. add `中华人民共和国` for the 户口本 cover side).

- **F9** → preserve the matched box from `matchValueWithBox` / `matchSubstringWithBox` instead
  of re-deriving it from text. Use the box the matcher actually returned.

Output the proposal as a unified diff against the parser file. **Never** rewrite the whole
parser; only the failing field's match site.

### 5. Apply

- Edit the parser file in place. Honor project hard constraints:
  - Java 8 source level (no `record`, no `List.of`, no `instanceof` pattern, no `String.strip`).
    Use `CollUtil.listOf` / `CollUtil.repeat` / `CollUtil.stripTrailing` etc.
  - Lombok `@Value` + `@Accessors(fluent = true)` for value objects; `@UtilityClass` for
    static helpers; `@Slf4j` for logging.
  - Keep `BaseStructuredParser` SPI intact: `parseResults(List<PPOcrV6Result>) -> R`.
- Add the new label variant as a constant if it's reused, or inline as a `CollUtil.listOf(...)`
  if single-use. Do not introduce new helper classes unless two fields share the same fix.

### 6. Re-verify

- Re-run step 2 on the **same** batch and the **same** tier.
- Diff raw outputs directory-by-directory: `diff -ru` shows exactly which samples flipped.
- Re-compute field-level P/R/F1 if ground truth exists.
- Only merge the fix if metrics improve **and** no other field regresses (regression
  budget = 0 unless user explicitly waives it).

### 7. Iterate

Stop when any of:

- All fields ≥ target P/R/F1 (default 0.95 if user did not specify).
- No proposed fix improves both target metric and overall accuracy.
- Iteration count ≥ 5 (default budget; raise on user request).

Do not loop blindly; if 3 consecutive iterations have no metric gain, switch diagnosis to
**visualization review** and ask the user for a hint.

### 8. Report

Write a single Markdown report to
`optimization-reports/<parser>-<timestamp>.md` with this structure:

```
# Parser Optimization Report — <parser> @ <tier>
## Summary
- Baseline: P=…, R=…, F1=…
- Final:    P=…, R=…, F1=…
- Iterations: N, Net Δ: …

## Per-field metrics
| field  | baseline F1 | final F1 | Δ      | dominant pattern |
| ------ | ----------- | -------- | ------ | ---------------- |
| …      | …           | …        | …      | F1 / F3 / F6 …   |

## Applied fixes (chronological)
### Iteration 1 — F3 on `plateNo`
- Diff: `parser/vehicle/VehicleLicenseParser.java` L412-L418
- Rationale: …
- Impact: F1 0.81 → 0.93 on 12 samples

## Regressions
- (none / list)

## Remaining failures
| sample | field | pattern | next step |
| ------ | ----- | ------- | --------- |
| …      | …     | …       | tier bump |
```

Delete the per-iteration raw directories on completion (keep only the final baseline + final
raw snapshot inside the report folder).

## Code references (canonical)

- [`BaseStructuredParser`](file:///e:/codes/gitee/mica-ppocr/mica-ppocr-structured/src/main/java/net/dreamlu/mica/ai/ppocr/structured/parser/core/BaseStructuredParser.java) — SPI entry, do not break
- [`LabelMatcher`](file:///e:/codes/gitee/mica-ppocr/mica-ppocr-structured/src/main/java/net/dreamlu/mica/ai/ppocr/structured/parser/core/LabelMatcher.java) — `matchValue*` / `matchSubstring*` / `matchPattern` / geometry helpers
- [`BaseTest`](file:///e:/codes/gitee/mica-ppocr/mica-ppocr-structured/src/test/java/net/dreamlu/mica/ai/ppocr/structured/parser/core/BaseTest.java) — single-image debug + vis
- [`BatchOcrMain`](file:///e:/codes/gitee/mica-ppocr/mica-ppocr-structured/src/test/java/net/dreamlu/mica/ai/ppocr/structured/parser/batch/BatchOcrMain.java) — batch run baseline
- [`ParserSpec`](file:///e:/codes/gitee/mica-ppocr/mica-ppocr-structured/src/test/java/net/dreamlu/mica/ai/ppocr/structured/parser/batch/ParserSpec.java) — parser registry (key → class)

## Cross-references

- For pure metric computation (P/R/F1) on labeled data → `ocr-accuracy-checker`
- For building a brand-new parser from a single sample → `mica-ppocr-custom-parser`
- For running OCR on a directory without parser involvement → `BatchOcrMain` directly

## Conventions & guardrails

- **One parser at a time.** Do not touch other parsers in the same iteration even if the
  batch crosses types.
- **No tier-3 by default.** If the fix is "switch to medium", ask the user — medium adds
  130 MB and 10× latency.
- **No new dependencies.** All fixes must live within `mica-ppocr-structured`.
- **No silent regressions.** A fix that improves one field but breaks another must be
  reverted, not weakened.
- **Preserve bit-exactness** of OCR; the parser must never alter the OCR pipeline
  (engine, preprocessor, postprocessor, decoder). It only consumes
  `List<PPOcrV6Result>`.
- **Java 8 source level**: no Java 11+ APIs, no `record`, no `List.of`. Use `CollUtil`
  shims and Lombok `@Value` style.
- **Do not add `Co-Authored-By:` trailer** to any commit the skill may produce (project
  memory rule).
